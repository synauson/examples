package com.example.voiceagent;

import com.synauson.jsyn.JSyn;
import com.synauson.jsyn.NativeAudioFormat;
import com.synauson.jsyn.Subscription;
import com.synauson.jsyn.event.AgentEvent;
import com.synauson.jsyn.event.PlayedWord;
import com.synauson.jsyn.participant.Conference;
import com.synauson.jsyn.participant.NativeParticipant;
import com.synauson.jsyn.spec.NativeParticipantSpec;
import com.synauson.jsyn.spec.Speak;
import com.synauson.jsyn.spec.TtsConfig;
import com.synauson.jsyn.spec.TurnDetectionConfig;
import com.synauson.jsyn.spec.VadConfig;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Text to speech: a native participant with a speaker, whose audio Java reads from the
 * participant's ring and saves as a WAV file, with the time each word played.
 */
final class SpeakToFile {
    private SpeakToFile() {}

    static int run(Path workDir, String text, Path out) throws Exception {
        try (JSyn jsyn = Engine.start(workDir)) {
            System.out.println("Loading text to speech...");
            Engine.awaitSpeech(jsyn, Duration.ofMinutes(15));

            NativeAudioFormat format = NativeAudioFormat.PCM_S16LE16K_MONO;
            String id = "listener";
            try (Conference conf = jsyn.startConference("speak");
                 // A speaker plays to its own participant; reading the participant's ring
                 // gives what a caller on it would hear. It needs turn detection (and VAD):
                 // what it plays is reported on the participant's agent stream.
                 NativeParticipant listener = conf.addNativeParticipant(id, NativeParticipantSpec.builder()
                         .format(format)
                         .vad(VadConfig.defaults())
                         .turnDetection(TurnDetectionConfig.defaults())
                         .tts(TtsConfig.defaults().withVoice("en-us-f3"))
                         .build())) {
                BlockingQueue<AgentEvent> events = new LinkedBlockingQueue<>();
                ByteArrayOutputStream audio = new ByteArrayOutputStream();
                try (Subscription sub = conf.streamAgentEvents(id, events::add)) {
                    conf.speak(id, Speak.complete("greeting", text));

                    byte[] buffer = new byte[format.bytesPer20ms() * 10];
                    boolean done = false;
                    while (!done) {
                        int n;
                        while ((n = listener.read(buffer, 0, buffer.length)) > 0) {
                            audio.write(buffer, 0, n);
                        }
                        AgentEvent event = events.poll(20, TimeUnit.MILLISECONDS);
                        if (event instanceof AgentEvent.WordsPlayed played) {
                            for (PlayedWord word : played.words) {
                                System.out.printf("  %5d ms  %s%n", word.startMs, word.text);
                            }
                        } else if (event instanceof AgentEvent.UtteranceDone finished) {
                            // underrunMs: time the caller heard silence because synthesis
                            // fell behind playback (0 on a machine sized for the load).
                            System.out.println("Spoke " + finished.audioMs + " ms of audio; "
                                    + finished.underrunMs + " ms waiting for synthesis.");
                            done = true;
                        } else if (event instanceof AgentEvent.UtteranceFailed failed) {
                            System.err.println("Speech failed: " + failed.code + " " + failed.message);
                            return 1;
                        }
                    }
                    // What the mixer still holds.
                    Thread.sleep(200);
                    int n;
                    while ((n = listener.read(buffer, 0, buffer.length)) > 0) {
                        audio.write(buffer, 0, n);
                    }
                }
                conf.removeParticipant(id);
                Wav.write(out, audio.toByteArray(), format.sampleRate());
                System.out.println("Wrote " + out.toAbsolutePath());
                return 0;
            }
        }
    }
}
