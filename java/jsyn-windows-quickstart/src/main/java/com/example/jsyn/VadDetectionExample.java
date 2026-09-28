package com.example.jsyn;

import com.synauson.jsyn.Capabilities;
import com.synauson.jsyn.JSyn;
import com.synauson.jsyn.JSynConfig;
import com.synauson.jsyn.NativeAudioFormat;
import com.synauson.jsyn.Subscription;
import com.synauson.jsyn.event.VadEvent;
import com.synauson.jsyn.participant.Conference;
import com.synauson.jsyn.participant.NativeParticipant;
import com.synauson.jsyn.spec.NativeParticipantSpec;
import com.synauson.jsyn.spec.VadConfig;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Example demonstrating Voice Activity Detection (VAD) using JSyn.
 *
 * <p>This example:
 * <ol>
 *   <li>Waits for the runtime to download the Silero VAD model your license includes
 *   <li>Creates a native participant with VAD enabled
 *   <li>Streams a speech recording into it in real time, followed by silence
 *   <li>Waits for VAD to report the speech starting and ending
 * </ol>
 *
 * <p><b>Prerequisites:</b>
 * <ul>
 *   <li>GStreamer 1.26.7 installed
 *   <li>{@code SYNAUSON_LICENSE_KEY} set to a license that includes {@code FEATURE_VAD}
 *   <li>A WAV file of speech: 16 kHz, mono, 16-bit PCM. Silero VAD is trained on
 *       speech, so a synthetic tone won't trigger it.
 * </ul>
 *
 * <p>Run with {@code .\gradlew.bat runVadExample --args="C:\path\to\speech.wav"}.
 * Exits non-zero if VAD doesn't detect the speech.
 */
public class VadDetectionExample {

    private static final int SAMPLE_RATE = 16_000;
    /** 20 ms of 16-bit mono audio at 16 kHz. */
    private static final int FRAME_BYTES = SAMPLE_RATE / 50 * 2;

    public static void main(String[] args) throws Exception {
        System.out.println("=== JSyn VAD Detection Example ===\n");
        if (args.length != 1) {
            System.err.println("usage: VadDetectionExample <speech.wav (16 kHz, mono, 16-bit PCM)>");
            System.exit(2);
        }
        byte[] speech = readPcm16kMono(new File(args[0]));
        System.out.printf("Loaded %s (%.1f s of audio)%n%n", args[0], speech.length / 2.0 / SAMPLE_RATE);

        // The license key is read from SYNAUSON_LICENSE_KEY; models go to the default
        // per-user model store.
        JSynConfig config = JSynConfig.builder()
                .rtpPortMin(40200)
                .rtpPortMax(40399)
                .build();

        System.out.println("Initializing JSyn...");
        try (JSyn jsyn = new JSyn(config)) {
            System.out.println("JSyn initialized: " + jsyn.capabilities().license.description + "\n");

            // The runtime downloads the licensed models in the background at startup.
            // Adding a VAD participant before the model is on disk throws
            // FailedPreconditionException, so wait for it first.
            System.out.println("Waiting for the Silero VAD model...");
            awaitModel(jsyn, "silero-vad", Duration.ofMinutes(2));
            System.out.println("Model ready\n");

            String conferenceId = "vad-example-conference";
            try (Conference conference = jsyn.startConference(conferenceId)) {
                System.out.println("Conference started: " + conferenceId + "\n");

                String participantId = "native-vad-test";
                AtomicInteger speechStarts = new AtomicInteger();
                CountDownLatch speechEnded = new CountDownLatch(1);

                try (Subscription vadSub = conference.streamVadEvents(participantId, event -> {
                    if (event instanceof VadEvent.SpeechStart) {
                        System.out.println("✓ VAD: speech START (#" + speechStarts.incrementAndGet() + ")");
                    } else if (event instanceof VadEvent.SpeechEnd) {
                        VadEvent.SpeechEnd end = (VadEvent.SpeechEnd) event;
                        System.out.println("✓ VAD: speech END (" + end.durationMs + " ms)");
                        speechEnded.countDown();
                    }
                })) {

                    System.out.println("Creating native participant with VAD (PCM 16 kHz mono)\n");
                    try (NativeParticipant participant = conference.addNativeParticipant(
                            participantId,
                            NativeParticipantSpec.builder()
                                    .format(NativeAudioFormat.PCM_S16LE16K_MONO)
                                    .vad(new VadConfig(0.5f, 300, 250))
                                    .build())) {

                        System.out.println("Streaming the recording, then 1.5 s of silence...");
                        stream(participant, speech);
                        stream(participant, new byte[SAMPLE_RATE * 3]);

                        boolean ended = speechEnded.await(10, TimeUnit.SECONDS);
                        System.out.println("\n=== VAD Detection Summary ===");
                        System.out.println("Speech START events: " + speechStarts.get());
                        if (speechStarts.get() == 0 || !ended) {
                            System.err.println("✗ VAD did not detect the speech");
                            System.exit(1);
                        }
                        System.out.println("✓ VAD detected the speech");
                    }
                }
            }
            System.out.println("JSyn shutdown complete");
        }
        System.out.println("\n=== Example completed successfully ===");
    }

    /**
     * Write PCM into the participant in 20 ms frames at real-time pace.
     *
     * <p>{@link NativeParticipant#write} never blocks: it returns how many bytes fit
     * in the ingress ring, so keep offering the rest of a frame until it's taken.
     */
    private static void stream(NativeParticipant participant, byte[] pcm) throws InterruptedException {
        long next = System.nanoTime();
        for (int off = 0; off < pcm.length; off += FRAME_BYTES) {
            int len = Math.min(FRAME_BYTES, pcm.length - off);
            int written = 0;
            while (written < len) {
                written += participant.write(pcm, off + written, len - written);
                if (written < len) Thread.sleep(2);
            }
            next += TimeUnit.MILLISECONDS.toNanos(20);
            long sleep = next - System.nanoTime();
            if (sleep > 0) TimeUnit.NANOSECONDS.sleep(sleep);
        }
    }

    /** Poll {@link JSyn#capabilities()} until the model is ready. */
    private static void awaitModel(JSyn jsyn, String modelId, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            for (Capabilities.ModelInfo m : jsyn.capabilities().models) {
                if (!m.id.equals(modelId)) continue;
                if ("ready".equals(m.state)) return;
                if ("not-entitled".equals(m.state)) {
                    throw new IllegalStateException("The license doesn't include " + modelId
                            + " (" + m.detail + ")");
                }
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException(modelId + " didn't download within " + timeout);
            }
            Thread.sleep(500);
        }
    }

    /** Read a WAV file that is already 16 kHz, mono, 16-bit signed little-endian PCM. */
    private static byte[] readPcm16kMono(File wav) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(wav)) {
            AudioFormat f = in.getFormat();
            boolean ok = f.getEncoding() == AudioFormat.Encoding.PCM_SIGNED
                    && f.getSampleRate() == SAMPLE_RATE && f.getChannels() == 1
                    && f.getSampleSizeInBits() == 16 && !f.isBigEndian();
            if (!ok) {
                throw new IllegalArgumentException("Expected 16 kHz mono 16-bit PCM, got " + f);
            }
            return in.readAllBytes();
        }
    }
}
