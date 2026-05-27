package com.synauson.testbed.room;

import com.synauson.testbed.signaling.envelope.ServerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fan-out of {@link ServerMessage.Roster} snapshots to every open WebSocket.
 *
 * <p>The roster carries one {@link ServerMessage.RosterEntry} per
 * currently-attached participant. Browsers use the roster to drive the
 * Lobby's "Presence card" (Section: "Single-participant mode" of the spec)
 * AND the Call screen's tile placement (self vs peer).
 *
 * <p>Joined-at timestamps are minted server-side at connection time and
 * preserved across subsequent broadcasts.
 */
@Component
public class RosterBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(RosterBroadcaster.class);

    private final ObjectMapper mapper;
    private final Map<String, Instant> joinedAt = new ConcurrentHashMap<>();

    public RosterBroadcaster(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Record the time a participant first opened a WebSocket. Called once per pid. */
    public void recordJoin(String pid) {
        joinedAt.putIfAbsent(pid, Instant.now());
    }

    /** Forget a participant's joined-at timestamp on full disconnect. */
    public void forget(String pid) {
        joinedAt.remove(pid);
    }

    /** Build a snapshot of the current roster from the live sessions map. */
    public ServerMessage.Roster snapshot(Map<String, ParticipantSession> sessions) {
        List<ServerMessage.RosterEntry> entries = sessions.keySet().stream()
            .map(pid -> new ServerMessage.RosterEntry(
                pid,
                joinedAt.getOrDefault(pid, Instant.now()).toString()))
            .toList();
        return new ServerMessage.Roster(entries);
    }

    /**
     * Send the same roster snapshot to every open socket in {@code sessions}.
     * Swallowed errors (broken sockets) are logged but not propagated.
     */
    public void broadcast(Map<String, ParticipantSession> sessions, ServerMessage.Roster snapshot) {
        TextMessage frame;
        try {
            frame = new TextMessage(mapper.writeValueAsString(snapshot));
        } catch (Exception ex) {
            log.error("Failed to serialise roster snapshot", ex);
            return;
        }
        for (ParticipantSession s : sessions.values()) {
            sendQuietly(s.socket(), frame);
        }
    }

    /** Send a roster snapshot to a single socket (used on socket-open). */
    public void sendTo(WebSocketSession session, ServerMessage.Roster snapshot) {
        try {
            sendQuietly(session, new TextMessage(mapper.writeValueAsString(snapshot)));
        } catch (Exception ex) {
            log.error("Failed to send roster to single socket", ex);
        }
    }

    private void sendQuietly(WebSocketSession session, TextMessage frame) {
        if (!session.isOpen()) return;
        try {
            session.sendMessage(frame);
        } catch (Exception ex) {
            log.debug("send to {} failed (ignored): {}", session.getId(), ex.getMessage());
        }
    }
}
