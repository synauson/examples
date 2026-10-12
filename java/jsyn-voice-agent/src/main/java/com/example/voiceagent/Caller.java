package com.example.voiceagent;

/** Test scaffolding, not part of an agent: the person on the phone. */
interface Caller extends AutoCloseable {
    /** Says a rehearsed line into the call and returns once it has been said. */
    void say(String line) throws InterruptedException;

    /** What the caller heard: the agent's speech, 16-bit mono PCM at {@link #sampleRate()}. */
    byte[] heard();

    int sampleRate();

    @Override
    void close() throws InterruptedException;
}
