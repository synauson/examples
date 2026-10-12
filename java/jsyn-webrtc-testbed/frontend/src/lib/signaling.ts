import type { ClientMessage, ServerMessage } from './types';

export type SignalingHandlers = {
  onMessage(msg: ServerMessage): void;
  onOpen?(): void;
  onClose?(reason: CloseEvent): void;
  onError?(err: Event): void;
};

/**
 * Thin wrapper around the browser WebSocket that types both directions
 * against the wire protocol. Exposes a single send() method that JSON-
 * serialises and a connect()-on-construction lifecycle with manual close().
 *
 * No reconnect logic — for a 1:1 testbed, the user just refreshes the
 * page. The recreate-on-rejoin code on the server handles that scenario.
 */
export class Signaling {
  private socket: WebSocket;

  constructor(participantId: string, handlers: SignalingHandlers) {
    // Build the WS URL relative to the page so dev (vite proxy) and prod
    // (Spring Boot on same origin) both work without config.
    const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const url = `${scheme}://${window.location.host}/ws/room/${encodeURIComponent(participantId)}`;
    this.socket = new WebSocket(url);

    this.socket.addEventListener('open', () => handlers.onOpen?.());
    this.socket.addEventListener('close', e => handlers.onClose?.(e));
    this.socket.addEventListener('error', e => handlers.onError?.(e));
    this.socket.addEventListener('message', e => {
      try {
        const parsed = JSON.parse(e.data) as ServerMessage;
        handlers.onMessage(parsed);
      } catch (err) {
        console.warn('Bad WS frame from server', err, e.data);
      }
    });
  }

  send(msg: ClientMessage): void {
    if (this.socket.readyState !== WebSocket.OPEN) {
      console.warn('Signaling.send: socket not open, dropping', msg.type);
      return;
    }
    this.socket.send(JSON.stringify(msg));
  }

  close(): void {
    if (this.socket.readyState === WebSocket.OPEN ||
        this.socket.readyState === WebSocket.CONNECTING) {
      this.socket.close(1000, 'leaving');
    }
  }
}
