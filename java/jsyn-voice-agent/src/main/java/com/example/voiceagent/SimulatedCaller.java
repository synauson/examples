package com.example.voiceagent;

import com.synauson.jsyn.participant.NativeParticipant;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Test scaffolding, not part of an agent: a person on the phone, so the demo runs without
 * a microphone or a SIP trunk. Their rehearsed lines are written, 20 ms at a time, into
 * the caller participant's ring, as a phone call's audio would arrive, and what the
 * participant plays back (the agent's speech) is kept, so it can be saved.
 *
 * <p>In a real application the caller participant is a SIP or WebRTC participant (see
 * {@link SimulatedPhone}), or a native participant your own audio I/O feeds.
 */
final class SimulatedCaller implements Caller {
    private static final int FRAME = Rehearsal.FORMAT.bytesPer20ms();

    private final NativeParticipant caller;
    private final Map<String, byte[]> lines;
    private final BlockingQueue<byte[]> outgoing = new LinkedBlockingQueue<>();
    private final ByteArrayOutputStream heard = new ByteArrayOutputStream();
    private final Thread pump;
    private volatile boolean running = true;

    SimulatedCaller(NativeParticipant caller, Map<String, byte[]> lines) {
        this.caller = caller;
        this.lines = lines;
        this.pump = new Thread(this::pump, "simulated-caller");
        this.pump.setDaemon(true);
        this.pump.start();
    }

    @Override
    public void say(String text) throws InterruptedException {
        byte[] audio = lines.get(text);
        if (audio == null) {
            throw new IllegalArgumentException("not rehearsed: " + text);
        }
        System.out.println("  (caller starts: \"" + text + "\")");
        for (int at = 0; at < audio.length; at += FRAME) {
            byte[] frame = new byte[FRAME];
            System.arraycopy(audio, at, frame, 0, Math.min(FRAME, audio.length - at));
            outgoing.add(frame);
        }
        while (!outgoing.isEmpty()) {
            Thread.sleep(20);
        }
    }

    @Override
    public byte[] heard() {
        synchronized (heard) {
            return heard.toByteArray();
        }
    }

    @Override
    public int sampleRate() {
        return 16_000;
    }

    /** Writes the caller's voice (or silence) and keeps what the caller hears, in real time. */
    private void pump() {
        byte[] silence = new byte[FRAME];
        byte[] in = new byte[FRAME * 8];
        long start = System.nanoTime();
        for (long f = 0; running; f++) {
            byte[] frame = outgoing.poll();
            caller.write(frame == null ? silence : frame, 0, FRAME);
            int n;
            while ((n = caller.read(in, 0, in.length)) > 0) {
                synchronized (heard) {
                    heard.write(in, 0, n);
                }
            }
            long wait = start + (f + 1) * 20_000_000L - System.nanoTime();
            if (wait > 0) {
                try {
                    Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    @Override
    public void close() throws InterruptedException {
        running = false;
        pump.join(2_000);
    }
}
