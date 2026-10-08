package com.synauson.testbed;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.synauson.testbed.config.AiFeatures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Starts the whole application against the real JSyn runtime: loads the natives,
 * exchanges the license key, downloads the licensed models and starts the conference.
 *
 * <p>Runs when {@code SYNAUSON_LICENSE_KEY} is set and GStreamer 1.26 is installed:
 * <pre>{@code
 *   SYNAUSON_LICENSE_KEY=... ./gradlew test
 * }</pre>
 * Set {@code TESTBED_EXPECT_DETECTORS=true} when the key includes VAD and turn
 * detection, to also require both detectors (CI does).
 */
@SpringBootTest
@TestPropertySource(properties = {
    "testbed.model-store=${java.io.tmpdir}/synauson-testbed-it/models",
    "testbed.state-dir=${java.io.tmpdir}/synauson-testbed-it/state",
    "testbed.conference-id=ctx-load-test",
})
@EnabledIfEnvironmentVariable(named = "SYNAUSON_LICENSE_KEY", matches = ".+")
class TestbedApplicationTests {

    @Autowired
    private AiFeatures ai;

    @Test
    void contextLoads() {
        if (Boolean.parseBoolean(System.getenv("TESTBED_EXPECT_DETECTORS"))) {
            assertTrue(ai.vad(), "VAD should be available");
            assertTrue(ai.turnDetection(), "turn detection should be available");
        }
    }
}
