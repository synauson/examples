package com.synauson.testbed.room;

import com.synauson.jsyn.Subscription;
import com.synauson.jsyn.participant.Conference;
import com.synauson.jsyn.participant.WebRtcParticipantHandle;
import com.synauson.jsyn.spec.ConnectionEntry;
import com.synauson.jsyn.spec.ConnectionMatrix;
import com.synauson.jsyn.spec.VadConfig;
import com.synauson.jsyn.spec.SmartTurnConfig;
import com.synauson.jsyn.spec.WebRtcParticipantSpec;
import com.synauson.testbed.config.TestbedProperties;
import com.synauson.testbed.signaling.envelope.ServerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The testbed orchestrator.
 *
 * <p>Holds the single JSyn-owned {@link Conference} and the live map of
 * {@link ParticipantSession}s. Every JSyn-touching operation goes through
 * here so we can centralise the recreate-on-rejoin and conference-full
 * invariants.
 *
 * <h2>Concurrency model</h2>
 *
 * <p>{@code ConcurrentHashMap} stores the sessions and supports lock-free
 * reads for hot paths (event fan-out). The {@link #lock} serialises every
 * sequence that touches more than one piece of state — adding a participant,
 * removing one, recreating on rejoin, rewiring the routing matrix. The lock
 * is <strong>never held across WebSocket sends on the success path</strong>:
 * SDP answer delivery and roster broadcast happen after {@code lock.unlock()}.
 * JSyn participant lifecycle calls ({@code addWebRtcParticipant},
 * {@code removeParticipant}, subscription registration) are intentionally inside
 * the lock because atomicity across those steps is required for the epoch and
 * recreate-on-rejoin invariants to hold. GStreamer pipeline latency on these
 * calls is bounded and acceptable given the two-participant cap.
 *
 * <h2>The epoch token</h2>
 *
 * <p>Every time a participant is added, an {@link AtomicLong#incrementAndGet()}
 * mints an epoch. The epoch lives inside the {@link ParticipantSession} and
 * is passed into every subscription callback. {@link EventBroadcaster} drops
 * any callback whose epoch doesn't match the pid's current session — which
 * protects against late callbacks from a session that was just superseded
 * by a rejoin.
 *
 * <h2>Reference-equality on the socket</h2>
 *
 * <p>{@link #onSocketClosed} uses {@code current.socket() == closedWs} to
 * decide whether the close belongs to the current session or to a stale
 * predecessor. Spring's WebSocketSession instances are unique per accepted
 * connection so reference identity is the cheapest correct check.
 *
 * <h2>Single-participant mode</h2>
 *
 * <p>The implementation deliberately supports {@code sessions.size() == 1}
 * as a fully-functional state: the routing matrix is empty, but the VAD
 * and Smart-Turn subscriptions are live and fan out the lone participant's
 * own events back to their own socket. See {@link #rewireMesh} for the
 * matrix-building logic.
 */
@Service
public class ConferenceService {

    private static final Logger log = LoggerFactory.getLogger(ConferenceService.class);
    private static final int MAX_PARTICIPANTS = 2;

    private final Conference conference;
    private final TestbedProperties props;
    private final RosterBroadcaster roster;
    private final EventBroadcaster events;
    private final ObjectMapper mapper;

    private final Map<String, ParticipantSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong epochSeq = new AtomicLong();
    private final ReentrantLock lock = new ReentrantLock();

    public ConferenceService(Conference conference, TestbedProperties props,
                              RosterBroadcaster roster, EventBroadcaster events,
                              ObjectMapper mapper) {
        this.conference = conference;
        this.props = props;
        this.roster = roster;
        this.events = events;
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------------
    // WebSocket lifecycle entry points
    // ---------------------------------------------------------------------

    /**
     * Called from the WebSocket handler when a new browser connects.
     * The browser is "in lobby" — registered for roster purposes but
     * not yet a synauson participant. The synauson participant is only
     * created when the SDP offer arrives.
     */
    public void onSocketConnected(String pid, WebSocketSession ws) {
        roster.recordJoin(pid);
        // Hand the new socket the current roster so its Lobby's Presence card
        // can render immediately. NOTE: we read the sessions map without the
        // lock — this is a single read of a ConcurrentHashMap, no consistency
        // hazard for a snapshot.
        roster.sendTo(ws, roster.snapshot(sessions));
    }

    /**
     * Called when the browser sends its SDP offer.
     *
     * <p>This is the "do work" entry point: it acquires the service lock,
     * evicts any prior session with the same pid, adds a fresh JSyn
     * participant, subscribes for VAD/Turn/ICE, rewires the routing
     * matrix, and broadcasts the updated roster.
     */
    public void onSdpOffer(String pid, WebSocketSession ws, String sdp) {
        WebRtcParticipantHandle handle = null;
        lock.lock();
        try {
            // Conference-full guard: third browser is rejected without
            // touching the existing two.
            if (sessions.size() >= MAX_PARTICIPANTS && !sessions.containsKey(pid)) {
                sendError(ws, "CONFERENCE_FULL",
                    "Testbed accepts at most " + MAX_PARTICIPANTS + " participants.");
                try { ws.close(CloseStatus.SERVICE_RESTARTED); }
                catch (Exception ignored) {}
                return;
            }

            // Recreate: if this pid is already a participant, evict it.
            ParticipantSession prior = sessions.remove(pid);
            if (prior != null) {
                sendError(prior.socket(), "REPLACED",
                    "Another browser joined as " + pid);
                prior.closeNativeState(() -> conference.removeParticipant(pid));
                try { prior.socket().close(CloseStatus.NORMAL); }
                catch (Exception ignored) {}
            }

            long epoch = epochSeq.incrementAndGet();
            var spec = WebRtcParticipantSpec.builder()
                .participantId(pid)
                .sdpOffer(sdp)
                .stunServer(props.stunServer())
                .jitterBufferMs(200)
                .vad(buildVadConfig())
                .smartTurn(buildSmartTurnConfig())
                .build();

            // addWebRtcParticipant blocks until webrtcbin processes the offer
            // and produces an SDP answer; the handle carries it. This call
            // can throw InvalidArgumentException if the SDP is malformed.
            try {
                handle = conference.addWebRtcParticipant(spec);
            } catch (com.synauson.jsyn.exception.InvalidArgumentException ex) {
                log.warn("Invalid SDP from pid={}: {}", pid, ex.getMessage());
                sendError(ws, "INVALID_SDP", ex.getMessage());
                return;
            }

            // Subscribe BEFORE storing the session — the subscription lambdas
            // capture {@code epoch} as a closure, so any late callback will
            // correctly drop itself if the pid is later replaced.
            Subscription vadSub  = conference.streamVadEvents(pid,
                ev -> events.dispatchVad(pid, epoch, sessions, ev));
            Subscription turnSub = conference.streamSmartTurnEvents(pid,
                ev -> events.dispatchTurn(pid, epoch, sessions, ev));
            Subscription iceSub  = conference.streamWebRtcIceCandidates(pid,
                ev -> events.dispatchIce(pid, epoch, sessions, ev));

            var session = new ParticipantSession(pid, epoch, ws, handle,
                vadSub, turnSub, iceSub);
            sessions.put(pid, session);

            rewireMesh();
        } finally {
            lock.unlock();
        }

        // I/O happens OUTSIDE the lock.
        sendMessage(ws, new ServerMessage.SdpAnswer(handle.sdpAnswer()));
        roster.broadcast(sessions, roster.snapshot(sessions));
    }

    /**
     * Called when the browser sends a trickle ICE candidate. Forwards directly
     * to the JSyn handle.
     *
     * <p>No lock — addIceCandidate on JSyn's handle is independently
     * thread-safe and looking up the session in a ConcurrentHashMap is
     * lock-free.
     */
    public void onIceCandidate(String pid, String candidate, int sdpMLineIndex) {
        ParticipantSession session = sessions.get(pid);
        if (session == null) return;
        try {
            session.handle().addIceCandidate(candidate, sdpMLineIndex);
        } catch (Exception ex) {
            log.warn("addIceCandidate({}) failed: {}", pid, ex.getMessage());
        }
    }

    /** Browser sent a "leave" message; tear down. */
    public void onLeave(String pid, WebSocketSession ws) {
        onSocketClosed(pid, ws);
    }

    /**
     * Called when the WebSocket closes (clean or dirty).
     *
     * <p>Reference-equality gate: only act if the closing socket is still
     * the current owner of this pid. A delayed RST from a previous session
     * must not kill its replacement.
     */
    public void onSocketClosed(String pid, WebSocketSession closedWs) {
        lock.lock();
        try {
            ParticipantSession current = sessions.get(pid);
            if (current == null || current.socket() != closedWs) return;
            sessions.remove(pid);
            current.closeNativeState(() -> conference.removeParticipant(pid));
            rewireMesh();
        } finally {
            lock.unlock();
        }
        roster.forget(pid);
        roster.broadcast(sessions, roster.snapshot(sessions));
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private VadConfig buildVadConfig() {
        return new VadConfig(props.vadThreshold(),
                              props.vadMinSilenceMs(),
                              props.vadMinSpeechMs());
    }

    private SmartTurnConfig buildSmartTurnConfig() {
        return new SmartTurnConfig(props.smartTurnBufferedSamples(),
                                    props.smartTurnConfidenceThreshold());
    }

    /**
     * Re-declare the full routing matrix to JSyn.
     *
     * <p>{@link Conference#updatePartyAudioConnections} is declarative — pass
     * the desired matrix, JSyn diffs against the current connections and
     * adds/removes only what changed. With 0 participants the matrix is empty;
     * with 1 it's still empty (no self-loopback); with 2 it has both
     * directions. This is what makes single-participant mode "just work" —
     * the detector subscriptions are independent of routing.
     */
    private void rewireMesh() {
        List<ConnectionEntry> entries = new ArrayList<>();
        var pids = sessions.keySet();
        for (String src : pids) {
            for (String dst : pids) {
                if (!src.equals(dst)) entries.add(ConnectionEntry.connect(src, dst));
            }
        }
        try {
            conference.updatePartyAudioConnections(new ConnectionMatrix(entries));
        } catch (Exception ex) {
            log.warn("rewireMesh failed: {}", ex.getMessage());
        }
    }

    private void sendError(WebSocketSession ws, String code, String message) {
        sendMessage(ws, new ServerMessage.Error(code, message));
    }

    private void sendMessage(WebSocketSession ws, ServerMessage msg) {
        if (!ws.isOpen()) return;
        try {
            ws.sendMessage(new TextMessage(mapper.writeValueAsString(msg)));
        } catch (Exception ex) {
            log.debug("send to {} failed (ignored): {}", ws.getId(), ex.getMessage());
        }
    }
}
