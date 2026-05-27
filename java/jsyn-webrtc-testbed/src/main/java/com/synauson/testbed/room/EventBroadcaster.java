package com.synauson.testbed.room;

import com.synauson.jsyn.event.IceCandidateEvent;
import com.synauson.jsyn.event.SmartTurnEvent;
import com.synauson.jsyn.event.VadEvent;
import com.synauson.testbed.signaling.envelope.ServerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Fans out VAD, Smart-Turn, and outbound ICE candidate events to all sockets.
 *
 * <p>Two responsibilities:
 * <ol>
 *   <li><b>Epoch validation.</b> Each dispatch receives the epoch the
 *       subscription was created with; if the pid's current session
 *       has a different epoch (because of a recreate), the event is
 *       a stale callback and gets dropped.
 *   <li><b>Wire-format translation.</b> JSyn's
 *       {@link VadEvent} / {@link SmartTurnEvent} records get converted into
 *       the testbed's {@link ServerMessage.VadEvent} / {@link ServerMessage.TurnEvent}
 *       envelopes, with the server-side wall-clock timestamp.</li>
 * </ol>
 *
 * <p>ICE candidates are special: they only go to the participant whose
 * webrtcbin produced them (not fanned out — the other browser doesn't need
 * to see this participant's local candidates).
 */
@Component
public class EventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(EventBroadcaster.class);
    private static final DateTimeFormatter T_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final ObjectMapper mapper;

    public EventBroadcaster(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Handle a VAD event. Called from JSyn's stream observer (a Rust-owned thread).
     *
     * @param pid       participant the event belongs to
     * @param epoch     epoch the subscription was created with
     * @param sessions  live snapshot of all sessions
     * @param event     the JSyn record (sealed: SpeechStart | SpeechEnd)
     */
    public void dispatchVad(String pid, long epoch,
                            Map<String, ParticipantSession> sessions, VadEvent event) {
        if (isStale(pid, epoch, sessions)) return;
        ServerMessage.VadEvent envelope = switch (event) {
            case VadEvent.SpeechStart s ->
                new ServerMessage.VadEvent(pid, ServerMessage.VadKind.SPEECH_START,
                    LocalTime.now().format(T_FMT), s.confidence, null);
            case VadEvent.SpeechEnd e ->
                new ServerMessage.VadEvent(pid, ServerMessage.VadKind.SPEECH_END,
                    LocalTime.now().format(T_FMT), null, e.durationMs);
            default -> null; // VadEvent is abstract (not sealed); shouldn't occur
        };
        if (envelope == null) return;
        broadcast(sessions, envelope);
    }

    /** Smart-Turn event fan-out. */
    public void dispatchTurn(String pid, long epoch,
                              Map<String, ParticipantSession> sessions, SmartTurnEvent event) {
        if (isStale(pid, epoch, sessions)) return;
        if (!(event instanceof SmartTurnEvent.TurnResult r)) return;
        var envelope = new ServerMessage.TurnEvent(
            pid, LocalTime.now().format(T_FMT), r.probability, r.turnComplete);
        broadcast(sessions, envelope);
    }

    /**
     * Outbound trickle ICE candidate from synauson's webrtcbin.
     *
     * <p>Sent only to the originating browser. The other peer doesn't need to
     * know synauson's local ICE candidates — that's between this peer and the
     * SFU.
     */
    public void dispatchIce(String pid, long epoch,
                              Map<String, ParticipantSession> sessions, IceCandidateEvent event) {
        if (isStale(pid, epoch, sessions)) return;
        var envelope = new ServerMessage.IceCandidate(
            event.candidate, event.sdpMLineIndex, event.endOfCandidates);
        ParticipantSession session = sessions.get(pid);
        if (session != null) sendTo(session, envelope);
    }

    private boolean isStale(String pid, long epoch, Map<String, ParticipantSession> sessions) {
        ParticipantSession current = sessions.get(pid);
        return current == null || current.epoch() != epoch;
    }

    private void broadcast(Map<String, ParticipantSession> sessions, ServerMessage msg) {
        TextMessage frame;
        try {
            frame = new TextMessage(mapper.writeValueAsString(msg));
        } catch (Exception ex) {
            log.error("Failed to serialise event", ex);
            return;
        }
        for (ParticipantSession s : sessions.values()) {
            sendQuietly(s, frame);
        }
    }

    private void sendTo(ParticipantSession session, ServerMessage msg) {
        try {
            sendQuietly(session, new TextMessage(mapper.writeValueAsString(msg)));
        } catch (Exception ex) {
            log.error("Failed to serialise per-pid event", ex);
        }
    }

    private void sendQuietly(ParticipantSession session, TextMessage frame) {
        if (!session.socket().isOpen()) return;
        try {
            session.socket().sendMessage(frame);
        } catch (Exception ex) {
            log.debug("send to {} failed (ignored): {}",
                session.participantId(), ex.getMessage());
        }
    }
}
