package com.synauson.testbed.config;

import com.synauson.jsyn.Capabilities;
import com.synauson.jsyn.JSyn;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which AI detectors the testbed attaches to each participant.
 *
 * <p>A detector is used only when the license includes its capability and its model is
 * on disk. At startup the JSyn runtime downloads the models the license includes in the
 * background, so {@link #await} polls {@link JSyn#capabilities()} until every one of
 * them is ready, or the timeout passes. Asking for a detector whose model hasn't arrived
 * throws {@code FailedPreconditionException}, and asking for one the license doesn't
 * include throws {@code PermissionDeniedException}; checking first lets the testbed run
 * with whatever the license allows instead of refusing every browser.
 *
 * @param vad           attach Voice Activity Detection
 * @param turnDetection attach Smart Turn end-of-turn detection
 */
public record AiFeatures(boolean vad, boolean turnDetection) {

    private static final Logger log = LoggerFactory.getLogger(AiFeatures.class);

    /**
     * Wait for the licensed models, then report which detectors are usable.
     *
     * @param jsyn    the runtime
     * @param timeout how long to wait for model downloads
     * @return the detectors to attach
     * @throws InterruptedException if interrupted while waiting
     */
    public static AiFeatures await(JSyn jsyn, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        Capabilities caps = jsyn.capabilities();
        while (!licensedModelsReady(caps) && System.nanoTime() < deadline) {
            Thread.sleep(500);
            caps = jsyn.capabilities();
        }
        for (Capabilities.ModelInfo m : caps.models) {
            log.info("Model {} {}: {}{}", m.id, m.version, m.state,
                    m.detail == null ? "" : " (" + m.detail + ")");
        }

        boolean sileroReady = ready(caps, "silero-vad");
        boolean turn = entitled(caps, "FEATURE_TURN_DETECTION") && sileroReady && ready(caps, "smart-turn");
        // Turn detection includes the VAD it runs on, so a turn-detection license also
        // allows VAD on its own.
        boolean vad = (entitled(caps, "FEATURE_VAD") || turn) && sileroReady;
        AiFeatures features = new AiFeatures(vad, turn);
        log.info("License: {}. Detectors: VAD {}, turn detection {}",
                caps.license.description, vad ? "on" : "off", turn ? "on" : "off");
        return features;
    }

    /** Every model is ready or not included in the license. */
    private static boolean licensedModelsReady(Capabilities caps) {
        return caps.models.stream().allMatch(m -> "ready".equals(m.state) || "not-entitled".equals(m.state));
    }

    private static boolean ready(Capabilities caps, String modelId) {
        return caps.models.stream().anyMatch(m -> m.id.equals(modelId) && "ready".equals(m.state));
    }

    private static boolean entitled(Capabilities caps, String code) {
        return caps.capabilities.stream().anyMatch(c -> c.code.equals(code) && c.entitled);
    }
}
