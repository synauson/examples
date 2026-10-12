package com.example.voiceagent;

import com.synauson.jsyn.CancelledUtterance;
import com.synauson.jsyn.event.AgentEvent;
import com.synauson.jsyn.exception.FailedPreconditionException;
import com.synauson.jsyn.exception.NotFoundException;
import com.synauson.jsyn.participant.Conference;
import com.synauson.jsyn.spec.Speak;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;

/**
 * The agent: reads the caller's agent stream, answers each turn the caller finishes, and
 * stops talking when the caller talks over it. All of it runs against one participant,
 * the caller's, which carries VAD, turn detection, STT and a speaker.
 *
 * <p>Events are handled one at a time on the agent's own thread. Replies are written on
 * other threads, the way an LLM streams them, so the agent still sees the caller while
 * it talks.
 */
final class VoiceAgent implements AutoCloseable {
    /** A line of the conversation: what the caller said, or what they heard the agent say. */
    record Line(String speaker, String text) {}

    /** One of the agent's replies: an utterance on the caller's speaker. */
    private static final class Reply {
        final String utteranceId;
        final long turnId;          // the caller's turn it answers
        final String callerSaid;    // that turn, after any the caller said since they last heard an answer
        volatile boolean confirmed; // the turn's EndOfTurn said it stands (an eager reply waits for that)
        final StringBuffer text = new StringBuffer(); // what has been sent to the speaker so far
        boolean started;            // its audio started playing
        boolean done;               // it played to its end
        volatile boolean withdrawn; // stop writing it
        Future<?> writer;

        Reply(String utteranceId, long turnId, String callerSaid, boolean confirmed) {
            this.utteranceId = utteranceId;
            this.turnId = turnId;
            this.callerSaid = callerSaid;
            this.confirmed = confirmed;
        }
    }

    private final Conference conf;
    private final String callerId;
    private final BiConsumer<AgentEvent, AgentStream> onEvent;
    private final ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Line> history = Collections.synchronizedList(new ArrayList<>());
    private final List<AgentEvent> events = new ArrayList<>();
    private final Map<String, Reply> replies = new LinkedHashMap<>();
    private final Set<String> unfinished = ConcurrentHashMap.newKeySet(); // not done, interrupted or failed
    private final Map<Long, String> turns = new TreeMap<>(); // what the caller said, by turn
    private Reply newest;           // the newest reply, while it is unfinished
    private Reply lastDone;         // the reply that last played to its end
    private long heardThrough;      // the newest turn whose answer the caller started to hear
    private volatile String goodbye;

    VoiceAgent(Conference conf, String callerId, BiConsumer<AgentEvent, AgentStream> onEvent) {
        this.conf = conf;
        this.callerId = callerId;
        this.onEvent = onEvent;
    }

    /** Runs the conversation until the agent has said goodbye or the stream ends. */
    void run(AgentStream stream) throws InterruptedException {
        while (true) {
            AgentEvent event = stream.next(Duration.ofSeconds(60));
            if (event == null) {
                throw new IllegalStateException("the call went quiet for a minute");
            }
            events.add(event);
            onEvent.accept(event, stream);
            if (handle(event)) {
                return;
            }
        }
    }

    List<Line> history() {
        return List.copyOf(history);
    }

    /** Every event the agent handled, in order. */
    List<AgentEvent> events() {
        return List.copyOf(events);
    }

    /** Each reply's utterance id and the text sent for it. */
    Map<String, String> replies() {
        Map<String, String> sent = new LinkedHashMap<>();
        replies.forEach((id, r) -> sent.put(id, r.text.toString()));
        return sent;
    }

    /** Acts on one event; true once the call is over. */
    private boolean handle(AgentEvent event) throws InterruptedException {
        if (event instanceof AgentEvent.EagerEndOfTurn eager) {
            // The caller has probably finished: start answering now, before turn
            // detection confirms it, so the reply is ready sooner.
            if (!eager.text.isBlank()) {
                dropUnheard(eager.turnId);
                startReply(eager.turnId, eager.text, false);
            }
        } else if (event instanceof AgentEvent.TurnResumed resumed) {
            // The caller went on talking: the early reply no longer fits.
            Reply reply = newest;
            if (reply != null && reply.turnId == resumed.turnId && !reply.confirmed) {
                withdraw(reply);
            }
        } else if (event instanceof AgentEvent.EndOfTurn end) {
            if (end.text.isBlank()) {
                return false;
            }
            System.out.println("caller: " + end.text);
            history.add(new Line("caller", end.text));
            // An early reply may still be playing, or may have played to its end already
            // when turn detection takes its time (the end-of-turn timeout).
            Reply reply = newest != null ? newest : lastDone;
            if (reply != null && reply.turnId == end.turnId
                    && reply.callerSaid.equals(unanswered(end.turnId, end.text))) {
                reply.confirmed = true; // the early reply was the right one
                if (reply.done && reply.utteranceId.equals(goodbye)) {
                    return true; // it was goodbye, and the caller heard it all
                }
            } else {
                dropUnheard(end.turnId);
                startReply(end.turnId, end.text, true);
            }
            turns.put(end.turnId, end.text);
        } else if (event instanceof AgentEvent.SpeechStarted && !unfinished.isEmpty()) {
            // Barge-in: the caller talks over the agent. Stop, and keep what they heard.
            bargeIn();
        } else if (event instanceof AgentEvent.UtteranceStarted started) {
            Reply reply = replies.get(started.utteranceId);
            if (reply != null) {
                reply.started = true;
                heardThrough = Math.max(heardThrough, reply.turnId);
            }
        } else if (event instanceof AgentEvent.UtteranceDone done) {
            Reply reply = finished(done.utteranceId);
            if (reply != null) {
                reply.done = true;
                lastDone = reply;
                history.add(new Line("agent", reply.text.toString().strip()));
                // Hang up after saying goodbye, once the caller's turn it answered ended.
                if (reply.utteranceId.equals(goodbye) && reply.confirmed) {
                    return true;
                }
            }
        } else if (event instanceof AgentEvent.UtteranceInterrupted interrupted) {
            finished(interrupted.utteranceId);
            if (interrupted.utteranceId.equals(goodbye)) {
                goodbye = null; // cut off: the call goes on
            }
            if (!interrupted.heardText.isBlank()) {
                history.add(new Line("agent", interrupted.heardText.strip()));
            }
        } else if (event instanceof AgentEvent.UtteranceFailed failed) {
            throw new IllegalStateException("speech failed: " + failed.code + " " + failed.message);
        } else if (event instanceof AgentEvent.Error error) {
            // A problem the stream recovers from, such as STT_LAGGING on a busy machine.
            System.out.println("  (engine: " + error.reason + ": " + error.message + ")");
        } else if (event instanceof AgentEvent.StreamEnded) {
            return true;
        }
        return false;
    }

    /** What a reply to this turn answers: the turn, after any the caller said since they last heard an answer. */
    private String unanswered(long turnId, String text) {
        List<String> said = new ArrayList<>();
        turns.forEach((id, t) -> {
            if (id > heardThrough && id < turnId) said.add(t);
        });
        said.add(text);
        return String.join(" ", said);
    }

    private void startReply(long turnId, String text, boolean confirmed) {
        Reply reply = new Reply("reply-" + (replies.size() + 1), turnId, unanswered(turnId, text), confirmed);
        replies.put(reply.utteranceId, reply);
        unfinished.add(reply.utteranceId);
        newest = reply;
        reply.writer = writers.submit(() -> speak(reply));
    }

    /**
     * Streams the reply into the speaker as it is written, the way an LLM streams tokens:
     * speech starts after the first words, and the rest follows.
     */
    private void speak(Reply reply) {
        try {
            Iterator<String> tokens = Replies.answer(reply.callerSaid);
            while (tokens.hasNext()) {
                String token = tokens.next();
                if (reply.withdrawn) {
                    return;
                }
                conf.speak(callerId, Speak.builder().utteranceId(reply.utteranceId).text(token).build());
                reply.text.append(token);
            }
            if (reply.withdrawn) {
                return;
            }
            conf.speak(callerId, Speak.builder().utteranceId(reply.utteranceId).release(Speak.Release.END).build());
        } catch (FailedPreconditionException ended) {
            // UTTERANCE_ENDED: the caller talked over it while it was being written.
            reply.withdrawn = true;
            return;
        }
        if (Replies.isGoodbye(reply.text.toString())) {
            goodbye = reply.utteranceId;
        }
        System.out.println("agent:  " + reply.text.toString().strip());
    }

    /**
     * A turn ended: withdraws the newest reply if the caller hasn't heard any of it (they
     * said more before it started), or if it was an early answer to this turn.
     */
    private void dropUnheard(long turnId) throws InterruptedException {
        Reply reply = newest;
        if (reply != null && (!reply.started || (reply.turnId == turnId && !reply.confirmed))) {
            withdraw(reply);
        }
    }

    /** Stops writing a reply and stops it on the speaker, playing or not. */
    private void withdraw(Reply reply) throws InterruptedException {
        stopWriting(reply);
        try {
            conf.cancelUtterance(callerId, reply.utteranceId);
        } catch (NotFoundException neverSpoken) {
            // UNKNOWN_UTTERANCE: none of it reached the speaker.
        }
        finished(reply.utteranceId);
    }

    private void bargeIn() throws InterruptedException {
        for (String id : List.copyOf(unfinished)) {
            stopWriting(replies.get(id));
        }
        for (CancelledUtterance c : conf.cancelUtterances(callerId)) {
            if (!c.heardText.isEmpty()) {
                System.out.println("  (caller talked over the agent; they heard \"" + c.heardText + "\")");
            }
        }
        for (String id : List.copyOf(unfinished)) {
            finished(id);
        }
    }

    private Reply finished(String utteranceId) {
        Reply reply = replies.get(utteranceId);
        unfinished.remove(utteranceId);
        if (reply != null && reply == newest) {
            newest = null;
        }
        return reply;
    }

    private static void stopWriting(Reply reply) throws InterruptedException {
        reply.withdrawn = true;
        try {
            reply.writer.get();
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("writing a reply failed", e.getCause());
        }
    }

    @Override
    public void close() {
        replies.values().forEach(r -> r.withdrawn = true);
        writers.close();
    }
}
