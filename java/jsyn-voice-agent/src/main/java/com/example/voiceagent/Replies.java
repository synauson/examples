package com.example.voiceagent;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;

/**
 * A stand-in for your LLM: picks a canned reply by keyword and returns it word by word,
 * the way a streaming completion arrives.
 */
final class Replies {
    private Replies() {}

    static final String GOODBYE = "You're welcome. Goodbye!";

    /** How long the stand-in takes for each token. A real LLM takes about as long. */
    private static final long TOKEN_DELAY_MS = 30;

    static Iterator<String> answer(String callerSaid) {
        String said = callerSaid.toLowerCase(Locale.ROOT);
        String reply;
        if (said.contains("bye") || said.contains("thank")) {
            reply = GOODBYE;
        } else if (said.contains("four") || said.contains("people") || said.contains("seven")) {
            reply = "Got it, a table for four. Is there anything else I can do for you?";
        } else if (said.contains("table") || said.contains("book")) {
            reply = "Sure, I can book that for you. For how many people, and at what time "
                    + "would you like to come in tomorrow evening?";
        } else {
            reply = "Sorry, could you say that again?";
        }
        // Words with their spaces, as tokens, each after the time an LLM would take.
        Iterator<String> tokens = Arrays.stream(reply.split("(?<= )")).iterator();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return tokens.hasNext();
            }

            @Override
            public String next() {
                try {
                    Thread.sleep(TOKEN_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return tokens.next();
            }
        };
    }

    static boolean isGoodbye(String reply) {
        return reply.strip().equals(GOODBYE);
    }
}
