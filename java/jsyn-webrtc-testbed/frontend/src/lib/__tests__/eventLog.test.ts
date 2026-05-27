import { describe, expect, it } from 'vitest';
import { EventLog } from '../eventLog';
import type { EventRecord } from '../types';

function vad(t = '12:00:00.000'): EventRecord {
  return { type: 'vad', participantId: 'alice', kind: 'speech_start', t,
           confidence: 0.9, durationMs: null };
}

describe('EventLog', () => {
  it('appends and counts events', () => {
    const log = new EventLog();
    log.append(vad());
    log.append(vad());
    expect(log.snapshot()).toHaveLength(2);
    expect(log.counts()).toEqual({ total: 2, vad: 2, turn: 0 });
  });

  it('computes rolling events-per-second over a 5s window', () => {
    const log = new EventLog();
    log.append(vad(), 0);
    log.append(vad(), 1000);
    log.append(vad(), 2000);
    log.append(vad(), 3000);
    log.append(vad(), 4000);
    // 5 events in the last 5000ms = 1.0 ev/s
    expect(log.rate(4500)).toBe(1.0);
  });

  it('prunes events older than the window from the rate calc', () => {
    const log = new EventLog();
    log.append(vad(), 0);
    log.append(vad(), 1000);
    // Query at t=10_000: window starts at 5000, both events pruned.
    expect(log.rate(10_000)).toBe(0);
  });

  it('notifies subscribers on append', () => {
    const log = new EventLog();
    let fired = 0;
    const unsub = log.subscribe(() => fired++);
    log.append(vad());
    log.append(vad());
    expect(fired).toBe(2);
    unsub();
    log.append(vad());
    expect(fired).toBe(2);
  });

  it('caps events at 5000 and drops the oldest', () => {
    const log = new EventLog();
    for (let i = 0; i < 5001; i++) {
      log.append({ type: 'vad', participantId: `p${i}`, kind: 'speech_start', t: '00:00:00.000', confidence: null, durationMs: null });
    }
    const snap = log.snapshot();
    expect(snap.length).toBe(5000);
    expect(snap[0].participantId).toBe('p1'); // oldest (p0) was dropped
  });
});
