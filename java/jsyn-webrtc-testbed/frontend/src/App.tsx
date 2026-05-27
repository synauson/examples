import { useCallback, useEffect, useRef, useState } from 'react';
import { LobbyScreen } from './screens/LobbyScreen';
import { CallScreen } from './screens/CallScreen';
import { Signaling } from './lib/signaling';
import { Peer } from './lib/peer';
import { EventLog } from './lib/eventLog';
import { useDevices } from './hooks/useDevices';
import { useConnectionStats } from './hooks/useConnectionStats';
import type { EventRecord, RosterEntry, ServerMessage } from './lib/types';

const STORAGE_MIC_KEY = 'synauson-mic-id';
const STORAGE_OUT_KEY = 'synauson-out-id';

export default function App() {
  const path = window.location.pathname;
  const match = path.match(/^\/room\/([^/]+)$/);
  const participantId = match ? decodeURIComponent(match[1]) : null;

  if (!participantId) {
    return (
      <main className="min-h-screen flex items-center justify-center text-ink-3 font-mono text-sm">
        open <span className="text-ink-1 px-1">/room/&lt;your-id&gt;</span> to join the testbed
      </main>
    );
  }

  return <Room participantId={participantId} />;
}

function Room({ participantId }: { participantId: string }) {
  const [phase, setPhase] = useState<'lobby' | 'connecting' | 'live' | 'ended'>('lobby');
  const [displayName, setDisplayName] = useState(participantId);
  const [localStream, setLocalStream] = useState<MediaStream | null>(null);
  const [remoteStream, setRemoteStream] = useState<MediaStream | null>(null);
  const [roster, setRoster] = useState<RosterEntry[]>([]);
  const [micMuted, setMicMuted] = useState(false);
  const [speaking, setSpeaking] = useState<Record<string, boolean>>({});
  const [callStartedAt, setCallStartedAt] = useState<Date | null>(null);
  const [, forceRender] = useState(0);

  const [selectedMicId, setSelectedMicId] = useState<string>(localStorage.getItem(STORAGE_MIC_KEY) ?? '');
  const [selectedOutputId, setSelectedOutputId] = useState<string>(localStorage.getItem(STORAGE_OUT_KEY) ?? '');

  const { inputs: availableInputs, outputs: availableOutputs, refresh: refreshDevices } = useDevices();

  const eventLogRef = useRef(new EventLog());
  const signalingRef = useRef<Signaling | null>(null);
  const peerRef = useRef<Peer | null>(null);

  const connectionStats = useConnectionStats(peerRef.current, phase === 'live');

  useEffect(() => eventLogRef.current.subscribe(() => forceRender(c => c + 1)), []);

  // WebSocket connect
  useEffect(() => {
    const sig = new Signaling(participantId, {
      onMessage: handleServerMessage,
      onClose: () => setPhase('ended'),
    });
    signalingRef.current = sig;
    return () => sig.close();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [participantId]);

  // Lobby mic preview — acquire once on lobby entry, using the stored preferred deviceId.
  // selectedMicId is intentionally omitted from deps: we only want this to run once per
  // lobby entry, not re-fire every time the user changes the picker (onMicChange handles that).
  useEffect(() => {
    if (phase !== 'lobby' || localStream) return;
    const constraints: MediaStreamConstraints = {
      audio: selectedMicId ? { deviceId: { exact: selectedMicId } } : true,
      video: false,
    };
    navigator.mediaDevices.getUserMedia(constraints)
      .then(s => { setLocalStream(s); refreshDevices(); })
      .catch(err => console.warn('mic acquisition failed', err));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [phase, localStream, refreshDevices]);

  function handleServerMessage(msg: ServerMessage) {
    switch (msg.type) {
      case 'roster':
        setRoster(msg.participants);
        break;
      case 'sdp_answer':
        peerRef.current?.applyAnswer(msg.sdp);
        setPhase('live');
        setCallStartedAt(new Date());
        break;
      case 'ice_candidate':
        peerRef.current?.applyRemoteIce(msg.candidate, msg.sdpMLineIndex, msg.endOfCandidates);
        break;
      case 'vad': {
        eventLogRef.current.append(msg as EventRecord);
        setSpeaking(prev => ({ ...prev, [msg.participantId]: msg.kind === 'speech_start' }));
        break;
      }
      case 'turn':
        eventLogRef.current.append(msg as EventRecord);
        break;
      case 'error':
        console.warn('Server error', msg.code, msg.message);
        break;
    }
  }

  const onMicChange = useCallback(async (deviceId: string) => {
    setSelectedMicId(deviceId);
    localStorage.setItem(STORAGE_MIC_KEY, deviceId);

    const newStream = await navigator.mediaDevices.getUserMedia({
      audio: { deviceId: { exact: deviceId } }, video: false,
    }).catch(err => { console.warn('mic switch failed', err); return null; });
    if (!newStream) return;

    const newTrack = newStream.getAudioTracks()[0];
    if (peerRef.current) {
      await peerRef.current.replaceTrack(newTrack);
    } else {
      localStream?.getTracks().forEach(t => t.stop());
      setLocalStream(newStream);
    }
    refreshDevices();
  }, [localStream, refreshDevices]);

  // Just stores the preference. CallScreen owns the <audio> element and calls setSinkId itself.
  const onOutputChange = useCallback((deviceId: string) => {
    setSelectedOutputId(deviceId);
    localStorage.setItem(STORAGE_OUT_KEY, deviceId);
  }, []);

  const onJoin = useCallback(async () => {
    if (!signalingRef.current) return;
    setPhase('connecting');
    const peer = new Peer(signalingRef.current, {
      onLocalStream: setLocalStream,
      onRemoteStream: setRemoteStream,
      onConnectionState: (s) => {
        if (s === 'failed' || s === 'disconnected') setPhase('ended');
      },
    });
    peerRef.current = peer;
    try {
      await peer.join(selectedMicId || undefined);
    } catch (err) {
      console.error('peer.join failed', err);
      setPhase('lobby');
    }
  }, [selectedMicId]);

  const onLeave = useCallback(() => {
    signalingRef.current?.send({ type: 'leave' });
    peerRef.current?.close();
    setPhase('ended');
  }, []);

  const onToggleMute = useCallback(() => {
    setMicMuted(m => {
      peerRef.current?.setMicMuted(!m);
      return !m;
    });
  }, []);

  const events = eventLogRef.current.snapshot();
  const counts = eventLogRef.current.counts();
  const rate = eventLogRef.current.rate();

  if (phase === 'lobby' || phase === 'connecting') {
    return (
      <LobbyScreen
        participantId={participantId}
        roster={roster}
        localStream={localStream}
        displayName={displayName}
        setDisplayName={setDisplayName}
        availableInputs={availableInputs}
        availableOutputs={availableOutputs}
        selectedMicId={selectedMicId}
        selectedOutputId={selectedOutputId}
        onMicChange={onMicChange}
        onOutputChange={onOutputChange}
        onJoin={onJoin}
      />
    );
  }

  return (
    <CallScreen
      selfId={participantId}
      roster={roster}
      localStream={localStream}
      remoteStream={remoteStream}
      events={events}
      rate={rate}
      counts={counts}
      speaking={speaking}
      micMuted={micMuted}
      availableInputs={availableInputs}
      availableOutputs={availableOutputs}
      selectedMicId={selectedMicId}
      selectedOutputId={selectedOutputId}
      onMicChange={onMicChange}
      onOutputChange={onOutputChange}
      onToggleMute={onToggleMute}
      onLeave={onLeave}
      connectionStats={connectionStats}
      callStartedAt={callStartedAt}
    />
  );
}
