package com.example.voiceagent;

import com.synauson.jsyn.AgentStreamOptions;
import com.synauson.jsyn.EventStreamObserver;
import com.synauson.jsyn.Subscription;
import com.synauson.jsyn.event.AgentEvent;
import com.synauson.jsyn.exception.AgentStreamException;
import com.synauson.jsyn.participant.Conference;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Reads a participant's agent event stream into a queue, each stored event exactly once
 * and in order, and keeps reading it when the subscription is lost.
 *
 * <p>The observer runs on an engine thread, and the engine never waits for it: it only
 * queues the event. An observer that falls 256 events behind is dropped
 * ({@code AGENT_SUBSCRIBER_LAGGED}); this one then subscribes again after the last event
 * it queued ({@link AgentStreamOptions#resumeAfter}), so nothing is lost or repeated. When
 * the engine no longer keeps the events after it, it starts afresh and skips the replayed
 * events it already queued.
 */
final class AgentStream implements EventStreamObserver<AgentEvent>, AutoCloseable {
    private final Conference conf;
    private final String participantId;
    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>(); // events, or a failure
    private final Object cursorLock = new Object();
    private AgentEvent lastStored; // the resume cursor, guarded by cursorLock
    private final Object subscriptionLock = new Object();
    private Subscription subscription; // guarded by subscriptionLock
    private volatile int reconnects;

    AgentStream(Conference conf, String participantId) {
        this.conf = conf;
        this.participantId = participantId;
    }

    void start() {
        synchronized (subscriptionLock) {
            subscription = conf.streamAgentEvents(participantId, AgentStreamOptions.defaults(), this);
        }
    }

    /**
     * The next event, for the agent's own thread; null if none came within {@code wait}.
     * Throws if the stream failed for a reason resuming can't mend.
     */
    AgentEvent next(Duration wait) throws InterruptedException {
        Object next = queue.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
        if (next instanceof Throwable t) {
            throw new IllegalStateException("the agent stream failed: " + t.getMessage(), t);
        }
        return (AgentEvent) next;
    }

    /** Subscribes again after the last stored event queued, or afresh if that is gone. */
    void resume() {
        synchronized (subscriptionLock) {
            closeSubscription();
            AgentEvent cursor;
            synchronized (cursorLock) {
                cursor = lastStored;
            }
            reconnects++;
            System.out.println("  (agent stream resumed after seq " + (cursor == null ? 0 : cursor.seq) + ")");
            try {
                subscription = conf.streamAgentEvents(participantId, cursor == null
                        ? AgentStreamOptions.defaults()
                        : AgentStreamOptions.resumeAfter(cursor), this);
            } catch (AgentStreamException gone) {
                // AGENT_REPLAY_EXPIRED or AGENT_STREAM_MISMATCH: what followed the cursor is gone.
                subscription = conf.streamAgentEvents(participantId, AgentStreamOptions.defaults(), this);
            }
        }
    }

    /** How many times the stream was resumed. */
    int reconnects() {
        return reconnects;
    }

    @Override
    public void onNext(AgentEvent event) {
        if (event.isStored()) {
            synchronized (cursorLock) {
                if (lastStored != null && event.streamId == lastStored.streamId
                        && event.seq <= lastStored.seq) {
                    return; // replayed after a fresh start; queued already
                }
                lastStored = event;
            }
        }
        queue.add(event);
    }

    @Override
    public void onError(Throwable t) {
        if (t instanceof AgentStreamException e
                && e.reason().equals(AgentStreamException.AGENT_SUBSCRIBER_LAGGED)) {
            // Subscribe again from another thread, never from inside the observer.
            Thread.ofVirtual().start(this::resume);
        } else {
            queue.add(t);
        }
    }

    @Override
    public void onCompleted() {
        // StreamEnded came first: the participant or the conference is gone.
    }

    @Override
    public void close() {
        synchronized (subscriptionLock) {
            closeSubscription();
        }
    }

    private void closeSubscription() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
    }
}
