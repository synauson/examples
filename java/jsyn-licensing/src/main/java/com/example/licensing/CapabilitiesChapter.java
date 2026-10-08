package com.example.licensing;

import com.synauson.jsyn.Capabilities;
import com.synauson.jsyn.JSyn;
import com.synauson.jsyn.NativeAudioFormat;
import com.synauson.jsyn.exception.PermissionDeniedException;
import com.synauson.jsyn.participant.Conference;
import com.synauson.jsyn.participant.NativeParticipant;
import com.synauson.jsyn.spec.NativeParticipantSpec;
import com.synauson.jsyn.spec.TurnDetectionConfig;
import com.synauson.jsyn.spec.VadConfig;

import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

import static com.example.licensing.Tour.expect;
import static com.example.licensing.Tour.note;
import static com.example.licensing.Tour.ok;
import static com.example.licensing.Tour.refused;
import static com.example.licensing.Tour.step;

/**
 * Chapter 3: capabilities. Each AI feature is a capability the license includes or
 * doesn't; each participant it runs on is one stream of it.
 */
final class CapabilitiesChapter {

    static final String VAD = "FEATURE_VAD";
    static final String TURN_DETECTION = "FEATURE_TURN_DETECTION";

    private CapabilitiesChapter() {}

    static NativeParticipantSpec plain() {
        return NativeParticipantSpec.builder().format(NativeAudioFormat.PCM_S16LE16K_MONO).build();
    }

    static NativeParticipantSpec withVad() {
        return NativeParticipantSpec.builder()
                .format(NativeAudioFormat.PCM_S16LE16K_MONO)
                .vad(VadConfig.defaults())
                .build();
    }

    static NativeParticipantSpec withTurnDetection() {
        // Turn detection is driven by VAD's speech-end events, so turn detection runs both.
        return NativeParticipantSpec.builder()
                .format(NativeAudioFormat.PCM_S16LE16K_MONO)
                .vad(VadConfig.defaults())
                .turnDetection(TurnDetectionConfig.defaults())
                .build();
    }

    static void run(Tour tour) throws InterruptedException {
        Tour.chapter("3 · Capabilities",
                "A capability is an AI feature a license includes or doesn't: FEATURE_VAD "
                + "(voice activity detection) and FEATURE_TURN_DETECTION (turn detection). Conferencing "
                + "itself is in every license. Asking for a capability the license lacks throws "
                + "PermissionDeniedException naming the code. Each participant a capability runs on "
                + "is one stream of it, and a conference with any AI stream is an AI conference; "
                + "licenses can limit both.");

        if (!Files.isRegularFile(tour.dir("state/online").resolve(OnlineChapter.CACHE_FILE))) {
            throw new Tour.TourFailure("run the online chapter first: this one reuses its license and models");
        }

        try (JSyn jsyn = new JSyn(tour.config(tour.dir("state/online")).build())) {
            Deque<AutoCloseable> open = new ArrayDeque<>();
            try {
                Tour.awaitEntitledModels(jsyn, Duration.ofMinutes(5));
                Conference conference = jsyn.startConference("capabilities");
                open.push(conference);

                step("A participant without AI");
                open.push(conference.addNativeParticipant("plain", plain()));
                Capabilities caps = jsyn.capabilities();
                expect(caps.aiConferences.inUse == 0, "a participant without AI uses no AI capacity");
                ok("joined; AI conferences " + CapabilitiesPrinter.usage(caps.aiConferences)
                        + ". Audio alone never touches entitlements or AI limits.");

                step("Turn detection (VAD + turn detection on one participant)");
                boolean turnEntitled = Tour.capability(caps, TURN_DETECTION).entitled;
                if (turnEntitled) {
                    open.push(conference.addNativeParticipant("turn", withTurnDetection()));
                    caps = jsyn.capabilities();
                    expect(Tour.capability(caps, TURN_DETECTION).streams.inUse == 1,
                            "turn detection counts one " + TURN_DETECTION + " stream");
                    expect(Tour.capability(caps, VAD).streams.inUse == 0,
                            "the VAD inside turn detection counts no " + VAD + " stream");
                    expect(caps.aiConferences.inUse == 1, "the conference now counts as an AI conference");
                    ok(TURN_DETECTION + " streams " + CapabilitiesPrinter.usage(Tour.capability(caps, TURN_DETECTION).streams));
                    ok(VAD + " streams " + CapabilitiesPrinter.usage(Tour.capability(caps, VAD).streams)
                            + ": turn detection includes the VAD that drives it, free");
                    ok("AI conferences " + CapabilitiesPrinter.usage(caps.aiConferences));
                } else {
                    PermissionDeniedException e = expectDenied(() -> conference.addNativeParticipant("turn", withTurnDetection()));
                    expect(e.getMessage().contains(TURN_DETECTION), "the refusal names " + TURN_DETECTION);
                    refused(e);
                    ok("refused: this license doesn't include turn detection");
                }

                step("VAD on its own");
                boolean vadEntitled = Tour.capability(caps, VAD).entitled;
                if (vadEntitled) {
                    open.push(conference.addNativeParticipant("vad", withVad()));
                    caps = jsyn.capabilities();
                    expect(Tour.capability(caps, VAD).streams.inUse == 1, "VAD on its own counts one " + VAD + " stream");
                    expect(caps.aiConferences.inUse == 1, "a second AI stream in the same conference is still one AI conference");
                    ok(VAD + " streams " + CapabilitiesPrinter.usage(Tour.capability(caps, VAD).streams));
                    ok("AI conferences still " + CapabilitiesPrinter.usage(caps.aiConferences)
                            + ": the AI-conference count is per conference, not per stream");
                } else {
                    PermissionDeniedException e = expectDenied(() -> conference.addNativeParticipant("vad", withVad()));
                    expect(e.getMessage().contains(VAD), "the refusal names " + VAD);
                    refused(e);
                    ok("refused: this license doesn't include VAD on its own"
                            + (turnEntitled ? " (it still runs inside turn detection)" : ""));
                }

                step("The AI participants leave");
                if (turnEntitled) conference.removeParticipant("turn");
                if (vadEntitled) conference.removeParticipant("vad");
                caps = jsyn.capabilities();
                expect(caps.aiConferences.inUse == 0
                                && Tour.capability(caps, VAD).streams.inUse == 0
                                && Tour.capability(caps, TURN_DETECTION).streams.inUse == 0,
                        "removing the AI participants releases their streams and the AI conference");
                ok("streams and the AI conference are released the moment their participants go; "
                        + "the conference itself (still running with 'plain') counts only as a conference");
                note("An entitlement added to or removed from your license reaches running "
                        + "runtimes at their next renewal (within about a day), and affects only "
                        + "participants added after it.");
            } finally {
                Tour.closeAll(open);
            }
        }
    }

    interface Call {
        NativeParticipant run();
    }

    static PermissionDeniedException expectDenied(Call call) {
        try {
            NativeParticipant p = call.run();
            p.close();
            throw new Tour.TourFailure("expected PermissionDeniedException, but the participant was added");
        } catch (PermissionDeniedException e) {
            return e;
        }
    }
}
