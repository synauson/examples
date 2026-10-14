"""Reads a participant's agent event stream and keeps reading it across disconnects."""

from __future__ import annotations

import asyncio
import logging
from collections.abc import AsyncGenerator

import grpc
from synauson.v1 import agent_pb2 as agent
from synauson.v1 import synauson_pb2_grpc

from .rpc import error_reason

log = logging.getLogger(__name__)

# How often the server sends a heartbeat when nothing else happens, and how many of
# them may go missing before the stream counts as dead. A connection can die without
# an error (a NAT or a proxy that drops it silently); the heartbeats are how the agent
# notices, rather than gRPC keepalive pings, which a proxy in between may refuse.
HEARTBEAT_MS = 1000
MISSED_HEARTBEATS = 3

# Failures with an ErrorInfo, by reason: resume from the cursor (this reader fell 256
# events behind), or start afresh (the cursor is older than the 512 events the stream
# keeps, or the participant was added again and has a new stream).
_RESUME_REASONS = {"AGENT_SUBSCRIBER_LAGGED"}
_RESTART_REASONS = {"AGENT_REPLAY_EXPIRED", "AGENT_STREAM_MISMATCH"}
# Failures without one come from the gRPC library, not the server: the connection broke
# or its keepalive timed out (UNAVAILABLE), or something between reset the stream
# (CANCELLED for an HTTP/2 CANCEL, INTERNAL for the other reset codes). The server's own
# errors always carry an ErrorInfo, so a code alone is safe to resume on here.
_TRANSPORT_CODES = {
    grpc.StatusCode.UNAVAILABLE,
    grpc.StatusCode.CANCELLED,
    grpc.StatusCode.INTERNAL,
}


class StreamDead(Exception):
    """No event, not even a heartbeat, for MISSED_HEARTBEATS intervals."""


def is_stored(event: agent.AgentEvent) -> bool:
    """Whether the event has a seq of its own: Subscribed and Heartbeat repeat the last."""
    return event.WhichOneof("event") not in ("subscribed", "heartbeat")


class AgentStream:
    """A participant's agent events, each stored event exactly once and in order.

    After a disconnect it subscribes again with the last seq it handed out as the
    cursor (``after_seq``, with its ``stream_id``), so nothing is lost or repeated. When
    the server no longer keeps the events after the cursor, it starts afresh and skips
    the replayed events it already handed out.
    """

    def __init__(
        self,
        stub: synauson_pb2_grpc.SynAsyncStub,
        conference_id: str,
        participant_id: str,
        retry_for_s: float = 30.0,
    ) -> None:
        self._stub = stub
        self._conference_id = conference_id
        self._participant_id = participant_id
        self._retry_for_s = retry_for_s
        self.last_seq = 0
        self.stream_id: int | None = None
        self.reconnects = 0

    async def events(self) -> AsyncGenerator[agent.AgentEvent, None]:
        """Every event until the stream ends (StreamEnded is the last one).

        Use it as ``async with contextlib.aclosing(stream.events()) as events``: leaving
        an ``async for`` early doesn't close a generator, so without aclosing its RPC
        would run on until the generator is garbage-collected.
        """
        resume = False
        failing_since: float | None = None
        loop = asyncio.get_running_loop()
        while True:
            request = agent.StreamAgentEventsRequest(
                conference_id=self._conference_id,
                participant_id=self._participant_id,
                heartbeat_ms=HEARTBEAT_MS,
            )
            if resume and self.stream_id is not None:
                request.after_seq = self.last_seq
                request.stream_id = self.stream_id
            # A long-lived stream takes no deadline: the heartbeats bound each read
            # instead, and the finally cancels it however the loop ends.
            call = self._stub.StreamAgentEvents(request, wait_for_ready=True)
            try:
                while True:
                    event = await _read(call)
                    if event is None:
                        return  # the server completed the stream
                    failing_since = None
                    if not is_stored(event):
                        yield event
                        continue
                    if event.stream_id != self.stream_id:
                        # The participant's first stream, or a new one after it was
                        # added again: seqs start from 1.
                        self.stream_id = event.stream_id
                        self.last_seq = 0
                    if event.seq <= self.last_seq:
                        continue  # replayed after a fresh start; handed out already
                    self.last_seq = event.seq
                    yield event
                    if event.WhichOneof("event") == "stream_ended":
                        return
            except (grpc.aio.AioRpcError, StreamDead) as e:
                why = _why(e)
                if why == "restart":
                    resume = False  # start afresh; the seq check skips what we had
                elif why == "resume":
                    resume = True
                else:
                    raise
                now = loop.time()
                failing_since = failing_since or now
                if now - failing_since > self._retry_for_s:
                    raise
                self.reconnects += 1
                log.warning(
                    "agent stream lost (%s); %s after seq %d", _describe(e), why, self.last_seq
                )
                await asyncio.sleep(0.2)
            finally:
                call.cancel()  # idempotent; ends the RPC when we stop reading early


async def _read(
    call: grpc.aio.UnaryStreamCall[agent.StreamAgentEventsRequest, agent.AgentEvent],
) -> agent.AgentEvent | None:
    """The stream's next event, or None at its end; StreamDead after a silence."""
    try:
        async with asyncio.timeout(HEARTBEAT_MS * MISSED_HEARTBEATS / 1000):
            event = await call.read()
    except TimeoutError:
        raise StreamDead(f"nothing for {MISSED_HEARTBEATS} heartbeats") from None
    if event is grpc.aio.EOF:
        return None
    assert isinstance(event, agent.AgentEvent)
    return event


def _why(e: grpc.aio.AioRpcError | StreamDead) -> str:
    """ "resume", "restart" or "fail": what a failed subscription calls for."""
    if isinstance(e, StreamDead):
        return "resume"
    reason = error_reason(e)
    if reason in _RESUME_REASONS:
        return "resume"
    if reason in _RESTART_REASONS:
        return "restart"
    if reason is None and e.code() in _TRANSPORT_CODES:
        return "resume"
    return "fail"


def _describe(e: grpc.aio.AioRpcError | StreamDead) -> str:
    if isinstance(e, StreamDead):
        return str(e)
    return error_reason(e) or e.code().name
