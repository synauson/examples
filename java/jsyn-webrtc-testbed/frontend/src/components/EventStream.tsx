import { useEffect, useRef, useState } from 'react';
import { Dot, Icon } from './primitives';
import { FilterChip } from './forms';
import { probBucket } from '../lib/probBucket';
import type { EventRecord } from '../lib/types';

function hexA(hex: string, a: number): string {
  const h = hex.replace('#', '');
  const r = parseInt(h.slice(0, 2), 16);
  const g = parseInt(h.slice(2, 4), 16);
  const b = parseInt(h.slice(4, 6), 16);
  return `rgba(${r},${g},${b},${a})`;
}

const SELF_COLOR  = '#5BD3F5';
const REMOTE_COLOR = '#B58CFF';
const ACCENT = '#3DDC97';

function formatTime(d: Date): string {
  return d.toTimeString().slice(0, 8);
}

// ── Stat block ──────────────────────────────────────────────────────────

function Stat({ label, value, accent, hint }: {
  label: string; value: number | string; accent?: string; hint?: string;
}) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4, minWidth: 0 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 10, color: '#60656d', letterSpacing: 0.6 }} className="font-mono">
        {accent && <span style={{ width: 5, height: 5, borderRadius: 1.5, background: accent, flexShrink: 0 }} />}
        {label}
      </div>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
        <span style={{ fontSize: 22, fontWeight: 600, letterSpacing: -0.4, color: '#e7e9ec', fontVariantNumeric: 'tabular-nums' }} className="font-sans">
          {value}
        </span>
        {hint && <span style={{ fontSize: 10.5, color: '#60656d' }} className="font-mono">{hint}</span>}
      </div>
    </div>
  );
}

// ── Event row ───────────────────────────────────────────────────────────

function EventRow({ event, selfId, dt }: { event: EventRecord; selfId: string; dt: number | null }) {
  const isSelf = event.participantId === selfId;
  const participantColor = isSelf ? SELF_COLOR : REMOTE_COLOR;
  const isTurn = event.type === 'turn';
  const bucket = isTurn ? probBucket(event.probability) : null;
  const accentEdge = isTurn && bucket ? bucket.color : participantColor;

  const t = event.t;
  const tMain = t.slice(0, 8);  // HH:MM:SS
  const tFrac = t.slice(8);     // .mmm

  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: '130px 70px 160px 1fr 80px',
        alignItems: 'center',
        gap: 14,
        padding: '11px 28px 11px 26px',
        fontSize: 13.5,
        lineHeight: 1.4,
        borderLeft: `2px solid ${accentEdge}`,
        background: isTurn && bucket ? hexA(bucket.color, 0.025) : 'transparent',
      }}
      className="font-mono"
    >
      {/* Timestamp: HH:MM:SS in text-3, .mmm in text-4 */}
      <span style={{ color: '#60656d', letterSpacing: 0.2, fontVariantNumeric: 'tabular-nums' }}>
        {tMain}<span style={{ color: '#3f444b' }}>{tFrac}</span>
      </span>

      {/* Type chip */}
      <span style={{
        display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
        height: 22, padding: '0 8px', borderRadius: 5, fontSize: 10.5, letterSpacing: 0.5,
        background: isTurn ? hexA('#B58CFF', 0.12) : hexA('#5BD3F5', 0.12),
        color: isTurn ? '#B58CFF' : '#5BD3F5',
        border: `1px solid ${isTurn ? hexA('#B58CFF', 0.25) : hexA('#5BD3F5', 0.25)}`,
      }}>
        {isTurn ? 'TURN' : 'VAD'}
      </span>

      {/* Participant: dot + handle */}
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7, overflow: 'hidden' }}>
        <span style={{ width: 7, height: 7, borderRadius: '50%', background: participantColor, flexShrink: 0 }} />
        <span style={{ color: '#e7e9ec', fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {event.participantId}
        </span>
      </span>

      {/* Payload */}
      {isTurn && bucket ? (
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, minWidth: 0 }}>
          <span style={{ color: '#e7e9ec', minWidth: 160, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {event.probability >= 0.65
              ? 'done_talking'
              : event.probability >= 0.20
              ? 'likely_done_talking'
              : 'not_done_talking'}
          </span>
          <div style={{ flex: 1, minWidth: 40, height: 5, background: hexA(bucket.color, 0.14), borderRadius: 3, position: 'relative', overflow: 'hidden' }}>
            <div style={{ position: 'absolute', inset: 0, width: `${event.probability * 100}%`, background: bucket.color, borderRadius: 3, boxShadow: `0 0 10px ${hexA(bucket.color, 0.5)}` }} />
          </div>
          <span style={{ color: bucket.color, minWidth: 40, textAlign: 'right', fontWeight: 500, fontVariantNumeric: 'tabular-nums' }}>
            {event.probability.toFixed(2)}
          </span>
        </div>
      ) : (
        <span style={{ color: '#e7e9ec', display: 'inline-flex', alignItems: 'center', gap: 8 }}>
          <Icon name="wave" size={12} />
          <span style={{ fontWeight: 500 }}>{event.type === 'vad' ? event.kind : ''}</span>
        </span>
      )}

      {/* Δt — ms since previous visible row, or — for the first row */}
      <span style={{ textAlign: 'right', color: '#60656d', fontSize: 12, fontVariantNumeric: 'tabular-nums' }}>
        {dt === null ? '—' : `+${dt}ms`}
      </span>
    </div>
  );
}

// ── Public component ────────────────────────────────────────────────────

export type EventStreamProps = {
  events: EventRecord[];
  selfId: string;
  rate: number;
  counts: { total: number; vad: number; turn: number };
  solo: boolean;
  rttMs: number;
  callStartedAt: Date | null;
};

type FilterType = 'all' | 'vad' | 'turn';

export function EventStream({ events, selfId, rate, counts, solo, rttMs, callStartedAt }: EventStreamProps) {
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const endRef = useRef<HTMLDivElement | null>(null);
  const frozenBuffer = useRef<EventRecord[]>([]);

  const [autoScroll, setAutoScroll] = useState(true);
  const [filter, setFilter] = useState<FilterType>('all');
  const [paused, setPaused] = useState(false);
  const [pendingCount, setPendingCount] = useState(0);

  function togglePause() {
    setPaused(p => {
      if (p) {
        frozenBuffer.current = [];
        setPendingCount(0);
        return false;
      } else {
        frozenBuffer.current = [...events];
        return true;
      }
    });
  }

  // Track how many new events arrived while paused
  useEffect(() => {
    if (paused) {
      setPendingCount(events.length - frozenBuffer.current.length);
    }
  }, [events.length, paused]);

  // Build the visible list: paused snapshot or live events, filtered by solo + type
  const baseList = paused ? frozenBuffer.current : events;
  const soloFiltered = solo ? baseList.filter(e => e.participantId === selfId) : baseList;
  const visibleEvents = filter === 'all' ? soloFiltered
    : soloFiltered.filter(e => e.type === filter);

  function handleScroll() {
    const el = scrollRef.current;
    if (!el) return;
    setAutoScroll(el.scrollTop + el.clientHeight >= el.scrollHeight - 80);
  }

  useEffect(() => {
    if (autoScroll && !paused) endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [visibleEvents.length, autoScroll, paused]);

  async function handleCopy() {
    try {
      await navigator.clipboard.writeText(JSON.stringify(visibleEvents, null, 2));
    } catch { /* clipboard unavailable */ }
  }

  return (
    <aside style={{ borderLeft: '1px solid rgba(255,255,255,0.06)', background: '#101216', display: 'flex', flexDirection: 'column', minHeight: 0 }}>

      {/* Header */}
      <div style={{ padding: '18px 28px 16px', borderBottom: '1px solid rgba(255,255,255,0.06)', display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexShrink: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <span style={{ fontSize: 18, fontWeight: 600, letterSpacing: -0.3, color: '#e7e9ec' }}>Event stream</span>
          <span style={{ padding: '3px 8px', borderRadius: 5, background: hexA(ACCENT, 0.1), border: `1px solid ${hexA(ACCENT, 0.25)}`, fontSize: 10, letterSpacing: 0.5, color: ACCENT, display: 'inline-flex', alignItems: 'center', gap: 5 }} className="font-mono">
            <Dot color={ACCENT} size={4} pulse />
            LIVE
          </span>
          <span style={{ fontSize: 12, color: '#60656d' }} className="font-mono">
            <span style={{ color: '#e7e9ec' }}>{rate.toFixed(1)}</span> ev/s
          </span>
        </div>

        {/* Toolbar */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <FilterChip label="ALL"  active={filter === 'all'}  onClick={() => setFilter('all')} />
          <FilterChip label="VAD"  active={filter === 'vad'}  color={SELF_COLOR}   onClick={() => setFilter('vad')} />
          <FilterChip label="TURN" active={filter === 'turn'} color={REMOTE_COLOR}  onClick={() => setFilter('turn')} />
          <div style={{ width: 1, height: 16, background: 'rgba(255,255,255,0.10)', margin: '0 4px' }} />
          <FilterChip label={paused ? 'RESUME' : 'PAUSE'} muted active={paused} onClick={togglePause} />
          <FilterChip label="COPY" muted onClick={handleCopy} />
        </div>
      </div>

      {/* Stats strip — 4 columns */}
      <div style={{ padding: '18px 28px', borderBottom: '1px solid rgba(255,255,255,0.06)', display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 18, flexShrink: 0 }}>
        <Stat label="EMITTED" value={counts.total} hint="this session" />
        <Stat label="VAD"  value={counts.vad}  accent={SELF_COLOR} />
        <Stat label="TURN" value={counts.turn} accent={REMOTE_COLOR} />
        <Stat label="RTT"  value={`${rttMs} ms`} hint="peer round-trip" />
      </div>

      {/* Legend row */}
      <div style={{ padding: '12px 28px', borderBottom: '1px solid rgba(255,255,255,0.06)', display: 'flex', alignItems: 'center', gap: 18, fontSize: 10.5, color: '#60656d', letterSpacing: 0.4, flexShrink: 0 }} className="font-mono">
        <span style={{ color: '#3f444b' }}>TURN PROB</span>
        {([{ c: '#FF6B8A', l: '< 0.20 not done' }, { c: '#FFB547', l: '0.20 – 0.65 likely' }, { c: '#3DDC97', l: '≥ 0.65 done' }] as const).map(({ c, l }) => (
          <span key={l} style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
            <span style={{ width: 6, height: 6, borderRadius: 1.5, background: c, flexShrink: 0 }} />
            {l}
          </span>
        ))}
        {/* Solo mode: append SCOPE indicator right-aligned */}
        {solo && (
          <span style={{ marginLeft: 'auto', display: 'inline-flex', alignItems: 'center', gap: 6, color: SELF_COLOR }}>
            <span style={{ width: 5, height: 5, borderRadius: '50%', background: SELF_COLOR, flexShrink: 0 }} />
            self-stream only · solo session
          </span>
        )}
      </div>

      {/* Column headers */}
      <div style={{ padding: '12px 28px 10px', display: 'grid', gridTemplateColumns: '130px 70px 160px 1fr 80px', alignItems: 'center', gap: 14, fontSize: 10, color: '#3f444b', letterSpacing: 0.6, borderBottom: '1px solid rgba(255,255,255,0.06)', flexShrink: 0 }} className="font-mono">
        <span>TIMESTAMP</span>
        <span>TYPE</span>
        <span>PARTICIPANT</span>
        <span>PAYLOAD</span>
        <span style={{ textAlign: 'right' }}>&#916;t</span>
      </div>

      {/* Scrollable event list */}
      <div style={{ flex: 1, minHeight: 0, overflow: 'hidden', position: 'relative' }}>
        <div
          ref={scrollRef}
          onScroll={handleScroll}
          style={{ position: 'absolute', inset: 0, padding: '4px 0', overflowY: 'auto' }}
        >
          {visibleEvents.map((ev, i) => {
            const prev = visibleEvents[i - 1];
            let dt: number | null = null;
            if (prev) {
              try {
                // Parse HH:MM:SS.mmm into ms from midnight
                const toMs = (t: string) => {
                  const [hms, ms = '000'] = t.split('.');
                  const [h, m, s] = hms.split(':').map(Number);
                  return h * 3600000 + m * 60000 + s * 1000 + parseInt(ms, 10);
                };
                dt = toMs(ev.t) - toMs(prev.t);
              } catch { /* ignore malformed timestamps */ }
            }
            return <EventRow key={`${ev.t}-${ev.participantId}-${ev.type}`} event={ev} selfId={selfId} dt={dt} />;
          })}

          {/* Live cursor */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '8px 28px', fontSize: 11, color: '#60656d' }} className="font-mono">
            <span style={{ display: 'inline-block', width: 7, height: 13, background: ACCENT, opacity: 0.7 }} className="animate-live-pulse" />
            <span>waiting for next event…</span>
          </div>
          <div ref={endRef} />
        </div>

        {/* Top fade */}
        <div style={{ position: 'absolute', left: 0, right: 0, top: 0, height: 24, background: 'linear-gradient(180deg, #101216, transparent)', pointerEvents: 'none' }} />
      </div>

      {/* Footer */}
      <div style={{ padding: '12px 28px', borderTop: '1px solid rgba(255,255,255,0.06)', display: 'flex', alignItems: 'center', justifyContent: 'space-between', fontSize: 11, color: '#60656d', flexShrink: 0 }} className="font-mono">
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          <Dot color={ACCENT} size={4} pulse={autoScroll && !paused} />
          <span style={{ color: '#9aa0a6' }}>auto-scroll</span>
          <span style={{ color: '#3f444b' }}>·</span>
          <span>{paused ? `paused · ↓ ${pendingCount} new` : autoScroll ? 'tailing newest' : 'scrolled up'}</span>
        </span>
        <span>
          buffer <span style={{ color: '#e7e9ec' }}>{events.length}</span> / 5000
          {callStartedAt && (
            <> · since <span style={{ color: '#e7e9ec' }}>{formatTime(callStartedAt)}</span></>
          )}
        </span>
      </div>
    </aside>
  );
}
