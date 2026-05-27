import { useMemo } from 'react';
import { Dot, Avatar } from './primitives';

function hexA(hex: string, a: number): string {
  const h = hex.replace('#', '');
  const r = parseInt(h.slice(0, 2), 16);
  const g = parseInt(h.slice(2, 4), 16);
  const b = parseInt(h.slice(4, 6), 16);
  return `rgba(${r},${g},${b},${a})`;
}

function genWaveform(n: number, seed: number): number[] {
  const out = [];
  for (let i = 0; i < n; i++) {
    const x = i / n;
    const v =
      Math.sin(x * 13 + seed) * 0.4 +
      Math.sin(x * 31 + seed * 1.7) * 0.3 +
      Math.sin(x * 5 + seed * 0.5) * 0.2 +
      Math.sin(x * 73 + seed * 2.3) * 0.15;
    out.push(Math.min(1, Math.max(0.04, 0.5 + v * 0.5)));
  }
  return out;
}

export type Participant = {
  id: string;
  name: string;
  handle: string;
  color: string;
};

export function RingHalo({ color, size, speaking = true }: { color: string; size: number; speaking?: boolean }) {
  const rings = [1.18, 1.42, 1.72, 2.08];
  return (
    <div style={{ position: 'absolute', left: '50%', top: '50%', transform: 'translate(-50%, -50%)', width: size, height: size, pointerEvents: 'none' }}>
      {rings.map((scale, i) => (
        <span key={i} style={{
          position: 'absolute', inset: 0, borderRadius: '50%',
          border: `1px solid ${hexA(color, speaking ? 0.18 - i * 0.035 : 0.06)}`,
          background: i === 0 ? `radial-gradient(50% 50% at 50% 50%, ${hexA(color, 0.18)} 0%, transparent 70%)` : 'none',
          transform: `scale(${scale})`,
        }} />
      ))}
    </div>
  );
}

export function Waveform({ bars = 32, color, height = 22, gap = 2, barWidth = 2, levels, muted = false, seed = 1 }: {
  bars?: number; color: string; height?: number; gap?: number; barWidth?: number;
  levels?: number[]; muted?: boolean; seed?: number;
}) {
  const seeded = useMemo(() => genWaveform(bars, seed), [bars, seed]);
  const vals = levels && levels.length > 0 ? levels : seeded;
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap, height, maxWidth: 180, width: '100%' }}>
      {vals.map((v, i) => (
        <span key={i} style={{
          width: barWidth,
          height: Math.max(2, v * height),
          borderRadius: barWidth / 2,
          background: muted ? hexA('#60656d', 0.3) : color,
          opacity: muted ? 0.5 : 0.55 + v * 0.45,
          flexShrink: 0,
        }} />
      ))}
    </div>
  );
}

export function StatusChip({ status, color }: { status: 'speaking' | 'listening' | 'muted'; color: string }) {
  const danger = '#ff5e5e';
  const map = {
    speaking: { label: 'SPEAKING', fg: color,      bg: hexA(color, 0.12),     bd: hexA(color, 0.3),              pulse: true },
    listening:{ label: 'LISTENING',fg: '#9aa0a6',  bg: hexA('#ffffff', 0.04), bd: 'rgba(255,255,255,0.10)',       pulse: false },
    muted:    { label: 'MUTED',    fg: danger,      bg: hexA(danger, 0.1),     bd: hexA(danger, 0.3),             pulse: false },
  };
  const s = map[status] || map.listening;
  return (
    <span
      style={{
        display: 'inline-flex', alignItems: 'center', gap: 6,
        padding: '5px 10px', borderRadius: 999,
        background: s.bg, border: `1px solid ${s.bd}`, color: s.fg,
        fontSize: 10.5, letterSpacing: 0.5,
      }}
      className="font-mono"
      aria-label={`Status: ${s.label}`}
    >
      <Dot color={s.fg} size={5} pulse={s.pulse} />
      {s.label}
    </span>
  );
}

/**
 * SpeakerTile — single-size horizontal audio-rail tile (~96px tall).
 *
 * Row 1: 44px avatar + RingHalo | name + handle | inline waveform (flex:1, max 180px wide).
 * Row 2: StatusChip | codec meta right-aligned.
 *
 * No `big` variant. Both call variants (solo and 2-participant) use this same tile.
 */
export function SpeakerTile({ participant, status, levels, seed }: {
  participant: Participant;
  status: 'speaking' | 'listening' | 'muted';
  levels?: number[];
  seed?: number;
}) {
  const speaking = status === 'speaking';
  const muted = status === 'muted';
  const tileSeed = seed ?? (participant.id.charCodeAt(0) % 10);
  const bitrate = speaking ? '64' : muted ? '0' : '32';

  return (
    <div
      style={{
        position: 'relative',
        background: `linear-gradient(90deg, ${hexA(participant.color, speaking ? 0.08 : 0.025)} 0%, transparent 60%), #101216`,
        border: '1px solid rgba(255,255,255,0.06)',
        borderRadius: 12,
        overflow: 'hidden',
        padding: '14px 16px',
        display: 'flex',
        flexDirection: 'column',
        gap: 12,
      }}
      aria-label={`${participant.name} — ${status}`}
    >
      {/* Row 1: identity + inline waveform */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        {/* Avatar with halo */}
        <div style={{ position: 'relative', width: 44, height: 44, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
          <RingHalo color={participant.color} size={44} speaking={speaking && !muted} />
          <Avatar name={participant.name} color={participant.color} size={44} />
        </div>
        {/* Name + handle */}
        <div style={{ flex: '0 0 auto' }}>
          <div style={{ fontSize: 14, fontWeight: 600, letterSpacing: -0.2, color: '#e7e9ec' }}>{participant.name}</div>
          <div style={{ fontSize: 11, color: '#60656d' }} className="font-mono">{participant.handle}</div>
        </div>
        {/* Waveform — flex:1 right-aligned, capped at 180px */}
        <div style={{ flex: 1, display: 'flex', justifyContent: 'flex-end' }}>
          <Waveform bars={32} color={participant.color} height={22} gap={2} barWidth={2} seed={tileSeed} levels={levels} muted={muted} />
        </div>
      </div>

      {/* Row 2: status chip + codec meta */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <StatusChip status={status} color={participant.color} />
        <span style={{ fontSize: 10, color: '#3f444b', letterSpacing: 0.4 }} className="font-mono">
          opus · 48kHz · {bitrate} kbps
        </span>
      </div>
    </div>
  );
}

/**
 * SoloPeerCard — shown in the audio rail when there are zero remote participants.
 *
 * Displays live RTCPeerConnection state so the engineer can confirm the connection
 * is up and inference detectors are still firing against the local stream.
 */
export function SoloPeerCard({ iceConnectionState, remoteCount, inboundTracks, outboundTracks }: {
  iceConnectionState: RTCIceConnectionState;
  remoteCount: number;
  inboundTracks: number;
  outboundTracks: number;
}) {
  const accent = '#3DDC97';
  const cyan = '#5BD3F5';
  const violet = '#B58CFF';
  const connected = iceConnectionState === 'connected' || iceConnectionState === 'completed';

  return (
    <div style={{
      background: '#14171c',
      border: '1px solid rgba(255,255,255,0.10)',
      borderRadius: 10,
      padding: '12px 14px',
      display: 'flex',
      flexDirection: 'column',
      gap: 8,
    }}>
      {/* Header row */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <span style={{ fontSize: 10, color: '#60656d', letterSpacing: 0.6 }} className="font-mono">RTCPEERCONNECTION</span>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 10, color: connected ? accent : '#ff5e5e', letterSpacing: 0.5 }} className="font-mono">
          <span
            style={{ width: 5, height: 5, borderRadius: '50%', background: connected ? accent : '#ff5e5e', flexShrink: 0 }}
            className={connected ? 'animate-live-pulse' : ''}
          />
          {connected ? 'CONNECTED' : iceConnectionState.toUpperCase()}
        </span>
      </div>

      {/* Stat grid — 2-column auto/1fr */}
      <div style={{ display: 'grid', gridTemplateColumns: 'auto 1fr', rowGap: 5, columnGap: 10, fontSize: 11 }} className="font-mono">
        <span style={{ color: '#60656d' }}>iceState</span>
        <span style={{ color: '#e7e9ec' }}>{iceConnectionState}</span>
        <span style={{ color: '#60656d' }}>peers</span>
        <span style={{ color: '#e7e9ec' }}>{remoteCount} remote · 1 local</span>
        <span style={{ color: '#60656d' }}>tracks</span>
        <span style={{ color: '#e7e9ec' }}>{outboundTracks} outbound · {inboundTracks} inbound</span>
        <span style={{ color: '#60656d' }}>detectors</span>
        <span style={{ display: 'inline-flex', gap: 6 }}>
          {([{ label: 'VAD', color: cyan }, { label: 'TURN', color: violet }] as const).map(({ label, color }) => (
            <span key={label} style={{
              display: 'inline-flex', alignItems: 'center', gap: 4,
              padding: '2px 7px', borderRadius: 4, fontSize: 10, letterSpacing: 0.5,
              background: hexA(color, 0.12), border: `1px solid ${hexA(color, 0.25)}`, color,
            }} className="font-mono">
              <span style={{ width: 4, height: 4, borderRadius: '50%', background: color, flexShrink: 0 }} className="animate-live-pulse" />
              {label}
            </span>
          ))}
        </span>
      </div>

      {/* Footer */}
      <div style={{ borderTop: '1px dashed rgba(255,255,255,0.10)', paddingTop: 6, fontSize: 10.5, color: '#9aa0a6' }} className="font-sans">
        No remote participant. VAD &amp; turn-detection still emit on the local stream — the event panel reflects analysis of your own mic.
      </div>
    </div>
  );
}
