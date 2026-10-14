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
 * here; downstream handlers should not decode it again. jsyn accepts ids of 1 to 128
 * characters from {@code A-Z}, {@code a-z}, {@code 0-9}, {@code .}, {@code _} and
 * {@code -} and throws {@code InvalidArgumentException} for any other, so the
 * handshake refuses other ids before they reach jsyn.
 */
public class PathParticipantIdInterceptor implements HandshakeInterceptor {

    public static final String PARTICIPANT_ID_ATTR = "participantId";

    private static final Pattern PATH_PATTERN =
        Pattern.compile("^/ws/room/([^/]+)/?$");

    /** The ids jsyn accepts for conferences and participants. */
    public static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String path = request.getURI().getPath();
        Matcher m = PATH_PATTERN.matcher(path);
        if (!m.matches()) return false;        // Spring will 404 the handshake
        String pid = java.net.URLDecoder.decode(m.group(1), java.nio.charset.StandardCharsets.UTF_8);
        if (!VALID_ID.matcher(pid).matches()) return false;
        attributes.put(PARTICIPANT_ID_ATTR, pid);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception ex) {
        // No-op; required by the interface.
    }
}
