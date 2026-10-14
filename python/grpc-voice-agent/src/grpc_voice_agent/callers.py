"""Test scaffolding, not part of an agent: the person on the phone, so the demo runs without
a SIP trunk. Its lines are WAV recordings in caller/. Two kinds of caller:

- FileCaller: a file participant on the server that plays the lines, for when you have no
  phone line at all. The server reads the files, so they must be on its filesystem.
- PhoneCaller: a phone at the far end of a SIP call. The server's SIP participant is only
  the call's media: your SIP stack does the signalling and the SDP, and gives it the
  phone's address, port and codec. This one sends the lines as PCMU over RTP from a UDP
  socket and keeps the RTP it gets back, which is the agent's voice.
"""

from __future__ import annotations

import asyncio
import contextlib
import random
import socket
from array import array
from collections.abc import Callable
from pathlib import Path
from typing import Protocol

from synauson.v1 import agent_pb2 as agent
from synauson.v1 import detector_pb2 as detector
from synauson.v1 import events_pb2 as events
from synauson.v1 import participant_pb2 as participant
from synauson.v1 import sip_pb2 as sip
from synauson.v1 import synauson_pb2_grpc

from . import audio
from .rpc import TIMEOUT_S
from .stream import is_stored

LINES = {
    "booking": "Hi, I'd like to book a table for tomorrow evening.",
    "change": "Sorry, make that four people at seven.",
    "bye": "No, that's all. Thank you, bye.",
}


def caller_detectors(agent_voice: str) -> detector.DetectorConfig:
    """What the caller's participant carries for the agent: VAD (speech start and stop),
    turn detection (when the caller has finished), STT (what they said), eager end of turn
    (an early signal that they have probably finished), and a speaker for the agent's
    voice."""
    return detector.DetectorConfig(
        # The values jsyn's VadConfig.defaults() and TurnDetectionConfig.defaults() use.
        vad=detector.VadConfig(threshold=0.5, min_silence_ms=300, min_speech_ms=250),
        turn_detection=detector.TurnDetectionConfig(buffered_samples=160, confidence_threshold=0.5),
        stt=detector.SttConfig(),
        turns=agent.TurnConfigUpdate(eager=True),
        tts=detector.TtsConfig(voice=agent_voice),
    )


class Caller(Protocol):
    participant_id: str

    async def join(self) -> None: ...
    async def say(self, line: str) -> None: ...
    async def leave(self) -> None: ...


class AgentWatch:
    """A second subscription to the caller's agent stream, from its first event to its
    last, to see when the agent talks. Its events are also the call's whole record.
    finish() ends it; until then a task reads it."""

    def __init__(
        self, stub: synauson_pb2_grpc.SynAsyncStub, conference_id: str, participant_id: str
    ) -> None:
        self.events: list[agent.AgentEvent] = []
        self._changed = asyncio.Condition()
        self._call = stub.StreamAgentEvents(
            agent.StreamAgentEventsRequest(
                conference_id=conference_id, participant_id=participant_id
            )
        )
        self._task = asyncio.create_task(self._read())

    async def _read(self) -> None:
        try:
            async for event in self._call:
                if is_stored(event):
                    async with self._changed:
                        self.events.append(event)
                        self._changed.notify_all()
        finally:
            self._call.cancel()

    async def wait_until(
        self, what: str, done: Callable[[list[agent.AgentEvent]], bool], timeout_s: float = 60.0
    ) -> None:
        """Waits until the call's events so far satisfy done."""
        try:
            async with asyncio.timeout(timeout_s), self._changed:
                await self._changed.wait_for(lambda: done(self.events))
        except TimeoutError:
            raise TimeoutError(f"no {what} within {timeout_s:.0f} s") from None

    async def finish(self, timeout_s: float = 10.0) -> None:
        """Waits for the stream's end (StreamEnded) after the participant was removed,
        then stops reading. A failed read raises here."""
        with contextlib.suppress(TimeoutError):
            async with asyncio.timeout(timeout_s):
                await asyncio.shield(self._task)
        self._task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await self._task


def turns_decided(events: list[agent.AgentEvent], turns: int) -> bool:
    """Whether the caller's turns are decided: at least `turns` have ended, and every turn
    that started has its EndOfTurn. Until then, more speech from the caller may resume the
    open turn rather than start a new one."""
    started = ended_turn = endings = 0
    for e in events:
        kind = e.WhichOneof("event")
        if kind == "turn_started":
            started = e.turn_started.turn_id
        elif kind == "end_of_turn":
            ended_turn = e.end_of_turn.turn_id
            endings += 1
    return endings >= turns and ended_turn == started


def agent_talking(events: list[agent.AgentEvent]) -> bool:
    """Whether an utterance of the agent is playing and the caller has heard some of it."""
    heard: set[str] = set()
    for e in events:
        kind = e.WhichOneof("event")
        if kind == "words_played" and e.words_played.words:
            heard.add(e.words_played.utterance_id)
        elif kind == "utterance_done":
            heard.discard(e.utterance_done.utterance_id)
        elif kind == "utterance_interrupted":
            heard.discard(e.utterance_interrupted.utterance_id)
    return bool(heard)


def answered_since_last_turn(events: list[agent.AgentEvent]) -> bool:
    """Whether an utterance of the agent that started after the caller's latest turn
    started has played to its end: an answer to that turn, not an earlier one."""
    last_turn = max(
        (i for i, e in enumerate(events) if e.WhichOneof("event") == "turn_started"), default=-1
    )
    started: set[str] = set()
    for e in events[last_turn + 1 :]:
        kind = e.WhichOneof("event")
        if kind == "utterance_started":
            started.add(e.utterance_started.utterance_id)
        elif kind == "utterance_done" and e.utterance_done.utterance_id in started:
            return True
    return False


class FileCaller:
    """A file participant that plays silence, and each line as priority audio."""

    def __init__(
        self,
        stub: synauson_pb2_grpc.SynAsyncStub,
        conference_id: str,
        lines_uri: str,
        agent_voice: str,
        participant_id: str = "caller",
    ) -> None:
        self.participant_id = participant_id
        self._stub = stub
        self._conference_id = conference_id
        self._lines_uri = lines_uri.rstrip("/")
        self._agent_voice = agent_voice
        self._file_events: asyncio.Queue[events.FileEvent] = asyncio.Queue()
        self._reader: asyncio.Task[None] | None = None

    async def join(self) -> None:
        await self._stub.AddFileParticipant(
            participant.AddFileParticipantRequest(
                conference_id=self._conference_id,
                participant_id=self.participant_id,
                uri=f"{self._lines_uri}/silence.wav",
                loop_playback=True,
                detectors=caller_detectors(self._agent_voice),
            ),
            timeout=TIMEOUT_S,
        )
        file_events = self._stub.StreamFileEvents(
            events.StreamFileEventsRequest(
                conference_id=self._conference_id, participant_id=self.participant_id
            )
        )

        async def read() -> None:
            try:
                async for e in file_events:
                    self._file_events.put_nowait(e)
            finally:
                file_events.cancel()

        self._reader = asyncio.create_task(read())

    async def say(self, line: str) -> None:
        print(f'  (caller starts: "{LINES[line]}")')
        uri = f"{self._lines_uri}/{line}.wav"
        await self._stub.AddPriorityAudioFiles(
            participant.AddPriorityAudioFilesRequest(
                conference_id=self._conference_id,
                participant_id=self.participant_id,
                uris=[uri],
            ),
            timeout=TIMEOUT_S,
        )
        async with asyncio.timeout(60):
            while True:
                e = await self._file_events.get()
                if e.WhichOneof("event") == "playback_ended" and e.playback_ended.uri == uri:
                    return
                if e.WhichOneof("event") == "error":
                    raise RuntimeError(f"the caller's file failed: {e.error.message}")

    async def leave(self) -> None:
        if self._reader:
            self._reader.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._reader


class _Rtp(asyncio.DatagramProtocol):
    def __init__(self, heard: array[int]) -> None:
        self.heard = heard
        self.packets = 0

    def datagram_received(self, data: bytes, addr: tuple[str | object, int]) -> None:
        parsed = audio.rtp_payload(data)
        if parsed and parsed[0] == audio.PCMU:
            self.packets += 1
            self.heard.extend(audio.ulaw_decode_all(parsed[1]))


class PhoneCaller:
    """A phone on a SIP call: PCMU over RTP, 20 ms a packet, in real time."""

    FRAME = 160  # 20 ms at 8 kHz

    def __init__(
        self,
        stub: synauson_pb2_grpc.SynAsyncStub,
        conference_id: str,
        server_host: str,
        lines_dir: Path,
        agent_voice: str,
        phone_ip: str | None = None,
        participant_id: str = "caller",
    ) -> None:
        self.participant_id = participant_id
        self.heard = array("h")
        self._stub = stub
        self._conference_id = conference_id
        self._server_ip = socket.gethostbyname(server_host)
        self._phone_ip = phone_ip or _local_ip_towards(self._server_ip)
        self._agent_voice = agent_voice
        self._lines = {
            name: audio.ulaw_encode_all(audio.to_8k(*audio.read_wav(lines_dir / f"{name}.wav")))
            for name in LINES
        }
        self._outgoing: asyncio.Queue[bytes] = asyncio.Queue()
        self._transport: asyncio.DatagramTransport | None = None
        self._protocol = _Rtp(self.heard)
        self._sender: asyncio.Task[None] | None = None

    async def join(self) -> None:
        loop = asyncio.get_running_loop()
        self._transport, _ = await loop.create_datagram_endpoint(
            lambda: self._protocol, local_addr=(self._phone_ip, 0)
        )
        phone_port = self._transport.get_extra_info("sockname")[1]
        # What a SIP stack does with the SDP: the phone's offer named its address, port
        # and codec; the answer carries the port the server bound for the call.
        added = await self._stub.AddSipParticipant(
            sip.AddSipParticipantRequest(
                conference_id=self._conference_id,
                participant_id=self.participant_id,
                remote_ip=self._phone_ip,
                remote_rtp_port=phone_port,
                codec="PCMU",
                detectors=caller_detectors(self._agent_voice),
            ),
            timeout=TIMEOUT_S,
        )
        self._sender = asyncio.create_task(self._send((self._server_ip, added.local_rtp_port)))

    async def say(self, line: str) -> None:
        print(f'  (caller starts: "{LINES[line]}")')
        payload = self._lines[line]
        for at in range(0, len(payload), self.FRAME):
            frame = payload[at : at + self.FRAME]
            self._outgoing.put_nowait(frame.ljust(self.FRAME, bytes([audio.ULAW_SILENCE])))
        await self._outgoing.join()  # until the last frame was sent

    async def _send(self, to: tuple[str, int]) -> None:
        """Sends a packet every 20 ms: the next frame of a line, or silence."""
        assert self._transport is not None
        loop = asyncio.get_running_loop()
        silence = bytes([audio.ULAW_SILENCE]) * self.FRAME
        ssrc = random.getrandbits(32)
        seq = random.getrandbits(16)
        timestamp = random.getrandbits(32)
        start = loop.time()
        talking = False
        for n in range(1 << 62):
            try:
                frame = self._outgoing.get_nowait()
            except asyncio.QueueEmpty:
                frame = None
            self._transport.sendto(
                audio.rtp_packet(
                    seq + n,
                    timestamp + n * self.FRAME,
                    ssrc,
                    frame or silence,
                    marker=frame is not None and not talking,
                ),
                to,
            )
            talking = frame is not None
            if frame is not None:
                self._outgoing.task_done()
            await asyncio.sleep(max(0.0, start + (n + 1) * 0.02 - loop.time()))

    async def leave(self) -> None:
        if self._sender:
            self._sender.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._sender
        if self._transport:
            self._transport.close()


def _local_ip_towards(server_ip: str) -> str:
    """The address of this host's interface that reaches the server: what a phone puts
    in its SDP."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect((server_ip, 9))  # UDP: no packet is sent
        ip: str = s.getsockname()[0]
        return ip
