import { useEffect, useRef, useState } from 'react';

/**
 * Drives the SpeakerTile halo + Waveform from a live MediaStream.
 *
 * Sample rate ~30 Hz; downsamples the analyser's frequency bins to `bars`
 * values, smooths each across frames with exponential smoothing
 * (alpha ≈ 0.3 per the design spec), and exposes a single envelope value
 * derived from the average of the bars.
 *
 * Returns null bars when stream is null so callers can render a quiescent
 * waveform.
 */
export function useAudioMeter(stream: MediaStream | null, bars: number) {
  const [meter, setMeter] = useState<{ bars: number[]; envelope: number } | null>(null);
  const rafRef = useRef<number | null>(null);

  useEffect(() => {
    if (!stream) {
      setMeter(null);
      return;
    }
    const audioCtx = new AudioContext();
    const source = audioCtx.createMediaStreamSource(stream);
    const analyser = audioCtx.createAnalyser();
    analyser.fftSize = 256;
    analyser.smoothingTimeConstant = 0.3;
    source.connect(analyser);

    const freqData = new Uint8Array(analyser.frequencyBinCount);
    const smoothed = new Array<number>(bars).fill(0);
    let lastSample = 0;

    const tick = (now: number) => {
      rafRef.current = requestAnimationFrame(tick);
      if (now - lastSample < 33) return;        // ~30Hz
      lastSample = now;
      analyser.getByteFrequencyData(freqData);

      const binSize = Math.floor(freqData.length / bars);
      let envSum = 0;
      const next = new Array<number>(bars);
      for (let i = 0; i < bars; i++) {
        let sum = 0;
        for (let j = 0; j < binSize; j++) sum += freqData[i * binSize + j];
        const norm = sum / (binSize * 255);
        smoothed[i] = smoothed[i] * 0.7 + norm * 0.3;
        next[i] = smoothed[i];
        envSum += smoothed[i];
      }
      setMeter({ bars: next, envelope: envSum / bars });
    };
    rafRef.current = requestAnimationFrame(tick);
    return () => {
      if (rafRef.current !== null) cancelAnimationFrame(rafRef.current);
      source.disconnect();
      audioCtx.close().catch(() => {});
    };
  }, [stream, bars]);

  return meter;
}
