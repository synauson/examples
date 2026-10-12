package com.synauson.testbed.signaling;

import com.synauson.testbed.room.ConferenceService;
import com.synauson.testbed.signaling.envelope.ClientMessage;
import com.synauson.testbed.signaling.envelope.ServerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Per-frame dispatcher between the browser and {@link ConferenceService}.
 *
 * <p>Spring's WebSocket layer invokes each method on a single thread per session,
 * so we don't need synchronisation here — but we do need to be careful that
 * {@link #handleTextMessage} stays fast (the dispatch into ConferenceService
 * happens on this thread and acquires a service-wide lock).
 *
 * <p>The handler uses Java 21 pattern matching on the sealed {@link ClientMessage}
 * hierarchy — adding a new message variant becomes a compile error here.
 */
@Component
public class RoomWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RoomWebSocketHandler.class);

    private final ConferenceService service;
    private final ObjectMapper mapper;

    public RoomWebSocketHandler(ConferenceService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String pid = pidOf(session);
        log.info("WS open: pid={}, session={}", pid, session.getId());
        service.onSocketConnected(pid, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage frame) {
        String pid = pidOf(session);
        ClientMessage msg;
        try {
            msg = mapper.readValue(frame.getPayload(), ClientMessage.class);
        } catch (Exception ex) {
            log.warn("Bad client frame from pid={}: {}", pid, ex.getMessage());
            sendError(session, "BAD_REQUEST", "Could not parse message: " + ex.getMessage());
            return;
        }

        // Exhaustive switch over the sealed hierarchy.
        switch (msg) {
            case ClientMessage.SdpOffer o     -> service.onSdpOffer(pid, session, o.sdp());
            case ClientMessage.IceCandidate c -> service.onIceCandidate(pid, c.candidate(), c.sdpMLineIndex());
            case ClientMessage.Leave leave        -> service.onLeave(pid, session);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String pid = pidOf(session);
        log.info("WS close: pid={}, session={}, status={}", pid, session.getId(), status);
        service.onSocketClosed(pid, session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WS transport error pid={}: {}", pidOf(session), exception.getMessage());
        // Spring will follow up with afterConnectionClosed; nothing else to do here.
    }

    private String pidOf(WebSocketSession session) {
        return (String) session.getAttributes().get(PathParticipantIdInterceptor.PARTICIPANT_ID_ATTR);
    }

    private void sendError(WebSocketSession session, String code, String message) {
        try {
            String json = mapper.writeValueAsString(new ServerMessage.Error(code, message));
            session.sendMessage(new TextMessage(json));
        } catch (Exception ignored) {
            // Best-effort; nothing actionable if the socket itself is broken.
        }
    }
}
