import { useEffect, useState } from 'react';
import { Wordmark, Dot, Icon, Avatar, MicMeter } from '../components/primitives';
import { RingHalo, Waveform } from '../components/SpeakerTile';
import { PillBtn, Field, DeviceSelect } from '../components/forms';
import { useAudioMeter } from '../lib/useAudioMeter';
import type { RosterEntry } from '../lib/types';

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

export type LobbyScreenProps = {
  participantId: string;
  roster: RosterEntry[];
  localStream: MediaStream | null;
  displayName: string;
  setDisplayName: (s: string) => void;
  availableInputs: MediaDeviceInfo[];
  availableOutputs: MediaDeviceInfo[];
  selectedMicId: string;
  selectedOutputId: string;
  onMicChange: (deviceId: string) => void;
  onOutputChange: (deviceId: string) => void;
  onJoin: () => void;
};

export function LobbyScreen({
  participantId, roster, localStream, displayName, setDisplayName,
  availableInputs, availableOutputs, selectedMicId, selectedOutputId,
  onMicChange, onOutputChange, onJoin,
}: LobbyScreenProps) {
  const meter = useAudioMeter(localStream, 80);
  const [micEnabled, setMicEnabled] = useState(true);

  useEffect(() => {
    localStream?.getAudioTracks().forEach(t => { t.enabled = micEnabled; });
  }, [micEnabled, localStream]);

  const peer = roster.find(r => r.participantId !== participantId);

  return (
    <div style={{ width: '100%', height: '100%', background: '#0a0b0d', color: '#e7e9ec', display: 'flex', flexDirection: 'column', position: 'relative', overflow: 'hidden' }} className="font-sans">
      <div style={{ position: 'absolute', inset: 0, opacity: 0.5, pointerEvents: 'none', background: `radial-gradient(80% 50% at 50% 0%, ${hexA(ACCENT, 0.06)} 0%, transparent 60%), radial-gradient(60% 40% at 100% 100%, ${hexA(SELF_COLOR, 0.04)} 0%, transparent 60%)` }} />

      {/* Top bar */}
      <div style={{ height: 56, padding: '0 24px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', borderBottom: '1px solid rgba(255,255,255,0.06)', position: 'relative', zIndex: 1 }}>
        <Wordmark />
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: '#9aa0a6' }}>
          <Dot color={ACCENT} pulse />
          <span className="font-mono">edge · sfo-2</span>
          <span style={{ color: '#3f444b' }}>·</span>
          <span className="font-mono">28 ms</span>
        </div>
      </div>

      {/* Main grid: 1.25fr left | 1fr right */}
      <div style={{ flex: 1, display: 'grid', gridTemplateColumns: '1.25fr 1fr', position: 'relative', zIndex: 1 }}>

        {/* LEFT — mic check */}
        <div style={{ padding: '40px 40px 40px 56px', display: 'flex', flexDirection: 'column', justifyContent: 'center' }}>
          <div style={{ fontSize: 12, color: '#60656d', letterSpacing: 0.5, marginBottom: 14 }} className="font-mono">
            ROOM &nbsp;·&nbsp; <span style={{ color: '#e7e9ec' }}>{participantId}</span>
          </div>
          <h1 style={{ fontSize: 36, fontWeight: 600, letterSpacing: -0.8, margin: 0, lineHeight: 1.1 }}>
            Check your mic<br />
            <span style={{ color: '#60656d' }}>before you join.</span>
          </h1>

          {/* Preview tile */}
          <div style={{ marginTop: 32, borderRadius: 16, overflow: 'hidden', background: '#101216', border: '1px solid rgba(255,255,255,0.06)', padding: '28px 32px', display: 'flex', flexDirection: 'column', gap: 24 }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 10px', background: hexA(ACCENT, 0.1), border: `1px solid ${hexA(ACCENT, 0.25)}`, borderRadius: 999, fontSize: 11, color: ACCENT, letterSpacing: 0.4 }} className="font-mono">
                <Dot color={ACCENT} size={5} pulse />
                <span>LIVE PREVIEW</span>
              </div>
              <span style={{ fontSize: 11, color: '#60656d' }} className="font-mono">
                input <span style={{ color: '#e7e9ec' }}>-18 dBFS</span> &nbsp;·&nbsp; noise <span style={{ color: '#e7e9ec' }}>-52 dBFS</span>
              </span>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 14, padding: '12px 0' }}>
              <div style={{ position: 'relative', width: 84, height: 84, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                <RingHalo color={SELF_COLOR} size={84} speaking={micEnabled} />
                <Avatar name={displayName || participantId} color={SELF_COLOR} size={84} />
              </div>
              <div style={{ textAlign: 'center' }}>
                <div style={{ fontSize: 16, fontWeight: 600, letterSpacing: -0.2, color: '#e7e9ec' }}>{displayName || participantId}</div>
                <div style={{ fontSize: 11, color: '#60656d', marginTop: 4 }} className="font-mono">say something — we'll show your levels</div>
              </div>
            </div>

            <Waveform bars={80} color={SELF_COLOR} height={52} gap={3} barWidth={3} seed={4.7} levels={meter?.bars} />
          </div>

          {/* Mic on toggle — "Test playback" removed per spec */}
          <div style={{ marginTop: 14, display: 'flex', alignItems: 'center', gap: 10 }}>
            <PillBtn
              icon={micEnabled ? 'mic' : 'mic-off'}
              label={micEnabled ? 'Mic on' : 'Mic off'}
              active={micEnabled}
              onClick={() => setMicEnabled(e => !e)}
            />
          </div>
        </div>

        {/* RIGHT — form */}
        <div style={{ padding: '40px 56px 40px 32px', display: 'flex', flexDirection: 'column', justifyContent: 'center', gap: 22 }}>
          {/* Presence card */}
          <div style={{ padding: '16px 18px', background: '#101216', border: '1px solid rgba(255,255,255,0.06)', borderRadius: 12 }}>
            <div style={{ fontSize: 11, color: '#60656d', letterSpacing: 0.6, marginBottom: 10 }} className="font-mono">IN&nbsp;THE&nbsp;ROOM</div>
            {peer ? (
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <Avatar name={peer.participantId} color={REMOTE_COLOR} size={36} />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 14, fontWeight: 500, color: '#e7e9ec' }}>{peer.participantId}</div>
                  <div style={{ marginTop: 2, display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: '#9aa0a6' }}>
                    <Dot color={ACCENT} size={5} pulse />
                    waiting for you · joined {formatJoinedAgo(peer.joinedAt)}
                  </div>
                </div>
                <Icon name="check" size={14} />
              </div>
            ) : (
              <div style={{ fontSize: 13, color: '#60656d' }} className="font-mono">nobody else here yet</div>
            )}
          </div>

          {/* Display name */}
          <Field label="Display name">
            <input
              value={displayName}
              onChange={e => setDisplayName(e.target.value)}
              style={{ width: '100%', height: 42, padding: '0 14px', background: '#101216', border: '1px solid rgba(255,255,255,0.10)', borderRadius: 10, color: '#e7e9ec', fontSize: 14, outline: 'none', boxSizing: 'border-box' }}
              className="font-sans"
            />
          </Field>

          {/* Microphone picker — MicMeter samples localStream (App swaps it on onMicChange) */}
          <Field label="Microphone" meta={<MicMeter levels={meter?.bars?.slice(0, 9) ?? Array(9).fill(0.1)} active={!!localStream && micEnabled} />}>
            <DeviceSelect devices={availableInputs} value={selectedMicId} onChange={onMicChange} />
          </Field>

          {/* Output picker */}
          <Field label="Output">
            <DeviceSelect devices={availableOutputs} value={selectedOutputId} onChange={onOutputChange} />
          </Field>

          {/* Join */}
          <button
            onClick={onJoin}
            style={{ marginTop: 6, height: 48, borderRadius: 10, border: 'none', cursor: 'pointer', background: ACCENT, color: '#062a1b', fontSize: 15, fontWeight: 600, letterSpacing: -0.1, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10, boxShadow: `0 1px 0 0 rgba(255,255,255,0.18) inset, 0 0 0 1px ${hexA(ACCENT, 0.4)}, 0 8px 24px ${hexA(ACCENT, 0.18)}` }}
            className="font-sans"
          >
            Join the room
            <span className="font-mono" style={{ fontSize: 12, opacity: 0.65 }}>⏎</span>
          </button>

          <div style={{ fontSize: 11, color: '#60656d', textAlign: 'center' }} className="font-mono">
            your stream will be analyzed for vad &amp; turn-detection events
          </div>
        </div>
      </div>
    </div>
  );
}

function formatJoinedAgo(joinedAt: string): string {
  try {
    const diffMs = Date.now() - new Date(joinedAt).getTime();
    const mins = Math.floor(diffMs / 60_000);
    if (mins < 1) return 'just now';
    return `${mins}m ago`;
  } catch { return 'recently'; }
}
