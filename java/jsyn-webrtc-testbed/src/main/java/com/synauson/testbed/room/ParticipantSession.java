package com.synauson.testbed.room;

import com.synauson.jsyn.Subscription;
import com.synauson.jsyn.participant.WebRtcParticipantHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.WebSocketSession;

/**
 * Full per-participant state inside the testbed conference.
 *
 * <p>Bundles resources so the orchestrator ({@link ConferenceService}) can
 * swap or destroy them atomically. {@link #epoch()} is a monotonically
 * increasing token assigned at creation time; downstream callback paths use
 * it to detect "stale" events that should be dropped because the session
 * has been superseded by a rejoin.
 */
public record ParticipantSession(
    String participantId, long epoch, WebSocketSession socket,
    WebRtcParticipantHandle handle,
    Subscription vadSubscription, Subscription turnSubscription, Subscription iceSubscription
) {
    private static final Logger log = LoggerFactory.getLogger(ParticipantSession.class);

    /**
     * Tear down per-participant native state, EXCLUDING the WebSocket
     * (caller decides whether and when to close the socket — some paths
     * keep it open to deliver a REPLACED error frame first).
     *
     * <h3>Order matters</h3>
     *
     * <p>Subscriptions close first. Once a subscription is closed, JSyn stops
     * delivering events for that stream — preventing a stale callback from
     * firing after the participant is removed. Doing it the other way leaves
     * a window where an in-flight VAD event could try to read a freed handle.
     *
     * @param conferenceCloser invokes {@code conference.removeParticipant(pid)};
     *                         passed in as a Runnable so this record stays
     *                         decoupled from Spring/JSyn imports.
     */
    public void closeNativeState(Runnable conferenceCloser) {
        closeQuietly(vadSubscription, "vad");
        closeQuietly(turnSubscription, "smart-turn");
        closeQuietly(iceSubscription, "ice");
        try {
            conferenceCloser.run();
        } catch (Exception ex) {
            log.warn("removeParticipant({}) threw during teardown", participantId, ex);
        }
    }

    private void closeQuietly(Subscription sub, String label) {
        if (sub == null) return;
        try { sub.close(); }
        catch (Exception ex) {
            log.debug("closing {} subscription for {} threw (ignored): {}",
                    label, participantId, ex.getMessage());
        }
    }
}
