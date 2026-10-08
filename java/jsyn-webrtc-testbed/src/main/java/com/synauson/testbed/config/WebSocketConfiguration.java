package com.synauson.testbed.config;

import com.synauson.testbed.signaling.PathParticipantIdInterceptor;
import com.synauson.testbed.signaling.RoomWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the {@code /ws/room/{participantId}} endpoint and attaches the
 * path-based handshake interceptor. {@code setAllowedOriginPatterns("*")} is
 * intentional: the testbed has zero security per the design spec.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfiguration implements WebSocketConfigurer {

    private final RoomWebSocketHandler handler;

    public WebSocketConfiguration(RoomWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/room/{participantId}")
                .addInterceptors(new PathParticipantIdInterceptor())
                .setAllowedOriginPatterns("*");
    }
}
