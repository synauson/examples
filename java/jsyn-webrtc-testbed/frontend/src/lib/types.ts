/**
 * Mirror of Java's ClientMessage / ServerMessage sealed hierarchies.
 *
 * The `type` field is the discriminator; TypeScript narrows the union
 * automatically in switch/if statements.
 */

// ---- Client → Server --------------------------------------------------

export type ClientMessage =
  | { type: 'sdp_offer';     sdp: string }
  | { type: 'ice_candidate'; candidate: string; sdpMLineIndex: number }
  | { type: 'leave' };

// ---- Server → Client --------------------------------------------------

export type RosterEntry = {
  participantId: string;
  joinedAt: string;          // ISO-8601 UTC
};

export type VadKind = 'speech_start' | 'speech_end';

export type ServerMessage =
  | { type: 'sdp_answer';    sdp: string }
  | { type: 'ice_candidate'; candidate: string; sdpMLineIndex: number; endOfCandidates: boolean }
  | { type: 'roster';        participants: RosterEntry[] }
  | { type: 'vad';           participantId: string; kind: VadKind; t: string;
                              confidence: number | null; durationMs: number | null }
  | { type: 'turn';          participantId: string; t: string;
                              probability: number; turnComplete: boolean }
  | { type: 'error';         code: string; message: string };

// ---- UI-side derived types --------------------------------------------

/** Convenience supertype for the event-rail list. */
export type EventRecord =
  | (Extract<ServerMessage, { type: 'vad' }>)
  | (Extract<ServerMessage, { type: 'turn' }>);

export type ConnectionStats = {
  rttMs: number;
  iceConnectionState: RTCIceConnectionState;
  connectionState: RTCPeerConnectionState;
  inboundTracks: number;
  outboundTracks: number;
};
