package com.example.voiceagent;

import com.synauson.jsyn.NativeAudioFormat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads and writes 16-bit PCM WAV files. */
final class Wav {
    private Wav() {}

    /** A recording's samples, mono, in the native participant format of its sample rate. */
    record Audio(NativeAudioFormat format, byte[] pcm) {}

    /**
     * Reads a 16-bit PCM WAV file at 8, 16 or 48 kHz, mixing stereo down to mono. For
     * anything else, convert first, for example
     * {@code ffmpeg -i in.mp3 -ac 1 -ar 16000 -sample_fmt s16 out.wav}.
     */
    static Audio read(Path wav) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(wav)).order(ByteOrder.LITTLE_ENDIAN);
        if (b.remaining() < 12 || b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157) {
            throw new IOException(wav + " is not a WAV file");
        }
        int channels = 0;
        int rate = 0;
        int bits = 0;
        int at = 12;
        while (at + 8 <= b.limit()) {
            int id = b.getInt(at);
            int size = b.getInt(at + 4);
            if (id == 0x20746d66) { // "fmt "
                if (b.getShort(at + 8) != 1) {
                    throw new IOException(wav + " is not PCM");
                }
                channels = b.getShort(at + 10);
                rate = b.getInt(at + 12);
                bits = b.getShort(at + 22);
            } else if (id == 0x61746164) { // "data"
                if (bits != 16 || channels < 1 || channels > 2) {
                    throw new IOException(wav + ": need 16-bit mono or stereo, got "
                            + bits + "-bit, " + channels + " channels");
                }
                NativeAudioFormat format = switch (rate) {
                    case 8_000 -> NativeAudioFormat.PCM_S16LE8K_MONO;
                    case 16_000 -> NativeAudioFormat.PCM_S16LE16K_MONO;
                    case 48_000 -> NativeAudioFormat.PCM_S16LE48K_MONO;
                    default -> throw new IOException(wav + ": need 8, 16 or 48 kHz, got " + rate + " Hz");
                };
                int len = Math.min(size, b.limit() - at - 8);
                byte[] pcm = new byte[len / (2 * channels) * 2];
                for (int i = 0, in = at + 8; i < pcm.length; i += 2, in += 2 * channels) {
                    int sample = b.getShort(in);
                    if (channels == 2) {
                        sample = (sample + b.getShort(in + 2)) / 2;
                    }
                    pcm[i] = (byte) sample;
                    pcm[i + 1] = (byte) (sample >> 8);
                }
                return new Audio(format, pcm);
            }
            at += 8 + size + (size & 1);
        }
        throw new IOException(wav + " has no audio");
    }

    /** Writes 16-bit mono PCM as a WAV file. */
    static void write(Path out, byte[] pcm, int sampleRate) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes());
        header.put("fmt ".getBytes()).putInt(16)
                .putShort((short) 1)            // PCM
                .putShort((short) 1)            // mono
                .putInt(sampleRate)
                .putInt(sampleRate * 2)         // bytes per second
                .putShort((short) 2)            // bytes per frame
                .putShort((short) 16);          // bits per sample
        header.put("data".getBytes()).putInt(pcm.length);
        byte[] file = new byte[44 + pcm.length];
        System.arraycopy(header.array(), 0, file, 0, 44);
        System.arraycopy(pcm, 0, file, 44, pcm.length);
        Files.write(out, file);
    }
}
