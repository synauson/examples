package com.example.jsyn;

import com.synauson.jsyn.JSyn;
import com.synauson.jsyn.JSynConfig;
import com.synauson.jsyn.NativeAudioFormat;
import com.synauson.jsyn.Subscription;
import com.synauson.jsyn.event.FileEvent;
import com.synauson.jsyn.participant.Conference;
import com.synauson.jsyn.participant.NativeParticipant;
import com.synauson.jsyn.spec.ConnectionEntry;
import com.synauson.jsyn.spec.ConnectionMatrix;
import com.synauson.jsyn.spec.FileParticipantSpec;
import com.synauson.jsyn.spec.NativeParticipantSpec;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demonstrates bidirectional audio I/O through a NativeParticipant.
 *
 * <p>Architecture:
 * <pre>
 *   File Participant → Native Participant (Java read/write)
 *   (plays test.wav)   ├─ Ingress: write() sends PCM to conference
 *                      └─ Egress: read() receives mixed audio from conference
 * </pre>
 *
 * <p>This example:
 * <ol>
 *   <li>Creates a file participant playing a test WAV file
 *   <li>Creates a native participant connected to the file participant
 *   <li>Writes synthetic PCM to the native participant's ingress
 *   <li>Reads mixed audio from the native participant's egress
 *   <li>Saves the egress audio to disk as output.wav
 *   <li>Validates that audio was received
 * </ol>
 *
 * <p><b>Use case:</b> Testing the JNI layer's bidirectional audio pathway -
 * Java code can both send audio into the conference (ingress) and receive
 * the mixed conference audio (egress) for custom processing, recording, or
 * streaming.
 */
public class NativeParticipantIOExample {

    public static void main(String[] args) throws Exception {
        System.out.println("=== JSyn NativeParticipant I/O Example ===\n");

        // Step 1: Create test files
        Path tempDir = Files.createTempDirectory("jsyn-native-io-");
        Path inputWav = generateTestWav(tempDir, "input.wav", 440.0, 2.0);
        Path outputWav = tempDir.resolve("output.wav");
        System.out.println("Test directory: " + tempDir);
        System.out.println("Input WAV: " + inputWav);
        System.out.println("Output WAV: " + outputWav + "\n");

        // Step 2: Configure JSyn
        Path modelsDir = Files.createTempDirectory("jsyn-models-");
        JSynConfig config = JSynConfig.builder()
                .modelsDir(modelsDir.toAbsolutePath().toString())
                .rtpPortMin(40400)
                .rtpPortMax(40599)
                .build();

        System.out.println("Initializing JSyn...");
        try (JSyn jsyn = new JSyn(config)) {
            System.out.println("JSyn initialized\n");

            String conferenceId = "native-io-conference";
            try (Conference conference = jsyn.startConference(conferenceId)) {
                System.out.println("Conference started: " + conferenceId + "\n");

                String fileParticipantId = "file-source";
                String nativeParticipantId = "native-receiver";

                // Latch to wait for file playback to complete
                CountDownLatch fileComplete = new CountDownLatch(1);

                // Subscribe to file events
                try (Subscription fileSub = conference.streamFileEvents(fileParticipantId, event -> {
                    if (event instanceof FileEvent.PlaybackStarted) {
                        System.out.println("✓ File playback started");
                    } else if (event instanceof FileEvent.Eos) {
                        System.out.println("✓ File playback complete (EOS)");
                        fileComplete.countDown();
                    }
                })) {

                    // Step 3: Add file participant (audio source)
                    System.out.println("Adding file participant:");
                    System.out.println("  ID: " + fileParticipantId);
                    System.out.println("  File: " + inputWav.getFileName() + "\n");

                    conference.addFileParticipant(FileParticipantSpec.builder()
                            .id(fileParticipantId)
                            .uri(inputWav.toUri().toString())
                            .loopPlayback(false)
                            .build());

                    // Step 4: Add native participant (audio sink + source)
                    System.out.println("Adding native participant:");
                    System.out.println("  ID: " + nativeParticipantId);
                    System.out.println("  Format: PCM 16kHz mono S16LE\n");

                    try (NativeParticipant nativeParticipant = conference.addNativeParticipant(
                            nativeParticipantId,
                            NativeParticipantSpec.builder()
                                    .format(NativeAudioFormat.PCM_S16LE16K_MONO)
                                    .build())) {

                        // Step 5: Connect file → native (so native receives file audio)
                        System.out.println("Routing:");
                        System.out.println("  " + fileParticipantId + " → " + nativeParticipantId);
                        System.out.println("  " + nativeParticipantId + " → " + nativeParticipantId + " (loopback)\n");

                        conference.updatePartyAudioConnections(new ConnectionMatrix(
                                ConnectionEntry.connect(fileParticipantId, nativeParticipantId),
                                ConnectionEntry.connect(nativeParticipantId, nativeParticipantId)
                        ));

                        // Step 6: Write ingress audio (Java → conference)
                        System.out.println("Writing synthetic ingress audio...");
                        ScheduledExecutorService ingressWriter = Executors.newSingleThreadScheduledExecutor();
                        AtomicBoolean stopIngress = new AtomicBoolean(false);
                        AtomicInteger ingressBytesWritten = new AtomicInteger(0);

                        ingressWriter.scheduleAtFixedRate(() -> {
                            if (stopIngress.get()) return;
                            byte[] chunk = generateSilence(1600); // 50ms of silence
                            int written = nativeParticipant.write(chunk, 0, chunk.length);
                            ingressBytesWritten.addAndGet(written);
                        }, 0, 50, TimeUnit.MILLISECONDS);

                        // Step 7: Read egress audio (conference → Java)
                        System.out.println("Reading egress audio...");
                        ByteArrayOutputStream egressBuffer = new ByteArrayOutputStream();
                        AtomicInteger egressBytesRead = new AtomicInteger(0);
                        AtomicBoolean stopEgress = new AtomicBoolean(false);

                        ScheduledExecutorService egressReader = Executors.newSingleThreadScheduledExecutor();
                        egressReader.scheduleAtFixedRate(() -> {
                            if (stopEgress.get()) return;
                            byte[] chunk = new byte[1600]; // 50ms buffer
                            int bytesRead = nativeParticipant.read(chunk, 0, chunk.length);
                            if (bytesRead > 0) {
                                synchronized (egressBuffer) {
                                    egressBuffer.write(chunk, 0, bytesRead);
                                }
                                egressBytesRead.addAndGet(bytesRead);
                            }
                        }, 100, 20, TimeUnit.MILLISECONDS);

                        // Wait for file to finish playing
                        boolean completed = fileComplete.await(15, TimeUnit.SECONDS);
                        if (!completed) {
                            throw new RuntimeException("File playback did not complete within 15s");
                        }

                        // Give egress time to drain
                        Thread.sleep(500);

                        // Stop I/O threads
                        stopIngress.set(true);
                        stopEgress.set(true);
                        ingressWriter.shutdown();
                        egressReader.shutdown();
                        ingressWriter.awaitTermination(1, TimeUnit.SECONDS);
                        egressReader.awaitTermination(1, TimeUnit.SECONDS);

                        System.out.println("\n=== I/O Summary ===");
                        System.out.println("Ingress bytes written: " + ingressBytesWritten.get());
                        System.out.println("Egress bytes read: " + egressBytesRead.get());

                        // Step 8: Save egress audio to disk
                        byte[] egressPcm;
                        synchronized (egressBuffer) {
                            egressPcm = egressBuffer.toByteArray();
                        }

                        if (egressPcm.length == 0) {
                            throw new RuntimeException("No audio received on egress! Check routing.");
                        }

                        writeWavFile(outputWav, egressPcm);
                        System.out.println("\n✓ Output written to: " + outputWav);
                        System.out.println("  Size: " + egressPcm.length + " bytes");
                        System.out.println("  Duration: " + (egressPcm.length / 32000.0) + " seconds (approx)");

                        // Step 9: Validate
                        if (egressPcm.length < 16000) {
                            throw new RuntimeException("Egress audio too short - expected ~2 seconds of data");
                        }

                        double rms = computeRmsS16LE(egressPcm);
                        System.out.println("  RMS energy: " + String.format("%.2f", rms));

                        if (rms < 100.0) {
                            System.out.println("\n⚠ Warning: Low RMS energy - audio may be silent");
                        } else {
                            System.out.println("\n✓ Validation passed - audio contains signal");
                        }
                    }

                    System.out.println("\nTerminating conference...");
                }
            }

            System.out.println("JSyn shutdown complete");
        }

        System.out.println("\n=== Example completed successfully ===");
        System.out.println("Output file: " + outputWav);
    }

    /**
     * Generate a test WAV file with a sine wave at the given frequency and duration.
     */
    private static Path generateTestWav(Path dir, String name, double freqHz, double durationSec)
            throws Exception {
        Path out = dir.resolve(name);
        int sampleRate = 16_000;
        int totalSamples = (int) (sampleRate * durationSec);

        ByteArrayOutputStream pcm = new ByteArrayOutputStream(totalSamples * 2);
        for (int n = 0; n < totalSamples; n++) {
            double t = n / (double) sampleRate;
            short sample = (short) (32_000.0 * Math.sin(2.0 * Math.PI * freqHz * t));
            pcm.write(sample & 0xff);
            pcm.write((sample >> 8) & 0xff);
        }

        writeWavFile(out, pcm.toByteArray());
        return out;
    }

    /**
     * Write PCM samples to a RIFF WAV file.
     */
    private static void writeWavFile(Path path, byte[] pcm) throws Exception {
        try (OutputStream os = Files.newOutputStream(path)) {
            writeInt32LE(os, 0x46464952); // "RIFF"
            writeInt32LE(os, pcm.length + 36);
            writeInt32LE(os, 0x45564157); // "WAVE"
            writeInt32LE(os, 0x20746d66); // "fmt "
            writeInt32LE(os, 16);         // chunk size
            writeInt16LE(os, 1);          // PCM
            writeInt16LE(os, 1);          // mono
            writeInt32LE(os, 16000);      // sample rate
            writeInt32LE(os, 32000);      // byte rate
            writeInt16LE(os, 2);          // block align
            writeInt16LE(os, 16);         // bits per sample
            writeInt32LE(os, 0x61746164); // "data"
            writeInt32LE(os, pcm.length);
            os.write(pcm);
        }
    }

    /**
     * Generate silence (zeros).
     */
    private static byte[] generateSilence(int bytes) {
        return new byte[bytes];
    }

    /**
     * Compute RMS energy of S16LE PCM buffer.
     */
    private static double computeRmsS16LE(byte[] buf) {
        long sum = 0;
        int frames = buf.length / 2;
        for (int i = 0; i < frames; i++) {
            int lo = buf[i * 2] & 0xff;
            int hi = buf[i * 2 + 1];
            short s = (short) ((hi << 8) | lo);
            sum += (long) s * s;
        }
        if (frames == 0) return 0.0;
        return Math.sqrt((double) sum / frames);
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
