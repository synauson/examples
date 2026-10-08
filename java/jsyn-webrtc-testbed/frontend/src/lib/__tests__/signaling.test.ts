import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Signaling } from '../signaling';
import type { ServerMessage } from '../types';

class MockWebSocket {
  static OPEN = 1;
  static CLOSED = 3;
  readyState = MockWebSocket.OPEN;
  sent: string[] = [];
  listeners: Record<string, ((e: any) => void)[]> = {};
  constructor(public url: string) {}
  addEventListener(t: string, fn: (e: any) => void) {
    (this.listeners[t] ||= []).push(fn);
  }
  send(payload: string) { this.sent.push(payload); }
  close() { this.readyState = MockWebSocket.CLOSED; }
  fire(type: string, e: any) { this.listeners[type]?.forEach(fn => fn(e)); }
}

describe('Signaling', () => {
  let originalWs: any;
  let lastMock: MockWebSocket;

  beforeEach(() => {
    originalWs = (globalThis as any).WebSocket;
    (globalThis as any).WebSocket = class extends MockWebSocket {
      constructor(url: string) { super(url); lastMock = this as any; }
    };
    Object.defineProperty(window, 'location', {
      value: { protocol: 'http:', host: 'example:8080' },
      writable: true,
    });
  });

  afterEach(() => { (globalThis as any).WebSocket = originalWs; });

  it('opens against /ws/room/<encoded-pid>', () => {
    new Signaling('alice', { onMessage: () => {} });
    expect(lastMock.url).toBe('ws://example:8080/ws/room/alice');
  });

  it('URL-encodes the participant id', () => {
    new Signaling('alice bob', { onMessage: () => {} });
    expect(lastMock.url).toBe('ws://example:8080/ws/room/alice%20bob');
  });

  it('serialises outbound messages to JSON', () => {
    const s = new Signaling('alice', { onMessage: () => {} });
    s.send({ type: 'sdp_offer', sdp: 'v=0' });
    expect(JSON.parse(lastMock.sent[0])).toEqual({ type: 'sdp_offer', sdp: 'v=0' });
  });

  it('routes incoming JSON to onMessage as a typed ServerMessage', () => {
    const onMessage = vi.fn();
    new Signaling('alice', { onMessage });
    lastMock.fire('message', { data: JSON.stringify({ type: 'sdp_answer', sdp: 'v=0' }) });
    const received: ServerMessage = onMessage.mock.calls[0][0];
    expect(received.type).toBe('sdp_answer');
  });
});
