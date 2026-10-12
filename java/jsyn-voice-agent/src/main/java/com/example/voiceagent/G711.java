package com.example.voiceagent;

/** G.711 mu-law (PCMU), and resampling 16 kHz audio to a phone call's 8 kHz. */
final class G711 {
    private G711() {}

    private static final int BIAS = 0x84;
    private static final int CLIP = 32635;
    static final byte SILENCE = encode((short) 0);

    static byte encode(short sample) {
        int sign = sample < 0 ? 0x80 : 0;
        int magnitude = Math.min(Math.abs((int) sample), CLIP) + BIAS;
        int exponent = Math.max(32 - Integer.numberOfLeadingZeros(magnitude) - 8, 0);
        int mantissa = (magnitude >> (exponent + 3)) & 0x0F;
        return (byte) ~(sign | (exponent << 4) | mantissa);
    }

    static short decode(byte b) {
        int u = ~b & 0xFF;
        int magnitude = ((((u & 0x0F) << 3) + BIAS) << ((u & 0x70) >> 4)) - BIAS;
        return (short) ((u & 0x80) != 0 ? -magnitude : magnitude);
    }

    /**
     * 16 kHz 16-bit little-endian PCM as 8 kHz mu-law: a low-pass filter below 4 kHz (a
     * windowed sinc), then every second sample.
     */
    static byte[] encode16k(byte[] pcm) {
        int n = pcm.length / 2;
        double[] taps = lowPass(3_600.0 / 16_000, 33);
        int half = taps.length / 2;
        byte[] out = new byte[(n + 1) / 2];
        for (int o = 0; o < out.length; o++) {
            double acc = 0;
            for (int k = 0; k < taps.length; k++) {
                int i = 2 * o + k - half;
                if (i >= 0 && i < n) {
                    acc += taps[k] * (short) ((pcm[2 * i] & 0xFF) | (pcm[2 * i + 1] << 8));
                }
            }
            out[o] = encode((short) Math.max(-32768, Math.min(32767, Math.round(acc))));
        }
        return out;
    }

    private static double[] lowPass(double cutoff, int length) {
        double[] taps = new double[length];
        double mid = (length - 1) / 2.0;
        double sum = 0;
        for (int i = 0; i < length; i++) {
            double x = i - mid;
            double sinc = x == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * x) / (Math.PI * x);
            taps[i] = sinc * (0.54 - 0.46 * Math.cos(2 * Math.PI * i / (length - 1))); // Hamming
            sum += taps[i];
        }
        for (int i = 0; i < length; i++) {
            taps[i] /= sum;
        }
        return taps;
    }
}
