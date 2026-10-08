import type { Signaling } from './signaling';
import type { ConnectionStats } from './types';

export type PeerHandlers = {
  onRemoteStream(stream: MediaStream): void;
  onLocalStream(stream: MediaStream): void;
  onConnectionState(state: RTCPeerConnectionState): void;
};

export class Peer {
  private pc: RTCPeerConnection;
  private localStream: MediaStream | null = null;

  constructor(
    private signaling: Signaling,
    private handlers: PeerHandlers,
    iceServers: RTCIceServer[] = [{ urls: 'stun:stun.l.google.com:19302' }]
  ) {
    this.pc = new RTCPeerConnection({ iceServers });

    this.pc.addEventListener('icecandidate', (e) => {
      if (e.candidate) {
        signaling.send({
          type: 'ice_candidate',
          candidate: e.candidate.candidate,
          sdpMLineIndex: e.candidate.sdpMLineIndex ?? 0,
        });
      }
    });

    this.pc.addEventListener('track', (e) => {
      if (e.streams[0]) handlers.onRemoteStream(e.streams[0]);
    });

    this.pc.addEventListener('connectionstatechange', () =>
      handlers.onConnectionState(this.pc.connectionState));
  }

  /** Acquire mic (optionally with a specific deviceId), attach to peer connection, send SDP offer. */
  async join(micId?: string): Promise<void> {
    const audioConstraint = micId
      ? { deviceId: { exact: micId } }
      : true;
    this.localStream = await navigator.mediaDevices.getUserMedia({
      audio: audioConstraint, video: false,
    });
    this.handlers.onLocalStream(this.localStream);
    for (const track of this.localStream.getAudioTracks()) {
      this.pc.addTrack(track, this.localStream);
    }
    const offer = await this.pc.createOffer();
    await this.pc.setLocalDescription(offer);
    this.signaling.send({ type: 'sdp_offer', sdp: offer.sdp ?? '' });
  }

  /** Swap the audio sender's track without tearing down the peer connection. */
  async replaceTrack(newTrack: MediaStreamTrack): Promise<void> {
    const sender = this.pc.getSenders().find(s => s.track?.kind === 'audio');
    if (!sender) return;
    await sender.replaceTrack(newTrack);
    this.localStream?.getAudioTracks().forEach(t => {
      if (t !== newTrack) t.stop();
    });
    this.localStream = new MediaStream([newTrack]);
    this.handlers.onLocalStream(this.localStream);
  }

  /** Poll RTCPeerConnection.getStats() for the nominated candidate pair RTT. */
  async getStats(): Promise<ConnectionStats> {
    let rttMs = 0;
    try {
      const report = await this.pc.getStats();
      for (const entry of report.values()) {
        if (
          entry.type === 'candidate-pair' &&
          entry.nominated &&
          entry.state === 'succeeded' &&
          typeof entry.currentRoundTripTime === 'number'
        ) {
          rttMs = Math.round(entry.currentRoundTripTime * 1000);
          break;
        }
      }
    } catch { /* best-effort */ }
    return {
      rttMs,
      iceConnectionState: this.pc.iceConnectionState,
      connectionState: this.pc.connectionState,
      inboundTracks: this.pc.getReceivers().filter(r => r.track).length,
      outboundTracks: this.pc.getSenders().filter(s => s.track).length,
    };
  }

  async applyAnswer(sdp: string): Promise<void> {
    await this.pc.setRemoteDescription({ type: 'answer', sdp });
  }

  async applyRemoteIce(candidate: string, sdpMLineIndex: number, endOfCandidates: boolean): Promise<void> {
    if (endOfCandidates) {
      try { await this.pc.addIceCandidate(null as unknown as RTCIceCandidateInit); }
      catch { /* ignore */ }
      return;
    }
    try {
      await this.pc.addIceCandidate({ candidate, sdpMLineIndex });
    } catch (err) {
      console.warn('addIceCandidate failed', err);
    }
  }

  setMicMuted(muted: boolean): void {
    this.localStream?.getAudioTracks().forEach(t => t.enabled = !muted);
  }

  close(): void {
    this.localStream?.getTracks().forEach(t => t.stop());
    this.pc.close();
  }
}
