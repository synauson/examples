package com.synauson.testbed.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for the testbed.
 *
 * <p>Bound to the {@code testbed.*} prefix; values come from {@code application.yml}
 * and can be overridden with environment variables (Spring Boot's relaxed binding
 * maps {@code TESTBED_MODELS_DIR} to {@code testbed.models-dir}).
 *
 * <p>String fields are required; Spring fails-fast if the backing property is
 * unresolvable. Numeric fields use boxed types ({@code Float}, {@code Integer})
 * so they are nullable, but every numeric field carries a sensible default in
 * {@code application.yml} so null values do not occur under normal deployment.
 *
 * @param modelsDir       Absolute path to the directory containing {@code silero_vad.onnx}
 *                        and {@code smart_turn.onnx}; passed straight to
 *                        {@link com.synauson.jsyn.JSynConfig.Builder#modelsDir(String)}.
 * @param conferenceId    The single fixed conference ID the testbed creates on boot.
 * @param stunServer      STUN URI for ICE negotiation; default
 *                        {@code stun://stun.l.google.com:19302}. Public STUN is fine
 *                        for the host-networking demo mode.
 * @param vadThreshold    Override for VAD's speech-vs-silence threshold (0–1).
 * @param vadMinSilenceMs Override for the minimum silence before {@code SpeechEnd}.
 * @param vadMinSpeechMs  Override for the minimum speech before {@code SpeechStart}.
 * @param smartTurnBufferedSamples       Override for SmartTurn's samples-buffered-before-inference.
 * @param smartTurnConfidenceThreshold   Override for SmartTurn's "turn complete" threshold.
 */
@ConfigurationProperties(prefix = "testbed")
public record TestbedProperties(
    String modelsDir,
    String conferenceId,
    String stunServer,
    Float vadThreshold,
    Integer vadMinSilenceMs,
    Integer vadMinSpeechMs,
    Integer smartTurnBufferedSamples,
    Float smartTurnConfidenceThreshold
) {}
