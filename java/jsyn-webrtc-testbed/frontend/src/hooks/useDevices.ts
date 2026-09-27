import { useCallback, useEffect, useState } from 'react';

export type DeviceState = {
  inputs: MediaDeviceInfo[];
  outputs: MediaDeviceInfo[];
  refresh: () => Promise<void>;
};

/**
 * Enumerates audio input/output devices and re-enumerates on OS devicechange events.
 * Labels are empty until getUserMedia permission is granted.
 */
export function useDevices(): DeviceState {
  const [inputs, setInputs] = useState<MediaDeviceInfo[]>([]);
  const [outputs, setOutputs] = useState<MediaDeviceInfo[]>([]);

  const refresh = useCallback(async () => {
    try {
      const all = await navigator.mediaDevices.enumerateDevices();
      setInputs(all.filter(d => d.kind === 'audioinput'));
      setOutputs(all.filter(d => d.kind === 'audiooutput'));
    } catch { /* permission not yet granted */ }
  }, []);

  useEffect(() => {
    refresh();
    navigator.mediaDevices.addEventListener('devicechange', refresh);
    return () => navigator.mediaDevices.removeEventListener('devicechange', refresh);
  }, [refresh]);

  return { inputs, outputs, refresh };
}
