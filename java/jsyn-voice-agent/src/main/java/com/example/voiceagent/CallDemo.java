package com.example.voiceagent;

import com.synauson.jsyn.JSyn;
import com.synauson.jsyn.event.AgentEvent;
import com.synauson.jsyn.participant.Conference;
import com.synauson.jsyn.participant.NativeParticipant;
import com.synauson.jsyn.participant.SipParticipantHandle;
import com.synauson.jsyn.spec.NativeParticipantSpec;
import com.synauson.jsyn.spec.SipParticipantSpec;
import com.synauson.jsyn.spec.SttConfig;
import com.synauson.jsyn.spec.TtsConfig;
import com.synauson.jsyn.spec.TurnConfigUpdate;
import com.synauson.jsyn.spec.TurnDetectionConfig;
import com.synauson.jsyn.spec.VadConfig;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * One call: the caller's participant with VAD, turn detection, STT and a speaker; the
 * {@link VoiceAgent} on its agent stream; and a simulated caller who books a table,
 * changes their mind while the agent is still talking, and hangs up. The caller is a
 * native participant fed from Java ({@link SimulatedCaller}), or a SIP participant whose
 * far end is a phone sending RTP ({@link SimulatedPhone}).
 */
final class CallDemo {
    private CallDemo() {}

    static final String BOOKING = "Hi, I'd like to book a table for tomorrow evening.";
    static final String CHANGE = "Sorry, make that four people at seven.";
    static final String BYE = "No, that's all. Thank you, bye.";

    /**
     * @param sip       the caller is on a SIP call rather than a native participant
     * @param lateJoin  how long the conference runs before the caller joins
     * @param recording where to write what the caller heard
     */
    record Options(boolean sip, Duration lateJoin, Path recording) {}

    /**
     * @param agentEvents what the agent's stream handed it
     * @param callEvents  the whole call's stored events, to StreamEnded
     * @param replies     each reply's utterance id and the text sent for it
     */
    record Result(List<AgentEvent> agentEvents, List<AgentEvent> callEvents,
                  List<VoiceAgent.Line> history, Map<String, String> replies, int reconnects) {}

    static int run(Path workDir, Options options) throws Exception {
        try (JSyn jsyn = Engine.start(workDir)) {
            System.out.println("Loading speech to text and text to speech...");
            Engine.awaitSpeech(jsyn, Duration.ofMinutes(15));
            call(jsyn, options, (event, stream) -> {});
        }
        return 0;
    }

    /** Waits for the call to reach a point, failing at once with the agent's own failure. */
    private static void awaitCall(CallWatch watch, AtomicReference<Throwable> failure, String what,
                                  Predicate<List<AgentEvent>> done) throws InterruptedException {
        try {
            watch.awaitUntil(what, e -> failure.get() != null || done.test(e));
        } catch (IllegalStateException timedOut) {
            if (failure.get() == null) throw timedOut;
        }
        if (failure.get() != null) {
            throw new IllegalStateException("the agent failed", failure.get());
        }
    }

    /** What the caller's participant carries for the agent. */
    private static TurnDetectionConfig turnDetection() {
        // Eager end of turn: an early signal that the caller has probably finished.
        return TurnDetectionConfig.defaults().withTurns(TurnConfigUpdate.none().withEager(true));
    }

    private static final TtsConfig AGENT_VOICE = TtsConfig.defaults().withVoice("en-us-f1");

    static Result call(JSyn jsyn, Options options, BiConsumer<AgentEvent, AgentStream> onAgentEvent)
            throws Exception {
        System.out.println("Preparing the simulated caller's voice...");
        Map<String, byte[]> lines = Rehearsal.lines(jsyn, BOOKING, CHANGE, BYE);

        String callerId = "caller";
        try (Conference conf = jsyn.startConference("call-" + UUID.randomUUID().toString().substring(0, 8))) {
            if (!options.lateJoin().isZero()) {
                System.out.println("The conference runs for " + options.lateJoin().toSeconds() + " s before the call.");
                Thread.sleep(options.lateJoin().toMillis());
            }
            NativeParticipant nativeCaller = null;
            Caller person;
            if (options.sip()) {
                // What your SIP stack does with the SDP: the phone's offer named its address,
                // port and codec; the answer carries the port jsyn bound for the call.
                SimulatedPhone phone = new SimulatedPhone(lines);
                SipParticipantHandle sip = conf.addSipParticipant(SipParticipantSpec.builder()
                        .participantId(callerId)
                        .remoteIp(phone.ip())
                        .remoteRtpPort(phone.rtpPort())
                        .codec("PCMU")
                        .vad(VadConfig.defaults())
                        .turnDetection(turnDetection())
                        .stt(SttConfig.defaults())
                        .tts(AGENT_VOICE)
                        .build());
                phone.connect(sip.localRtpPort());
                person = phone;
            } else {
                nativeCaller = conf.addNativeParticipant(callerId, NativeParticipantSpec.builder()
                        .format(Rehearsal.FORMAT)
                        .vad(VadConfig.defaults())                     // speech start and stop
                        .turnDetection(turnDetection())                // when the caller has finished
                        .stt(SttConfig.defaults())                     // what they said
                        .tts(AGENT_VOICE)                              // the agent's voice, played to them
                        .build());
                person = new SimulatedCaller(nativeCaller, lines);
            }

            CallWatch watch = new CallWatch(conf, callerId);
            AgentStream stream = new AgentStream(conf, callerId);
            VoiceAgent agent = new VoiceAgent(conf, callerId, onAgentEvent);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread agentThread = new Thread(() -> {
                try {
                    agent.run(stream);
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "voice-agent");
            try {
                stream.start();
                agentThread.start();
                System.out.println("Call started.");
                // The caller speaks each line once turn detection has decided the last
                // one, so a line never joins the turn before it however long that takes.
                person.say(BOOKING);
                // They change their mind while the agent is still answering (if its answer
                // already ended, they simply speak next).
                awaitCall(watch, failure, "decided booking turn and the agent's answer",
                        e -> CallWatch.turnsDecided(e, 1)
                                && (CallWatch.agentTalking(e) || CallWatch.answeredSinceLastTurn(e)));
                person.say(CHANGE);
                awaitCall(watch, failure, "decided change turn and its answer played to its end",
                        e -> CallWatch.turnsDecided(e, 2) && CallWatch.answeredSinceLastTurn(e));
                person.say(BYE);

                agentThread.join(60_000);
                if (failure.get() != null) {
                    throw new IllegalStateException("the agent failed", failure.get());
                }
                if (agentThread.isAlive()) {
                    throw new IllegalStateException("the agent didn't end the call within 60 s");
                }
            } finally {
                agentThread.interrupt();
                agent.close();
                stream.close();
                person.close();
                conf.removeParticipant(callerId);
                watch.awaitEnd();
                watch.close();
                if (nativeCaller != null) {
                    nativeCaller.close();
                }
            }
            Wav.write(options.recording(), person.heard(), person.sampleRate());
            System.out.println("Call ended. What the caller heard: " + options.recording().toAbsolutePath());
            return new Result(agent.events(), watch.events(), agent.history(), agent.replies(), stream.reconnects());
        }
    }
}
