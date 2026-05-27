package com.synauson.testbed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Context-load smoke test. Requires the JSyn natives and ONNX models to be
 * available; otherwise the JSyn bean's constructor throws and the test fails.
 *
 * <p>Disabled by default; enable explicitly when you have models on disk:
 * <pre>{@code
 *   ./gradlew test -Drun.integration.tests=true \
 *     -Dtestbed.models.path=/abs/path/to/models
 * }</pre>
 */
@SpringBootTest
@TestPropertySource(properties = {
    "testbed.models-dir=${testbed.models.path:/tmp/synauson-models-missing}",
    "testbed.conference-id=ctx-load-test",
})
@EnabledIfSystemProperty(named = "run.integration.tests", matches = "true")
class TestbedApplicationTests {
    @Test
    void contextLoads() {
        // No assertions — if @SpringBootTest's context refresh completes,
        // the JSyn runtime is up and the conference was created.
    }
}
