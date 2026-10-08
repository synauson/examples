package com.synauson.testbed.config;

import com.synauson.jsyn.JSyn;
import com.synauson.jsyn.JSynConfig;
import com.synauson.jsyn.participant.Conference;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the JSyn runtime and the single fixed conference into the Spring context.
 *
 * <h2>Why this is a {@code @Configuration} with two beans, not one big initializer</h2>
 *
 * <p>JSyn (the runtime) and Conference (the audio session) have <em>distinct</em>
 * lifecycles. JSyn loads GStreamer + ONNX Runtime — process-global libraries that
 * must be initialised exactly once per JVM. Conference is a tenant of that
 * runtime; tearing it down is fast, tearing JSyn down is slow because it stops
 * the GStreamer thread pool. Modelling them as two beans gives Spring's shutdown
 * machinery the dependency information it needs to close them in the correct
 * order (Conference first, then JSyn).
 *
 * <h2>Why both beans use {@code destroyMethod = "close"}</h2>
 *
 * <p>Both classes implement {@link AutoCloseable}. Spring honours
 * {@code destroyMethod = "close"} as part of context shutdown, so a JVM exit
 * triggered by SIGTERM (the only signal Docker ever sends a stopping container)
 * propagates cleanly through the shutdown hook → Spring context close → bean
 * destruction in reverse-dependency order → JSyn native cleanup.
 *
 * <h2>Why {@code @Bean} ordering matters even when both depend on each other only indirectly</h2>
 *
 * <p>Conference declares {@code JSyn} as a constructor parameter, so Spring
 * resolves JSyn first and shuts it down last. If we passed both as autowired
 * fields, Spring's shutdown order would be unspecified and we'd risk freeing
 * the runtime while the conference still held onto pipeline state — which
 * presents as a hang or a native segfault, not a clean exception.
 */
@Configuration
public class JSynConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JSynConfiguration.class);

    /**
     * The JSyn runtime — one per JVM.
     *
     * <p>Constructing the bean loads the native libraries via
     * {@link com.synauson.jsyn.internal.NativeLoader#load()}, runs a GStreamer
     * sanity check, and initialises ONNX Runtime. None of this is reversible
     * within the JVM lifetime; calling {@link JSyn#close()} (which Spring
     * invokes at shutdown thanks to {@code destroyMethod = "close"}) tears
     * everything down but a second {@link JSyn} cannot be constructed afterwards.
     *
     * <p>Configuration values come from {@link TestbedProperties}. The
     * license key is exchanged for a signed license file (cached in the state
     * directory) before the constructor returns; a missing or refused key makes
     * it throw. The RTP port range matters for SIP participants (unused in this
     * testbed, but JSynConfig requires a value), the STUN server matters for
     * the WebRTC ICE exchange.
     *
     * @param props testbed properties; injected by Spring
     * @return the JSyn runtime, ready to start conferences
     */
    @Bean(destroyMethod = "close")
    public JSyn jsyn(TestbedProperties props) {
        log.info("Initialising JSyn runtime with model store {}", props.modelStore());
        JSynConfig cfg = JSynConfig.builder()
                // Null reads SYNAUSON_LICENSE_KEY from the environment.
                .licenseKey(blankToNull(props.licenseKey()))
                .modelStore(blankToNull(props.modelStore()))
                .stateDir(blankToNull(props.stateDir()))
                .rtpPortMin(40000)       // unused for WebRTC, but JSynConfig requires it
                .rtpPortMax(40199)
                .webrtcStunServer(props.stunServer())
                .webrtcJitterBufferMs(200)
                .build();
        return new JSyn(cfg);
    }

    /**
     * The detectors each participant gets, once the licensed models have
     * downloaded. See {@link AiFeatures#await}.
     *
     * @param jsyn  the JSyn runtime; injected
     * @param props testbed properties; injected
     * @return the detectors the license and the model store allow
     * @throws InterruptedException if startup is interrupted while waiting
     */
    @Bean
    public AiFeatures aiFeatures(JSyn jsyn, TestbedProperties props) throws InterruptedException {
        return AiFeatures.await(jsyn, Duration.ofSeconds(props.modelWaitSeconds()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * The single fixed conference the testbed hosts.
     *
     * <p>Created on context refresh; survives for the JVM's lifetime. The
     * conference ID is supplied via {@link TestbedProperties#conferenceId()},
     * defaulting to {@code "testbed"}.
     *
     * <p>{@link Conference#close()} → {@link Conference#terminate()} releases
     * every participant before tearing down the conference itself, which is
     * exactly what we want on shutdown. The constructor parameter {@code jsyn}
     * encodes the dependency that Spring uses to schedule this bean's
     * destruction <em>before</em> the JSyn bean's.
     *
     * @param jsyn  the JSyn runtime; injected
     * @param props testbed properties; injected
     * @return a fresh, empty {@link Conference} that participants can be added to
     */
    @Bean(destroyMethod = "close")
    public Conference testbedConference(JSyn jsyn, TestbedProperties props) {
        log.info("Starting conference {}", props.conferenceId());
        return jsyn.startConference(props.conferenceId());
    }
}
