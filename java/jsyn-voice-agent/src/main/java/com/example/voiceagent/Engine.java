package com.example.voiceagent;

import com.synauson.jsyn.Capabilities;
import com.synauson.jsyn.JSyn;
import com.synauson.jsyn.JSynConfig;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Starts a jsyn runtime with speech to text and text to speech, and waits until both can
 * take participants.
 */
final class Engine {
    private Engine() {}

    /**
     * A runtime whose model store and license cache live under {@code workDir}. Keep both
     * on a volume in production: the models are large and download at startup.
     */
    static JSyn start(Path workDir) {
        JSynConfig config = JSynConfig.builder()
                // licenseKey is left unset: the runtime reads $SYNAUSON_LICENSE_KEY.
                .modelStore(workDir.resolve("models").toString())
                .stateDir(workDir.resolve("state").toString())
                // Eager end of turn with STT: decoding is set aside for its forecasts.
                .sttEager(true)
                .build();
        return new JSyn(config);
    }

    /**
     * Fails unless the license may use speech to text and text to speech now, then waits
     * for their models to download and load. On first start that takes a few minutes.
     */
    static void awaitSpeech(JSyn jsyn, Duration within) throws InterruptedException {
        Capabilities caps = jsyn.capabilities();
        List<String> missing = caps.capabilities.stream()
                .filter(c -> (c.code.equals("FEATURE_STT") || c.code.equals("FEATURE_TTS")) && !c.entitled)
                .map(c -> c.code)
                .toList();
        if (!missing.isEmpty()) {
            // Every license has them; only a license that starts nothing new (refused by
            // the licensing server, or 7 days without a usage report) can't use them.
            throw new IllegalStateException("the license can't use " + missing + " now ("
                    + caps.license.state + ": " + caps.license.description + ")");
        }

        long deadline = System.nanoTime() + within.toNanos();
        String last = "";
        while (true) {
            caps = jsyn.capabilities();
            String stt = caps.stt == null ? "absent" : caps.stt.state;
            String tts = caps.tts == null ? "absent" : caps.tts.state;
            if (stt.equals("ready") && tts.equals("ready")) {
                return;
            }
            if (stt.equals("failed") || tts.equals("failed")) {
                throw new IllegalStateException("speech failed to load: stt "
                        + (caps.stt == null ? "" : caps.stt.detail) + ", tts "
                        + (caps.tts == null ? "" : caps.tts.detail));
            }
            String now = "speech to text " + stt + ", text to speech " + tts + "; models: "
                    + caps.models.stream().map(m -> m.id + " " + m.state).toList();
            if (!now.equals(last)) {
                System.out.println("  waiting: " + now);
                last = now;
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("speech not ready within " + within + ": " + now);
            }
            Thread.sleep(500);
        }
    }
}
