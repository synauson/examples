"""The agent: reads the caller's agent event stream, answers each turn, and stops talking
when the caller talks over it. Everything goes through one participant, the caller's,
which carries VAD, turn detection, STT and a speaker.

The conversation is the program's output, so it is printed; diagnostics go to logging."""

from __future__ import annotations

import asyncio
import contextlib
import logging
from collections.abc import Callable
from dataclasses import dataclass, field

import grpc
from synauson.v1 import agent_pb2 as agent
from synauson.v1 import synauson_pb2_grpc

from . import replies
from .rpc import TIMEOUT_S, enum_name, error_reason
from .stream import AgentStream

log = logging.getLogger(__name__)


@dataclass
class Reply:
    """One of the agent's replies: an utterance on the caller's speaker."""

    utterance_id: str
    turn_id: int  # the caller's turn it answers
    # What it answers: that turn, after any the caller said since they last heard an answer.
    caller_said: str
    confirmed: bool  # the turn's EndOfTurn said it stands (an eager reply waits for that)
    text: str = ""  # what has been sent to the speaker so far
    started: bool = False  # its audio started playing
    done: bool = False  # it played to its end
    withdrawn: bool = False  # stop writing it
    task: asyncio.Task[None] | None = None


@dataclass
class Line:
    """A line of the conversation: what the caller said, or what they heard the agent say."""

    speaker: str
    text: str


@dataclass
class VoiceAgent:
    stub: synauson_pb2_grpc.SynAsyncStub
    conference_id: str
    caller_id: str
    on_event: Callable[[agent.AgentEvent, AgentStream], None] | None = None

    history: list[Line] = field(default_factory=list)  # the conversation, as the caller heard it
    events: list[agent.AgentEvent] = field(default_factory=list)
    replies_by_id: dict[str, Reply] = field(default_factory=dict)
    _newest: Reply | None = None  # the newest reply, while it is unfinished
    _last_done: Reply | None = None  # the reply that last played to its end
    _unfinished: set[str] = field(default_factory=set)  # replies not done, interrupted or failed
    _turns: dict[int, str] = field(default_factory=dict)  # what the caller said, by turn
    _heard_through: int = 0  # the newest turn whose answer the caller started to hear
    _goodbye: str | None = None
    _tasks: asyncio.TaskGroup | None = None  # the reply tasks, while run() runs

    async def run(self) -> None:
        """Runs the conversation until the agent has said goodbye or the stream ends.

        Each reply is written by a task in a TaskGroup, so run() returns only once every
        reply task has ended, and a reply that fails fails the call."""
        stream = AgentStream(self.stub, self.conference_id, self.caller_id)
        async with (
            asyncio.TaskGroup() as self._tasks,
            contextlib.aclosing(stream.events()) as events,
        ):
            try:
                async for event in events:
                    self.events.append(event)
                    if self.on_event:
                        self.on_event(event, stream)
                    if await self._handle(event):
                        return
            finally:
                for reply in self.replies_by_id.values():
                    reply.withdrawn = True  # the reply tasks stop at their next piece

    async def _handle(self, event: agent.AgentEvent) -> bool:
        """Acts on one event; True once the call is over."""
        kind = event.WhichOneof("event")
        if kind == "eager_end_of_turn":
            # The caller has probably finished: start answering now, before turn detection
            # confirms it, so the reply is ready sooner.
            eager = event.eager_end_of_turn
            if eager.text.strip():
                await self._drop_unheard(eager.turn_id)
                self._start_reply(eager.turn_id, eager.text, confirmed=False)
        elif kind == "turn_resumed":
            # The caller went on talking: the early reply no longer fits.
            reply = self._newest
            if reply and reply.turn_id == event.turn_resumed.turn_id and not reply.confirmed:
                await self._withdraw(reply)
        elif kind == "end_of_turn":
            end = event.end_of_turn
            if not end.text.strip():
                return False
            print(f"caller: {end.text}")
            self.history.append(Line("caller", end.text))
            # An early reply may still be playing, or may have played to its end already
            # when turn detection takes its time (the end-of-turn timeout).
            reply = self._newest or self._last_done
            if (
                reply
                and reply.turn_id == end.turn_id
                and reply.caller_said == self._unanswered(end.turn_id, end.text)
            ):
                reply.confirmed = True  # the early reply was the right one
                if reply.done and reply.utterance_id == self._goodbye:
                    return True  # it was goodbye, and the caller heard it all
            else:
                await self._drop_unheard(end.turn_id)
                self._start_reply(end.turn_id, end.text, confirmed=True)
            self._turns[end.turn_id] = end.text
        elif kind == "speech_started" and self._unfinished:
            # Barge-in: the caller talks over the agent. Stop, and keep what they heard.
            await self._barge_in()
        elif kind == "utterance_started":
            reply = self.replies_by_id.get(event.utterance_started.utterance_id)
            if reply:
                reply.started = True
                self._heard_through = max(self._heard_through, reply.turn_id)
        elif kind == "utterance_done":
            reply = self._finished(event.utterance_done.utterance_id)
            if reply:
                reply.done = True
                self._last_done = reply
                self.history.append(Line("agent", reply.text.strip()))
                # Hang up after saying goodbye, once the caller's turn it answered ended.
                if reply.utterance_id == self._goodbye and reply.confirmed:
                    return True
        elif kind == "utterance_interrupted":
            interrupted = event.utterance_interrupted
            self._finished(interrupted.utterance_id)
            if interrupted.utterance_id == self._goodbye:
                self._goodbye = None  # cut off: the call goes on
            if interrupted.heard_text.strip():
                self.history.append(Line("agent", interrupted.heard_text.strip()))
        elif kind == "utterance_failed":
            failed = event.utterance_failed
            raise RuntimeError(
                f"speech failed: {enum_name(agent.UtteranceFailure.Name, failed.code)} "
                f"{failed.message}"
            )
        elif kind == "error":
            # A problem the stream recovers from, such as STT_LAGGING on a busy machine.
            log.warning("engine: %s: %s", event.error.reason, event.error.message)
        elif kind == "stream_ended":
            return True
        return False

    def _unanswered(self, turn_id: int, text: str) -> str:
        """What a reply to this turn answers: the turn, after any the caller said since
        they last heard the agent answer."""
        earlier = [t for n, t in sorted(self._turns.items()) if self._heard_through < n < turn_id]
        return " ".join([*earlier, text])

    def _start_reply(self, turn_id: int, text: str, confirmed: bool) -> None:
        reply = Reply(
            f"reply-{len(self.replies_by_id) + 1}",
            turn_id,
            self._unanswered(turn_id, text),
            confirmed,
        )
        self.replies_by_id[reply.utterance_id] = reply
        self._unfinished.add(reply.utterance_id)
        self._newest = reply
        assert self._tasks is not None, "replies start only while run() runs"
        reply.task = self._tasks.create_task(self._speak(reply))

    async def _speak(self, reply: Reply) -> None:
        """Streams the reply into the speaker as it is written: speech starts after the
        first words, and the rest follows."""
        async for token in replies.stream_reply(reply.caller_said):
            if reply.withdrawn:
                return
            await self._send(reply, token, agent.SPEAK_RELEASE_NONE)  # more follows
            reply.text += token
        if reply.withdrawn:
            return
        await self._send(reply, "", agent.SPEAK_RELEASE_END)
        if replies.is_goodbye(reply.text):
            self._goodbye = reply.utterance_id
        print(f"agent:  {reply.text.strip()}")

    async def _send(self, reply: Reply, text: str, release: agent.SpeakRelease.ValueType) -> None:
        # Speak isn't retry-safe (a retried piece can be spoken twice), so a failure
        # fails the reply; wait_for_ready only holds the call until the channel is up.
        try:
            await self.stub.Speak(
                agent.SpeakRequest(
                    conference_id=self.conference_id,
                    participant_id=self.caller_id,
                    utterance_id=reply.utterance_id,
                    text=text,
                    release=release,
                ),
                timeout=TIMEOUT_S,
                wait_for_ready=True,
            )
        except grpc.aio.AioRpcError as e:
            # The caller talked over it while it was being written.
            if error_reason(e) != "UTTERANCE_ENDED":
                raise
            reply.withdrawn = True

    async def _drop_unheard(self, turn_id: int) -> None:
        """A turn ended: withdraws the newest reply if the caller hasn't heard any of it
        (they said more before it started), or if it was an early answer to this turn."""
        reply = self._newest
        if reply and (not reply.started or (reply.turn_id == turn_id and not reply.confirmed)):
            await self._withdraw(reply)

    async def _withdraw(self, reply: Reply) -> None:
        """Stops writing a reply and stops it on the speaker, playing or not."""
        await self._stop_writing(reply)
        try:
            await self.stub.CancelUtterance(
                agent.CancelUtteranceRequest(
                    conference_id=self.conference_id,
                    participant_id=self.caller_id,
                    utterance_id=reply.utterance_id,
                ),
                timeout=TIMEOUT_S,
                wait_for_ready=True,
            )
        except grpc.aio.AioRpcError as e:
            if error_reason(e) != "UNKNOWN_UTTERANCE":  # it never got to the speaker
                raise
        self._finished(reply.utterance_id)

    async def _barge_in(self) -> None:
        for utterance_id in list(self._unfinished):
            await self._stop_writing(self.replies_by_id[utterance_id])
        response = await self.stub.CancelUtterance(
            agent.CancelUtteranceRequest(
                conference_id=self.conference_id, participant_id=self.caller_id
            ),
            timeout=TIMEOUT_S,
            wait_for_ready=True,
        )
        for cancelled in response.cancelled:
            if cancelled.heard_text:
                print(f'  (caller talked over the agent; they heard "{cancelled.heard_text}")')
        for utterance_id in list(self._unfinished):
            self._finished(utterance_id)

    def _finished(self, utterance_id: str) -> Reply | None:
        reply = self.replies_by_id.get(utterance_id)
        self._unfinished.discard(utterance_id)
        if reply is self._newest:
            self._newest = None
        return reply

    @staticmethod
    async def _stop_writing(reply: Reply) -> None:
        reply.withdrawn = True
        if reply.task:
            await reply.task
