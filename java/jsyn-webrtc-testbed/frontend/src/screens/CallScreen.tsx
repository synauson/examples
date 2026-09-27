import { useEffect, useRef } from 'react';
import { Wordmark, Dot, Icon } from '../components/primitives';
import { SpeakerTile, SoloPeerCard, type Participant } from '../components/SpeakerTile';
import { ControlBtn, DeviceChip } from '../components/forms';
import { EventStream } from '../components/EventStream';
import { useAudioMeter } from '../lib/useAudioMeter';
import { useUptime } from '../hooks/useUptime';
import type { RosterEntry, EventRecord, ConnectionStats } from '../lib/types';

function hexA(hex: string, a: number): string {
  const h = hex.replace('#', '');
  const r = parseInt(h.slice(0, 2), 16);
  const g = parseInt(h.slice(2, 4), 16);
  const b = parseInt(h.slice(4, 6), 16);
  return `rgba(${r},${g},${b},${a})`;
}

const ACCENT = '#3DDC97';
const SELF_COLOR = '#5BD3F5';
const REMOTE_COLOR = '#B58CFF';

// setSinkId is Chromium-only. Disable the output chip in other browsers.
const sinkIdSupported = typeof HTMLAudioElement !== 'undefined' &&
  'setSinkId' in HTMLAudioElement.prototype;

export type CallScreenProps = {
  selfId: string;
  roster: RosterEntry[];
  localStream: MediaStream | null;
  remoteStream: MediaStream | null;
  events: EventRecord[];
  rate: number;
  counts: { total: number; vad: number; turn: number };
  speaking: Record<string, boolean>;
  micMuted: boolean;
  availableInputs: MediaDeviceInfo[];
  availableOutputs: MediaDeviceInfo[];
  selectedMicId: string;
  selectedOutputId: string;
  onMicChange: (deviceId: string) => void;
  onOutputChange: (deviceId: string) => void;
  onToggleMute: () => void;
  onLeave: () => void;
  connectionStats: ConnectionStats;
  callStartedAt: Date | null;
};

export function CallScreen({
  selfId, roster, localStream, remoteStream, events, rate, counts,
  speaking, micMuted, availableInputs, availableOutputs, selectedMicId,
  selectedOutputId, onMicChange, onOutputChange, onToggleMute, onLeave,
  connectionStats, callStartedAt,
}: CallScreenProps) {
  const localMeter  = useAudioMeter(localStream,  32);
  const remoteMeter = useAudioMeter(remoteStream, 32);
  const uptime = useUptime(callStartedAt);

  // <audio> element is owned here — no ref drilling from App.
  const audioRef = useRef<HTMLAudioElement | null>(null);
  // Ref to the output DeviceChip wrapper so the Output button can trigger its hidden <select>.
  const outputChipRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = audioRef.current;
    if (el && remoteStream) el.srcObject = remoteStream;
  }, [remoteStream]);

  // Apply selected output device when it changes (Chromium only).
  useEffect(() => {
    const el = audioRef.current;
    if (!el || !sinkIdSupported || !selectedOutputId) return;
    (el as HTMLAudioElement & { setSinkId(id: string): Promise<void> })
      .setSinkId(selectedOutputId)
      .catch(err => console.warn('setSinkId failed', err));
  }, [selectedOutputId]);

  const selfParticipant: Participant = { id: selfId, name: selfId, handle: selfId, color: SELF_COLOR };
  const remoteEntry = roster.find(r => r.participantId !== selfId);
  const remoteParticipant: Participant | null = remoteEntry
    ? { id: remoteEntry.participantId, name: remoteEntry.participantId, handle: remoteEntry.participantId, color: REMOTE_COLOR }
    : null;

  const solo = !remoteParticipant;
  const selfStatus = micMuted ? 'muted' : speaking[selfId] ? 'speaking' : 'listening';
  const remoteStatus = remoteParticipant
    ? (speaking[remoteParticipant.id] ? 'speaking' : 'listening')
    : 'listening';
  const participantCount = solo ? 1 : 2;

  return (
    <div style={{ width: '100%', height: '100%', background: '#0a0b0d', color: '#e7e9ec', display: 'flex', flexDirection: 'column', overflow: 'hidden' }} className="font-sans">

      {/* Top bar */}
      <div style={{ height: 48, padding: '0 18px 0 20px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', borderBottom: '1px solid rgba(255,255,255,0.06)', background: '#0a0b0d', flexShrink: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
          <Wordmark size={13} />
          <div style={{ width: 1, height: 18, background: 'rgba(255,255,255,0.10)' }} />
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12, color: '#9aa0a6' }} className="font-mono">
            <span style={{ color: '#60656d' }}>room</span>
            <span style={{ color: '#e7e9ec' }}>{selfId}</span>
          </div>
          {/* DEBUG chip */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '3px 8px', borderRadius: 5, background: hexA(ACCENT, 0.1), border: `1px solid ${hexA(ACCENT, 0.25)}`, fontSize: 11, color: ACCENT, letterSpacing: 0.5 }} className="font-mono">
            <Dot color={ACCENT} size={5} pulse />
            DEBUG
          </div>
          {/* SOLO chip — slides in/out so the top bar reshuffles smoothly when a remote joins. */}
          <div style={{
            display: 'inline-flex', alignItems: 'center', overflow: 'hidden',
            maxWidth: solo ? 200 : 0,
            opacity: solo ? 1 : 0,
            transition: 'max-width 320ms cubic-bezier(0.2, 0.8, 0.2, 1), opacity 220ms ease',
          }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 5, padding: '3px 8px', borderRadius: 5, background: hexA(SELF_COLOR, 0.1), border: `1px solid ${hexA(SELF_COLOR, 0.25)}`, fontSize: 11, color: SELF_COLOR, letterSpacing: 0.5, whiteSpace: 'nowrap' }} className="font-mono">
              SOLO · 1 PARTICIPANT
            </div>
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 18, fontSize: 12, color: '#9aa0a6' }} className="font-mono">
          <span><span style={{ color: '#60656d' }}>uptime </span><span style={{ color: '#e7e9ec' }}>{uptime}</span></span>
          <span style={{ color: '#3f444b' }}>·</span>
          <span><span style={{ color: '#60656d' }}>jitter </span><span style={{ color: '#e7e9ec' }}>0ms</span></span>
          <span style={{ color: '#3f444b' }}>·</span>
          <span><span style={{ color: '#60656d' }}>rtt </span><span style={{ color: '#e7e9ec' }}>{connectionStats.rttMs}ms</span></span>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: ACCENT }}>
            <Icon name="signal" size={13} />
            <span style={{ fontSize: 11, letterSpacing: 0.4 }}>STABLE</span>
          </div>
        </div>
      </div>

      {/* Body: 360px audio rail | 1fr event stream */}
      <div style={{ flex: 1, display: 'grid', gridTemplateColumns: '360px 1fr', minHeight: 0 }}>

        {/* Audio rail */}
        <div style={{ borderRight: '1px solid rgba(255,255,255,0.06)', padding: '20px 20px 16px', display: 'flex', flexDirection: 'column', gap: 14, minHeight: 0, overflow: 'hidden' }}>

          {/* Header */}
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', fontSize: 10, letterSpacing: 0.6, flexShrink: 0 }} className="font-mono">
            <span style={{ color: '#60656d' }}>PARTICIPANTS · {participantCount}</span>
            <span style={{ color: '#3f444b' }}>{solo ? '1 local stream' : '1 local · 1 remote'}</span>
          </div>

          {/* Tiles */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10, flexShrink: 0 }}>
            <SpeakerTile participant={selfParticipant} status={selfStatus} levels={localMeter?.bars} />
            {remoteParticipant && (
              // key on participant id so the animation re-fires for each new peer
              <div key={remoteParticipant.id} className="animate-tile-in">
                <SpeakerTile participant={remoteParticipant} status={remoteStatus} levels={remoteMeter?.bars} />
              </div>
            )}
          </div>

          {/* SoloPeerCard — always mounted, collapses via max-height when a remote joins.
              Always-rendered + max-height keeps both directions of the transition smooth
              (collapse on join, expand if the peer ever leaves). */}
          <div style={{
            overflow: 'hidden',
            maxHeight: solo ? 240 : 0,
            opacity: solo ? 1 : 0,
            transition: 'max-height 360ms cubic-bezier(0.2, 0.8, 0.2, 1), opacity 240ms ease',
            flexShrink: 0,
          }}>
            <SoloPeerCard
              iceConnectionState={connectionStats.iceConnectionState}
              remoteCount={remoteParticipant ? 1 : 0}
              inboundTracks={connectionStats.inboundTracks}
              outboundTracks={connectionStats.outboundTracks}
            />
          </div>

          {/* Spacer — pushes control dock to the bottom */}
          <div style={{ flex: 1 }} />

          {/* Control dock — 3 rows */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8, flexShrink: 0 }}>
            {/* Row 1: Mute + Mic chip */}
            <div style={{ display: 'flex', gap: 8 }}>
              <ControlBtn icon={micMuted ? 'mic-off' : 'mic'} label="Mute" onClick={onToggleMute} />
              <DeviceChip devices={availableInputs} value={selectedMicId} onChange={onMicChange} />
            </div>
            {/* Row 2: Output + Output chip */}
            <div style={{ display: 'flex', gap: 8 }}>
              <ControlBtn icon="headphones" label="Output" onClick={() => outputChipRef.current?.querySelector('select')?.click()} />
              <div ref={outputChipRef} style={{ flex: 1, display: 'flex' }}>
                <DeviceChip
                  devices={availableOutputs}
                  value={selectedOutputId}
                  onChange={onOutputChange}
                  disabled={!sinkIdSupported}
                  disabledTooltip="Output device switching not supported in this browser"
                />
              </div>
            </div>
            {/* Row 3: Leave room — full width */}
            <ControlBtn icon="phone-down" label="Leave room" danger fullWidth onClick={onLeave} />
          </div>
        </div>

        {/* Event stream */}
        <EventStream
          events={events}
          selfId={selfId}
          rate={rate}
          counts={counts}
          solo={solo}
          rttMs={connectionStats.rttMs}
          callStartedAt={callStartedAt}
        />
      </div>

      {/* Hidden audio element — owned here, not passed up to App */}
      <audio autoPlay ref={audioRef} style={{ display: 'none' }} />
    </div>
  );
}
