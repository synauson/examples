import { useEffect, useState } from 'react';

function formatUptime(startedAt: Date): string {
  const secs = Math.floor((Date.now() - startedAt.getTime()) / 1000);
  const h = String(Math.floor(secs / 3600)).padStart(2, '0');
  const m = String(Math.floor((secs % 3600) / 60)).padStart(2, '0');
  const s = String(secs % 60).padStart(2, '0');
  return `${h}:${m}:${s}`;
}

/**
 * Returns a live-ticking HH:MM:SS uptime string, updating every second.
 * Returns '00:00:00' when startedAt is null.
 */
export function useUptime(startedAt: Date | null): string {
  const [display, setDisplay] = useState('00:00:00');

  useEffect(() => {
    if (!startedAt) {
      setDisplay('00:00:00');
      return;
    }
    setDisplay(formatUptime(startedAt));
    const id = setInterval(() => setDisplay(formatUptime(startedAt)), 1000);
    return () => clearInterval(id);
  }, [startedAt]);

  return display;
}
