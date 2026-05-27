import { useEffect, useState } from 'react';
import type { Peer } from '../lib/peer';
import type { ConnectionStats } from '../lib/types';

const DEFAULT_STATS: ConnectionStats = {
  rttMs: 0,
  iceConnectionState: 'new',
  connectionState: 'new',
  inboundTracks: 0,
  outboundTracks: 0,
};

/**
 * Polls Peer.getStats() every second while the call is live.
 * Returns zeroed stats when peer is null or live is false.
 */
export function useConnectionStats(peer: Peer | null, live: boolean): ConnectionStats {
  const [stats, setStats] = useState<ConnectionStats>(DEFAULT_STATS);

  useEffect(() => {
    if (!peer || !live) {
      setStats(DEFAULT_STATS);
      return;
    }
    const id = setInterval(async () => {
      try {
        setStats(await peer.getStats());
      } catch { /* best-effort */ }
    }, 1000);
    return () => clearInterval(id);
  }, [peer, live]);

  return stats;
}
