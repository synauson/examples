package com.synauson.testbed;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the JSyn WebRTC testbed.
 *
 * <p>This Spring Boot service hosts a Vite-built React SPA from
 * {@code classpath:/static}, accepts per-browser WebSocket connections at
 * {@code /ws/room/{participantId}}, and owns a single {@link com.synauson.jsyn.JSyn}
 * runtime that runs the WebRTC audio conference internally.
 *
 * <p>The project is intended as a <em>reference implementation</em>: code throughout
 * this module is written for readers learning the JSyn API, so most JSyn-touching
 * methods carry Javadoc explaining lifecycle ordering and rationale that you would
 * not write in production code. See
 * {@code docs/superpowers/specs/2026-05-26-jsyn-webrtc-testbed-design.md} in the
 * synauson repo for the architectural background.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class TestbedApplication {
    public static void main(String[] args) {
        SpringApplication.run(TestbedApplication.class, args);
    }
}
