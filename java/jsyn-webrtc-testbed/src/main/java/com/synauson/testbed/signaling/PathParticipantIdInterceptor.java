package com.synauson.testbed.signaling;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the participant ID from the WebSocket handshake URL and stores it
 * on the session's attribute map under {@link #PARTICIPANT_ID_ATTR}.
 *
 * <p>URL scheme: {@code /ws/room/<participantId>}. The ID is URL-decoded once
 * here; downstream handlers should not decode it again.
 */
public class PathParticipantIdInterceptor implements HandshakeInterceptor {

    public static final String PARTICIPANT_ID_ATTR = "participantId";

    private static final Pattern PATH_PATTERN =
        Pattern.compile("^/ws/room/([^/]+)/?$");

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String path = request.getURI().getPath();
        Matcher m = PATH_PATTERN.matcher(path);
        if (!m.matches()) return false;        // Spring will 404 the handshake
        String pid = java.net.URLDecoder.decode(m.group(1), java.nio.charset.StandardCharsets.UTF_8);
        if (pid.isBlank()) return false;
        attributes.put(PARTICIPANT_ID_ATTR, pid);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception ex) {
        // No-op; required by the interface.
    }
}
