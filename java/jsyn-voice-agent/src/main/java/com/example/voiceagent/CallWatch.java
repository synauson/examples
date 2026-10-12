package com.example.voiceagent;

import com.synauson.jsyn.Subscription;
import com.synauson.jsyn.event.AgentEvent;
import com.synauson.jsyn.participant.Conference;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Test scaffolding, not part of an agent: a second subscription to the caller's agent
 * stream, from its first event to its last, so the simulated caller knows when the agent
 * has decided a turn and when it talks. Its events are also the call's whole record.
 */
final class CallWatch implements AutoCloseable {
    private final List<AgentEvent> events = new ArrayList<>();
    private final CountDownLatch ended = new CountDownLatch(1);
    private final Subscription subscription;

    CallWatch(Conference conf, String participantId) {
        subscription = conf.streamAgentEvents(participantId, e -> {
            if (e.isStored()) {
                synchronized (events) {
                    events.add(e);
                    events.notifyAll();
                }
            }
            if (e instanceof AgentEvent.StreamEnded) {
                ended.countDown();
            }
        });
    }

    /** Waits, up to 60 s, until the call's events so far satisfy {@code done}. */
    void awaitUntil(String what, Predicate<List<AgentEvent>> done) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        synchronized (events) {
            while (!done.test(events)) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) {
                    throw new IllegalStateException("no " + what + " within 60 s");
                }
                events.wait(left);
            }
        }
    }

    /**
     * Whether the caller's turns are decided: at least {@code turns} have ended, and every
     * turn that started has its EndOfTurn. Until then, more speech from the caller may
     * resume the open turn rather than start a new one.
     */
    static boolean turnsDecided(List<AgentEvent> events, int turns) {
        long started = 0;
        long endedTurn = 0;
        int endings = 0;
        for (AgentEvent e : events) {
            if (e instanceof AgentEvent.TurnStarted s) {
                started = s.turnId;
            } else if (e instanceof AgentEvent.EndOfTurn end) {
                endedTurn = end.turnId;
                endings++;
            }
        }
        return endings >= turns && endedTurn == started;
    }

    /** Whether an utterance of the agent is playing and the caller has heard some of it. */
    static boolean agentTalking(List<AgentEvent> events) {
        Set<String> heard = new HashSet<>();
        for (AgentEvent e : events) {
            if (e instanceof AgentEvent.WordsPlayed w && !w.words.isEmpty()) {
                heard.add(w.utteranceId);
            } else if (e instanceof AgentEvent.UtteranceDone d) {
                heard.remove(d.utteranceId);
            } else if (e instanceof AgentEvent.UtteranceInterrupted i) {
                heard.remove(i.utteranceId);
            }
        }
        return !heard.isEmpty();
    }

    /**
     * Whether an utterance of the agent that started after the caller's latest turn
     * started has played to its end: an answer to that turn, not an earlier one.
     */
    static boolean answeredSinceLastTurn(List<AgentEvent> events) {
        int lastTurn = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof AgentEvent.TurnStarted) lastTurn = i;
        }
        Set<String> started = new HashSet<>();
        for (AgentEvent e : events.subList(lastTurn + 1, events.size())) {
            if (e instanceof AgentEvent.UtteranceStarted s) {
                started.add(s.utteranceId);
            } else if (e instanceof AgentEvent.UtteranceDone d && started.contains(d.utteranceId)) {
                return true;
            }
        }
        return false;
    }

    /** Every stored event so far; after the participant is removed, the whole call. */
    List<AgentEvent> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    /** Waits for StreamEnded, which follows the participant's removal. */
    void awaitEnd() throws InterruptedException {
        ended.await(10, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        subscription.close();
    }
}
