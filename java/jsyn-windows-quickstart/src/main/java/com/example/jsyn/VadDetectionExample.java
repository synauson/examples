package com.example.jsyn;

import com.synauson.jsyn.JSyn;
import com.synauson.jsyn.JSynConfig;
import com.synauson.jsyn.NativeAudioFormat;
import com.synauson.jsyn.Subscription;
import com.synauson.jsyn.event.VadEvent;
import com.synauson.jsyn.participant.Conference;
import com.synauson.jsyn.participant.NativeParticipant;
import com.synauson.jsyn.spec.NativeParticipantSpec;
import com.synauson.jsyn.spec.VadConfig;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Example demonstrating Voice Activity Detection (VAD) using JSyn.
 *
 * <p>This example:
 * <ol>
 *   <li>Creates a native participant with VAD enabled
 *   <li>Writes synthetic PCM audio (alternating speech and silence)
 *   <li>Listens for VAD events (speech start/end)
 *   <li>Verifies that VAD correctly detects speech vs silence
 * </ol>
 *
 * <p><b>Prerequisites:</b>
 * <ul>
 *   <li>GStreamer 1.26.7 installed
 *   <li>ONNX models directory with Silero VAD model (silero_vad.onnx)
 * </ol>
 *
 * <p><b>Note:</b> This example requires actual ONNX models. If you don't have
 * them, the example will fail during initialization. For file playback only
 * (no VAD), see {@link FilePlaybackExample}.
 */
public class VadDetectionExample {

    public static void main(String[] args) throws Exception {
        System.out.println("=== JSyn VAD Detection Example ===\n");

        // Check if models directory is available
        Path modelsDir = Path.of("../../models").toAbsolutePath();
        if (!Files.exists(modelsDir.resolve("silero_vad.onnx"))) {
            System.err.println("ERROR: ONNX models not found at: " + modelsDir);
            System.err.println("Expected: silero_vad.onnx");
            System.err.println("\nThis example requires the Silero VAD model.");
            System.err.println("For file playback only, run FilePlaybackExample instead.");
            System.exit(1);
        }

        // Configure JSyn with models directory
        JSynConfig config = JSynConfig.builder()
                .modelsDir(modelsDir.toString())
                .rtpPortMin(40200)
                .rtpPortMax(40399)
                .build();

        System.out.println("Initializing JSyn with VAD models...");
        try (JSyn jsyn = new JSyn(config)) {
            System.out.println("JSyn initialized\n");

            String conferenceId = "vad-example-conference";
            try (Conference conference = jsyn.startConference(conferenceId)) {
                System.out.println("Conference started: " + conferenceId + "\n");

                String participantId = "native-vad-test";

                // Counters for VAD events
                AtomicInteger speechStartCount = new AtomicInteger(0);
                AtomicInteger speechEndCount = new AtomicInteger(0);
                CountDownLatch vadComplete = new CountDownLatch(1);

                // Subscribe to VAD events
                try (Subscription vadSub = conference.streamVadEvents(participantId, event -> {
                    if (event instanceof VadEvent.SpeechStart) {
                        int count = speechStartCount.incrementAndGet();
                        System.out.println("✓ VAD: Speech START (event #" + count + ")");
                    } else if (event instanceof VadEvent.SpeechEnd) {
                        VadEvent.SpeechEnd vadEnd = (VadEvent.SpeechEnd) event;
                        int count = speechEndCount.incrementAndGet();
                        System.out.println("✓ VAD: Speech END (duration: " + vadEnd.durationMs + " ms, event #" + count + ")");

                        // After detecting 2 speech segments, we're done
                        if (count >= 2) {
                            vadComplete.countDown();
                        }
                    }
                })) {

                    // Create native participant with VAD enabled
                    System.out.println("Creating native participant with VAD:");
                    System.out.println("  Participant ID: " + participantId);
                    System.out.println("  Format: PCM 16kHz mono (S16LE)");
                    System.out.println("  VAD threshold: 0.5\n");

                    try (NativeParticipant participant = conference.addNativeParticipant(
                            participantId,
                            NativeParticipantSpec.builder()
                                    .format(NativeAudioFormat.PCM_S16LE16K_MONO)
                                    .vad(new VadConfig(0.5f, 300, 250))
                                    .build())) {

                        System.out.println("Native participant created\n");
                        System.out.println("Generating and writing synthetic audio:");
                        System.out.println("  - 1 second of speech (440 Hz sine wave)");
                        System.out.println("  - 1 second of silence");
                        System.out.println("  - 1 second of speech (880 Hz sine wave)");
                        System.out.println("  - 1 second of silence\n");

                        // Write alternating speech and silence
                        // Speech: 440 Hz sine wave (should trigger VAD)
                        byte[] speech1 = generateSinePcm(440.0, 1.0);
                        participant.write(speech1, 0, speech1.length);
                        System.out.println("Wrote speech segment 1 (440 Hz, 1s)");

                        // Silence: zeros (should end VAD)
                        byte[] silence1 = new byte[16000 * 2]; // 1 second of silence
                        participant.write(silence1, 0, silence1.length);
                        System.out.println("Wrote silence segment 1 (1s)");

                        // Speech: 880 Hz sine wave (should trigger VAD again)
                        byte[] speech2 = generateSinePcm(880.0, 1.0);
                        participant.write(speech2, 0, speech2.length);
                        System.out.println("Wrote speech segment 2 (880 Hz, 1s)");

                        // Silence: zeros
                        byte[] silence2 = new byte[16000 * 2];
                        participant.write(silence2, 0, silence2.length);
                        System.out.println("Wrote silence segment 2 (1s)\n");

                        // Wait for VAD to detect both speech segments
                        System.out.println("Waiting for VAD events...");
                        boolean success = vadComplete.await(10, TimeUnit.SECONDS);

                        if (!success) {
                            System.err.println("\n✗ Timeout waiting for VAD events");
                            System.exit(1);
                        }

                        System.out.println("\n=== VAD Detection Summary ===");
                        System.out.println("Speech START events: " + speechStartCount.get());
                        System.out.println("Speech END events: " + speechEndCount.get());

                        if (speechStartCount.get() >= 2 && speechEndCount.get() >= 2) {
                            System.out.println("\n✓ VAD working correctly - detected multiple speech segments");
                        } else {
                            System.out.println("\n✗ VAD detection incomplete");
                        }
                    }

                    System.out.println("\nTerminating conference...");
                }
            }

            System.out.println("JSyn shutdown complete");
        }

        System.out.println("\n=== Example completed successfully ===");
    }

    /**
     * Generate synthetic PCM buffer of a sine wave at the given frequency
     * for the specified duration, in 16-bit signed little-endian format,
     * at 16 kHz mono.
     */
    private static byte[] generateSinePcm(double freqHz, double durationSeconds) {
        int sampleRate = 16_000;
        int totalSamples = (int) (sampleRate * durationSeconds);
        byte[] buf = new byte[totalSamples * 2];

        for (int n = 0; n < totalSamples; n++) {
            double t = n / (double) sampleRate;
            short sample = (short) (32_000.0 * Math.sin(2.0 * Math.PI * freqHz * t));
            buf[n * 2]     = (byte) (sample & 0xff);
            buf[n * 2 + 1] = (byte) ((sample >> 8) & 0xff);
        }

        return buf;
    }
}
