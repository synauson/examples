package com.example.voiceagent;

import com.synauson.jsyn.JSyn;
import com.synauson.jsyn.NativeAudioFormat;
import com.synauson.jsyn.Subscription;
import com.synauson.jsyn.event.AgentEvent;
import com.synauson.jsyn.participant.Conference;
import com.synauson.jsyn.participant.NativeParticipant;
import com.synauson.jsyn.spec.NativeParticipantSpec;
import com.synauson.jsyn.spec.Speak;
import com.synauson.jsyn.spec.TtsConfig;
import com.synauson.jsyn.spec.TurnDetectionConfig;
import com.synauson.jsyn.spec.VadConfig;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Test scaffolding, not part of an agent: the simulated caller's voice. Its lines are
 * synthesised before the call by a second speaker, on a participant of its own in a
 * conference of its own, so each line plays without a pause and the call's conference
 * starts only when the call does.
 */
final class Rehearsal {
    private Rehearsal() {}

    static final NativeAudioFormat FORMAT = NativeAudioFormat.PCM_S16LE16K_MONO;
    private static final int FRAME = FORMAT.bytesPer20ms();
    private static final String VOICE_ID = "simulated-voice";

    /** Each line's audio, 16 kHz mono 16-bit PCM. */
    static Map<String, byte[]> lines(JSyn jsyn, String... texts) throws InterruptedException {
        Map<String, byte[]> lines = new LinkedHashMap<>();
        try (Conference conf = jsyn.startConference("rehearsal");
             NativeParticipant voice = conf.addNativeParticipant(VOICE_ID, NativeParticipantSpec.builder()
                     .format(FORMAT)
                     .vad(VadConfig.defaults())
                     .turnDetection(TurnDetectionConfig.defaults())
                     .tts(TtsConfig.defaults().withVoice("en-us-m1"))
                     .build())) {
            BlockingQueue<AgentEvent> spoken = new LinkedBlockingQueue<>();
            try (Subscription sub = conf.streamAgentEvents(VOICE_ID, spoken::add)) {
                for (int i = 0; i < texts.length; i++) {
                    String id = "line-" + i;
                    conf.speak(VOICE_ID, Speak.complete(id, texts[i]));
                    ByteArrayOutputStream audio = new ByteArrayOutputStream();
                    byte[] buffer = new byte[FRAME * 10];
                    boolean done = false;
                    while (!done) {
                        AgentEvent e = spoken.poll(20, TimeUnit.MILLISECONDS);
                        done = e instanceof AgentEvent.UtteranceDone d && d.utteranceId.equals(id);
                        if (e instanceof AgentEvent.UtteranceFailed f) {
                            throw new IllegalStateException("couldn't synthesise a line: " + f.message);
                        }
                        int n;
                        while ((n = voice.read(buffer, 0, buffer.length)) > 0) {
                            audio.write(buffer, 0, n);
                        }
                    }
                    lines.put(texts[i], closeGaps(audio.toByteArray()));
                }
            }
            conf.removeParticipant(VOICE_ID);
        }
        return lines;
    }

    /**
     * Drops the silence before and after the speech, and shortens pauses over 250 ms (a
     * slow machine leaves them where synthesis fell behind playback) to 150 ms.
     */
    private static byte[] closeGaps(byte[] pcm) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int maxQuiet = 250 * 32;  // 16 kHz, 2 bytes a sample
        int keepQuiet = 150 * 32;
        int quietFrom = -1;
        boolean spoken = false;
        for (int at = 0; at + FRAME <= pcm.length; at += FRAME) {
            boolean quiet = true;
            for (int i = at; i < at + FRAME; i += 2) {
                if (Math.abs((short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8))) > 300) {
                    quiet = false;
                    break;
                }
            }
            if (quiet) {
                if (quietFrom < 0) quietFrom = at;
                continue;
            }
            if (quietFrom >= 0 && spoken) {
                int len = at - quietFrom;
                out.write(pcm, quietFrom, len > maxQuiet ? keepQuiet : len);
            }
            quietFrom = -1;
            spoken = true;
            out.write(pcm, at, FRAME);
        }
        return out.toByteArray();
    }
}
