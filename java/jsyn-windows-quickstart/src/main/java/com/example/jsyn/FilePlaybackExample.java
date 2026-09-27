package com.example.jsyn;

import com.synauson.jsyn.JSyn;
import com.synauson.jsyn.JSynConfig;
import com.synauson.jsyn.Subscription;
import com.synauson.jsyn.event.FileEvent;
import com.synauson.jsyn.participant.Conference;
import com.synauson.jsyn.spec.FileParticipantSpec;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Simple example demonstrating file playback using JSyn.
 *
 * <p>This example:
 * <ol>
 *   <li>Initializes the JSyn runtime (loads native libraries)
 *   <li>Creates a conference
 *   <li>Adds a file participant that plays a WAV file
 *   <li>Listens for playback events (started, EOS)
 *   <li>Waits for playback to complete
 * </ol>
 *
 * <p><b>Prerequisites:</b>
 * <ul>
 *   <li>GStreamer 1.26.7 installed (runtime + development packages)
 *   <li>ONNX models directory (can be empty for file-only examples)
 * </ul>
 */
public class FilePlaybackExample {

    public static void main(String[] args) throws Exception {
        System.out.println("=== JSyn File Playback Example ===\n");

        // Step 1: Generate a test WAV file (2-second 440 Hz sine wave)
        Path tempDir = Files.createTempDirectory("jsyn-example-");
        Path testWav = generateTestWav(tempDir, "test.wav");
        System.out.println("Generated test WAV: " + testWav);

        // Step 2: Configure JSyn
        // The models directory is required but can be empty for file-only examples.
        // For VAD/SmartTurn, you need actual ONNX models.
        Path modelsDir = Files.createTempDirectory("jsyn-models-");
        JSynConfig config = JSynConfig.builder()
                .modelsDir(modelsDir.toAbsolutePath().toString())
                .rtpPortMin(40000)
                .rtpPortMax(40199)
                .build();

        // Step 3: Initialize JSyn runtime
        System.out.println("Initializing JSyn...");
        try (JSyn jsyn = new JSyn(config)) {
            System.out.println("JSyn initialized successfully\n");

            // Step 4: Create a conference
            String conferenceId = "example-conference";
            System.out.println("Starting conference: " + conferenceId);
            try (Conference conference = jsyn.startConference(conferenceId)) {
                System.out.println("Conference started\n");

                // Step 5: Add a file participant
                String participantId = "file-player";
                String fileUri = testWav.toUri().toString();

                System.out.println("Adding file participant:");
                System.out.println("  Participant ID: " + participantId);
                System.out.println("  File URI: " + fileUri);
                System.out.println("  Loop playback: false\n");

                // Latch to wait for playback completion
                CountDownLatch playbackComplete = new CountDownLatch(1);

                // Subscribe to file events
                try (Subscription subscription = conference.streamFileEvents(participantId, event -> {
                    if (event instanceof FileEvent.PlaybackStarted) {
                        System.out.println("✓ Playback started");
                    } else if (event instanceof FileEvent.Eos) {
                        System.out.println("✓ End of stream (EOS) - playback complete");
                        playbackComplete.countDown();
                    }
                })) {

                    // Add the file participant to the conference
                    conference.addFileParticipant(FileParticipantSpec.builder()
                            .id(participantId)
                            .uri(fileUri)
                            .loopPlayback(false)  // Play once and stop
                            .build());

                    // Wait for playback to complete (timeout after 10 seconds)
                    boolean completed = playbackComplete.await(10, TimeUnit.SECONDS);
                    if (!completed) {
                        System.err.println("✗ Timeout waiting for playback to complete");
                        System.exit(1);
                    }

                    System.out.println("\nPlayback completed successfully!");
                }

                // Conference automatically cleaned up when exiting try block
                System.out.println("Terminating conference...");
            }

            // JSyn runtime automatically shut down when exiting try block
            System.out.println("JSyn shutdown complete");
        }

        System.out.println("\n=== Example completed successfully ===");
    }

    /**
     * Generate a minimal RIFF WAV file with a 2-second 440 Hz sine wave
     * at 16 kHz, mono, 16-bit signed little-endian PCM.
     */
    private static Path generateTestWav(Path dir, String name) throws Exception {
        Path out = dir.resolve(name);
        int sampleRate = 16_000;
        int seconds = 2;
        int totalSamples = sampleRate * seconds;

        // Generate PCM samples
        ByteArrayOutputStream pcm = new ByteArrayOutputStream(totalSamples * 2);
        for (int n = 0; n < totalSamples; n++) {
            double t = n / (double) sampleRate;
            short sample = (short) (32_000.0 * Math.sin(2.0 * Math.PI * 440.0 * t));
            pcm.write(sample & 0xff);
            pcm.write((sample >> 8) & 0xff);
        }
        byte[] rawPcm = pcm.toByteArray();

        // Write RIFF WAV header + data
        try (OutputStream os = Files.newOutputStream(out)) {
            writeInt32LE(os, 0x46464952); // "RIFF"
            writeInt32LE(os, rawPcm.length + 36);
            writeInt32LE(os, 0x45564157); // "WAVE"
            writeInt32LE(os, 0x20746d66); // "fmt "
            writeInt32LE(os, 16);         // chunk size
            writeInt16LE(os, 1);          // PCM
            writeInt16LE(os, 1);          // mono
            writeInt32LE(os, sampleRate);
            writeInt32LE(os, sampleRate * 2); // byte rate
            writeInt16LE(os, 2);          // block align
            writeInt16LE(os, 16);         // bits per sample
            writeInt32LE(os, 0x61746164); // "data"
            writeInt32LE(os, rawPcm.length);
            os.write(rawPcm);
        }
        return out;
    }

    private static void writeInt32LE(OutputStream os, int v) throws Exception {
        os.write(v & 0xff);
        os.write((v >> 8) & 0xff);
        os.write((v >> 16) & 0xff);
        os.write((v >> 24) & 0xff);
    }

    private static void writeInt16LE(OutputStream os, int v) throws Exception {
        os.write(v & 0xff);
        os.write((v >> 8) & 0xff);
    }
}
