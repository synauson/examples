import type { EventRecord } from './types';

/**
 * Append-only event store with a rolling events-per-second average over
 * the last 5 seconds. React components use this via useSyncExternalStore
 * so the rail re-renders only when append fires.
 */
export class EventLog {
  private events: EventRecord[] = [];
  private readonly CAP = 5000;
  private windowMs = 5_000;
  private timestamps: number[] = [];   // ms since epoch, for the rolling rate
  private listeners = new Set<() => void>();
  private totalSeen = 0;

  /** Subscribe to changes; returns an unsubscribe. */
  subscribe(fn: () => void): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  snapshot(): EventRecord[] { return this.events; }

  /**
   * Rolling events-per-second average. now is injected for tests;
   * defaults to Date.now().
   */
  rate(now: number = Date.now()): number {
    const cutoff = now - this.windowMs;
    // Prune older timestamps as we read.
    while (this.timestamps.length && this.timestamps[0] < cutoff) {
      this.timestamps.shift();
    }
    return +(this.timestamps.length / (this.windowMs / 1000)).toFixed(1);
  }

  append(ev: EventRecord, now: number = Date.now()): void {
    const next = [...this.events, ev];
    this.events = next.length > this.CAP ? next.slice(next.length - this.CAP) : next;
    this.timestamps.push(now);
    this.totalSeen++;
    this.listeners.forEach(fn => fn());
  }

  counts(): { total: number; vad: number; turn: number } {
    let vad = 0, turn = 0;
    for (const e of this.events) {
      if (e.type === 'vad') vad++;
      else turn++;
    }
    return { total: this.totalSeen, vad, turn };
  }
}
