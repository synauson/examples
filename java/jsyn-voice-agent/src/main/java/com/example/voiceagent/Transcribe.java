package com.example.voiceagent;

import com.synauson.jsyn.JSyn;
import com.synauson.jsyn.NativeAudioFormat;
import com.synauson.jsyn.Subscription;
import com.synauson.jsyn.event.TranscriptEvent;
import com.synauson.jsyn.participant.Conference;
import com.synauson.jsyn.participant.NativeParticipant;
import com.synauson.jsyn.spec.NativeParticipantSpec;
import com.synauson.jsyn.spec.SttConfig;
import com.synauson.jsyn.spec.TurnDetectionConfig;
import com.synauson.jsyn.spec.VadConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Speech to text on a recording: the WAV file's audio goes into a native participant
 * with VAD, turn detection and STT, 20 ms at a time and in real time, as a call's audio
 * would arrive, and the transcript comes back as it is decoded.
 */
final class Transcribe {
    private Transcribe() {}

    static int run(Path workDir, Path wav) throws Exception {
        if (!Files.isRegularFile(wav)) {
            System.err.println("no such file: " + wav);
            return 2;
        }
        Wav.Audio audio = Wav.read(wav);
        try (JSyn jsyn = Engine.start(workDir)) {
            System.out.println("Loading speech to text...");
            Engine.awaitSpeech(jsyn, Duration.ofMinutes(15));

            String id = "recording";
            try (Conference conf = jsyn.startConference("transcribe");
                 NativeParticipant recording = conf.addNativeParticipant(id, NativeParticipantSpec.builder()
                         .format(audio.format())
                         // STT needs turn detection, and turn detection needs VAD.
                         .vad(VadConfig.defaults())
                         .turnDetection(TurnDetectionConfig.defaults())
                         .stt(SttConfig.defaults())
                         .build())) {
                BlockingQueue<TranscriptEvent> transcript = new LinkedBlockingQueue<>();
                List<String> turns = new ArrayList<>();
                try (Subscription sub = conf.streamTranscriptEvents(id, transcript::add)) {
                    System.out.println("Transcribing " + wav.getFileName() + " ("
                            + audio.pcm().length / audio.format().bytesPer20ms() / 50 + " s):");
                    play(recording, audio, transcript, turns);
                    // The recording is over. A turn still open ends now; its text follows once
                    // STT has decoded the last audio. Then collect anything still on its way.
                    boolean open = conf.forceEndTurn(id).isPresent();
                    TranscriptEvent e;
                    while ((e = transcript.poll(open ? 30 : 2, TimeUnit.SECONDS)) != null) {
                        if (print(e, turns)) {
                            open = false;
                        }
                    }
                }
                conf.removeParticipant(id);
                System.out.println();
                System.out.println("Transcript (" + turns.size() + " turns):");
                turns.forEach(t -> System.out.println("  " + t));
                return turns.isEmpty() ? 1 : 0;
            }
        }
    }

    /** Writes the audio, then a second of silence, one 20 ms frame every 20 ms. */
    private static void play(NativeParticipant participant, Wav.Audio audio,
                             BlockingQueue<TranscriptEvent> transcript, List<String> turns)
            throws InterruptedException {
        int frame = audio.format().bytesPer20ms();
        byte[] pcm = audio.pcm();
        byte[] chunk = new byte[frame];
        int frames = (pcm.length + frame - 1) / frame + 50;
        long start = System.nanoTime();
        for (int f = 0; f < frames; f++) {
            int from = f * frame;
            int n = Math.max(0, Math.min(frame, pcm.length - from));
            System.arraycopy(pcm, Math.min(from, pcm.length), chunk, 0, n);
            java.util.Arrays.fill(chunk, n, frame, (byte) 0);
            participant.write(chunk, 0, frame);
            TranscriptEvent e;
            while ((e = transcript.poll()) != null) {
                print(e, turns);
            }
            long wait = start + (f + 1) * 20_000_000L - System.nanoTime();
            if (wait > 0) {
                Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
            }
        }
    }

    /** Prints committed text as it comes and each turn as it ends; true for a turn. */
    private static boolean print(TranscriptEvent event, List<String> turns) {
        if (event instanceof TranscriptEvent.Delta delta) {
            System.out.print(delta.text);
            System.out.flush();
        } else if (event instanceof TranscriptEvent.Turn turn) {
            System.out.println();
            if (!turn.text.isBlank()) {
                turns.add(turn.text.trim());
            }
            return true;
        }
        return false;
    }
}
