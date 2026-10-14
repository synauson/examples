package com.example.licensing;

import com.synauson.jsyn.Capabilities;
import com.synauson.jsyn.JSyn;
import com.synauson.jsyn.NativeAudioFormat;
import com.synauson.jsyn.participant.Conference;
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
import static com.example.licensing.Tour.step;

/**
 * Chapter 3: capabilities and sessions. Every license has every AI feature; each
 * participant using any of them takes one AI session from the license's session pool,
 * and each conference one conference from its conference pool.
 */
final class CapabilitiesChapter {

    static final String STT = "FEATURE_STT";

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
        // The turn detector is driven by VAD's speech-end events, so turn detection needs both.
        return NativeParticipantSpec.builder()
                .format(NativeAudioFormat.PCM_S16LE16K_MONO)
                .vad(VadConfig.defaults())
                .turnDetection(TurnDetectionConfig.defaults())
                .build();
    }

    static void run(Tour tour) throws InterruptedException {
        Tour.chapter("3 · Capabilities and sessions",
                "The AI features are capabilities: FEATURE_VAD, FEATURE_TURN_DETECTION, FEATURE_STT "
                + "and FEATURE_TTS. Every license has all of them, and conferencing without AI. "
                + "Licenses differ only in how many AI sessions and conferences they run at once: "
                + "the Free base of 4 sessions and 10 conferences that every license has, plus what "
                + "it buys. Each participant using any capability takes one AI session from the "
                + "license's session pool, whatever it uses, and every conference takes one "
                + "conference from its conference pool, with AI or without.");

        if (!Files.isRegularFile(tour.dir("state/online").resolve(OnlineChapter.CACHE_FILE))) {
            throw new Tour.TourFailure("run the online chapter first: this one reuses its license and models");
        }

        try (JSyn jsyn = new JSyn(tour.config(tour.dir("state/online")).build())) {
            Deque<AutoCloseable> open = new ArrayDeque<>();
            try {
                Tour.awaitEntitledModels(jsyn, Duration.ofMinutes(10));
                Capabilities caps = Tour.awaitLease(jsyn, Duration.ofSeconds(60));
                for (Capabilities.CapabilityInfo c : caps.capabilities) {
                    expect(c.entitled, "every license includes " + c.code);
                }
                ok("every capability is entitled; AI sessions " + CapabilitiesPrinter.pool(caps.sessions)
                        + " (base " + caps.sessions.base + ", purchased "
                        + (caps.sessions.purchased == null ? "unlimited" : caps.sessions.purchased) + ")");
                Conference conference = jsyn.startConference("capabilities");
                open.push(conference);
                caps = jsyn.capabilities();
                expect(caps.conferences.inUse == 1, "a conference takes one of the license's conferences");
                ok("the conference takes one conference: conferences "
                        + CapabilitiesPrinter.pool(caps.conferences));

                step("A participant without AI");
                open.push(conference.addNativeParticipant("plain", plain()));
                caps = jsyn.capabilities();
                expect(caps.sessions.inUse == 0, "a participant without AI takes no session");
                ok("joined; AI sessions " + CapabilitiesPrinter.pool(caps.sessions)
                        + ". Conferences and participants without AI take no AI session.");

                step("Turn detection (VAD and the turn detector on one participant)");
                open.push(conference.addNativeParticipant("turn", withTurnDetection()));
                caps = jsyn.capabilities();
                expect(caps.sessions.inUse == 1, "a participant with two detectors takes one session");
                ok("AI sessions " + CapabilitiesPrinter.pool(caps.sessions)
                        + ": one session, however many detectors the participant has");

                step("VAD on its own, in the same conference");
                int room = Tour.sessionCap(caps);
                boolean second = room >= 2;
                if (second) {
                    open.push(conference.addNativeParticipant("vad", withVad()));
                    caps = jsyn.capabilities();
                    expect(caps.sessions.inUse == 2, "a second AI participant takes a second session");
                    ok("AI sessions " + CapabilitiesPrinter.pool(caps.sessions)
                            + ": sessions count participants, not conferences");
                } else {
                    note("skipped: this runtime's share of the pool is " + room + " session"
                            + ", because other runtimes on this license hold the rest");
                }

                step("STT and TTS");
                ok(STT + " and FEATURE_TTS are entitled like the rest. jsyn's speech to text and "
                        + "text to speech guides show participants with them; the tour leaves them "
                        + "out, since their models are large to run.");

                step("The AI participants leave");
                conference.removeParticipant("turn");
                if (second) conference.removeParticipant("vad");
                caps = jsyn.capabilities();
                expect(caps.sessions.inUse == 0, "removing the AI participants releases their sessions");
                expect(caps.sessions.peak >= (second ? 2 : 1), "the report keeps the peak");
                ok("their sessions are released at once: AI sessions "
                        + CapabilitiesPrinter.pool(caps.sessions) + ", peak " + caps.sessions.peak
                        + ". The conference keeps running with 'plain'.");
                note("A change to your license's sessions or conferences reaches running runtimes at their next "
                        + "renewal, within about a day, and applies to participants added after it.");
            } finally {
                Tour.closeAll(open);
            }
        }
    }
}
