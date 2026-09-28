package com.example.licensing;

import com.synauson.jsyn.Capabilities;
import com.synauson.jsyn.JSyn;
import com.synauson.jsyn.NativeAudioFormat;
import com.synauson.jsyn.exception.PermissionDeniedException;
import com.synauson.jsyn.participant.Conference;
import com.synauson.jsyn.spec.NativeParticipantSpec;
import com.synauson.jsyn.spec.SmartTurnConfig;
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
 * Chapter 3: capabilities. The license decides which AI features a participant may use,
 * and each participant a feature runs on counts as one stream of it.
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
        // Smart Turn is driven by VAD's speech-end events, so turn detection runs both.
        return NativeParticipantSpec.builder()
                .format(NativeAudioFormat.PCM_S16LE16K_MONO)
                .vad(VadConfig.defaults())
                .smartTurn(SmartTurnConfig.defaults())
                .build();
    }

    static void run(Tour tour) throws InterruptedException {
        Tour.chapter("3 · Capabilities",
                "The AI features are capabilities: FEATURE_VAD (voice activity detection) and "
                + "FEATURE_TURN_DETECTION (Smart Turn). Every license includes conferencing. Asking "
                + "for a capability the license lacks throws PermissionDeniedException naming the "
                + "code. Each participant a capability runs on is one stream of it, and a conference "
                + "with at least one AI stream is an AI conference. Licenses can limit both.");

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
                        + ". A participant without AI needs no capability and counts toward no AI limit.");

                step("Turn detection (VAD + Smart Turn on one participant)");
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
                            + ": the VAD inside turn detection is included");
                    ok("AI conferences " + CapabilitiesPrinter.usage(caps.aiConferences));
                } else {
                    PermissionDeniedException e = Tour.expectThrows(PermissionDeniedException.class,
                            () -> conference.addNativeParticipant("turn", withTurnDetection()));
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
                    PermissionDeniedException e = Tour.expectThrows(PermissionDeniedException.class,
                            () -> conference.addNativeParticipant("vad", withVad()));
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
                ok("their streams and the AI conference are released at once; the conference, "
                        + "still running with 'plain', now counts only as a conference");
                note("A change to your license's capabilities reaches running runtimes at their "
                        + "next renewal, within about a day, and applies to participants added after it.");
            } finally {
                Tour.closeAll(open);
            }
        }
    }
}
