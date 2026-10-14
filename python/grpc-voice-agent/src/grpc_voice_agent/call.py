"""One call: the caller's participant, the agent on its agent stream, and a simulated caller
who books a table, changes their mind while the agent is still talking, and hangs up."""

from __future__ import annotations

import asyncio
import logging
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from pathlib import Path

import grpc
from synauson.v1 import agent_pb2 as agent
from synauson.v1 import capabilities_pb2 as capabilities
from synauson.v1 import conference_pb2 as conference
from synauson.v1 import participant_pb2 as participant
from synauson.v1 import synauson_pb2_grpc

from . import audio
from .agent import Line, VoiceAgent
from .callers import (
    AgentWatch,
    Caller,
    FileCaller,
    PhoneCaller,
    agent_talking,
    answered_since_last_turn,
    turns_decided,
)
from .rpc import TIMEOUT_S, enum_name, error_reason
from .stream import AgentStream

log = logging.getLogger(__name__)

AGENT_VOICE = "en-us-f1"


@dataclass
class CallOptions:
    target: str = "localhost:50051"  # the server's gRPC address
    caller: str = "sip"  # "sip" or "file"
    late_join: float = 0.0  # seconds the conference runs before the caller joins
    lines_dir: Path = Path("caller")  # the caller's WAV lines, as this process sees them
    lines_uri: str = "file:///caller"  # the same directory, as the server sees it
    phone_ip: str | None = None  # the SIP phone's address; found from the route if unset
    recording: Path | None = Path("call.wav")  # what the SIP caller heard
    agent_target: str | None = None  # the agent's own connection, if not target
    on_agent_event: Callable[[agent.AgentEvent, AgentStream], None] | None = None


@dataclass
class CallResult:
    agent_events: list[agent.AgentEvent]  # what the agent's stream handed it
    call_events: list[agent.AgentEvent]  # the whole call's stored events, to StreamEnded
    history: list[Line]
    replies: dict[str, str]  # each reply's utterance id and the text sent for it


async def wait_until_ready(stub: synauson_pb2_grpc.SynAsyncStub, within_s: float = 900) -> None:
    """Fails unless the license may use speech to text and text to speech now, then waits for
    their models to download and load. On the server's first start that takes minutes."""
    # wait_for_ready: the server may still be starting; the deadline bounds the wait.
    caps = await stub.GetCapabilities(
        capabilities.GetCapabilitiesRequest(), timeout=60, wait_for_ready=True
    )
    missing = [
        c.code
        for c in caps.capabilities
        if c.code in ("FEATURE_STT", "FEATURE_TTS") and not c.entitled
    ]
    if missing:
        state = enum_name(capabilities.LicenseState.Name, caps.license.state)
        # Every license has them; only one that starts nothing new can't use them.
        raise SystemExit(
            f"the license can't use {missing} now ({state}: {caps.license.description})"
        )
    last = ""
    async with asyncio.timeout(within_s):
        while True:
            caps = await stub.GetCapabilities(
                capabilities.GetCapabilitiesRequest(), timeout=TIMEOUT_S
            )
            stt, tts = caps.stt.state, caps.tts.state
            if stt == capabilities.POOL_STATE_READY and tts == capabilities.POOL_STATE_READY:
                if not caps.stt.eager:
                    raise SystemExit(
                        "the server doesn't allow eager end of turn: start it with --stt-eager true"
                    )
                return
            if capabilities.POOL_STATE_FAILED in (stt, tts):
                raise SystemExit(
                    f"speech failed to load: stt {caps.stt.detail}, tts {caps.tts.detail}"
                )
            now = f"speech to text {_pool(stt)}, text to speech {_pool(tts)}; models: " + ", ".join(
                f"{m.id} {enum_name(capabilities.ModelState.Name, m.state)}" for m in caps.models
            )
            if now != last:
                log.info("waiting: %s", now)
                last = now
            await asyncio.sleep(0.5)


def _pool(state: capabilities.PoolState.ValueType) -> str:
    return enum_name(capabilities.PoolState.Name, state).removeprefix("POOL_STATE_").lower()


async def converse(caller: Caller, watch: AgentWatch) -> None:
    """The caller's side: books a table, changes their mind while the agent is still
    answering, and says goodbye once the agent has finished its answer. Each line waits
    until turn detection has decided the last one, so a line never joins the turn before
    it, however long that takes."""
    await caller.say("booking")
    # They change their mind while the agent is still answering (if its answer already
    # ended, they simply speak next).
    await watch.wait_until(
        "decided booking turn and the agent's answer",
        lambda e: turns_decided(e, 1) and (agent_talking(e) or answered_since_last_turn(e)),
    )
    await caller.say("change")
    await watch.wait_until(
        "decided change turn and its answer played to its end",
        lambda e: turns_decided(e, 2) and answered_since_last_turn(e),
    )
    await caller.say("bye")


async def talk(voice_agent: VoiceAgent, caller: Caller, watch: AgentWatch) -> None:
    """Runs the agent and the caller side by side until the agent hangs up. Both are
    tasks in a TaskGroup: if either fails, the other is cancelled and the call fails."""
    async with asyncio.TaskGroup() as tasks:
        agent_task = tasks.create_task(voice_agent.run())
        script = tasks.create_task(converse(caller, watch))
        await asyncio.wait({agent_task, script}, return_when=asyncio.FIRST_COMPLETED)
        if not script.done():
            raise RuntimeError("the agent ended the call before the caller was done")
        try:
            async with asyncio.timeout(60):
                await agent_task
        except TimeoutError:
            raise RuntimeError("the agent didn't end the call within 60 s") from None


async def run_call(options: CallOptions) -> CallResult:
    # One channel is the usual shape. The agent has its own here only so that the tests
    # can route it through a proxy and drop its connection without dropping the caller's.
    async with (
        grpc.aio.insecure_channel(options.target) as channel,
        grpc.aio.insecure_channel(options.agent_target or options.target) as agent_channel,
    ):
        stub = synauson_pb2_grpc.SynStub(channel)
        print("Waiting for speech to text and text to speech...")
        await wait_until_ready(stub)

        conference_id = f"call-{uuid.uuid4().hex[:8]}"
        await stub.StartConference(
            conference.StartConferenceRequest(conference_id=conference_id), timeout=TIMEOUT_S
        )
        # Every exit, Ctrl-C and SIGTERM included, ends the conference on the server
        # before the channel closes.
        try:
            if options.late_join:
                print(f"The conference runs for {options.late_join:g} s before the call.")
                await asyncio.sleep(options.late_join)
            caller: Caller
            if options.caller == "sip":
                caller = PhoneCaller(
                    stub,
                    conference_id,
                    server_host=options.target.rsplit(":", 1)[0],
                    lines_dir=options.lines_dir,
                    agent_voice=AGENT_VOICE,
                    phone_ip=options.phone_ip,
                )
            else:
                caller = FileCaller(stub, conference_id, options.lines_uri, AGENT_VOICE)
            await caller.join()
            watch = AgentWatch(stub, conference_id, caller.participant_id)
            voice_agent = VoiceAgent(
                synauson_pb2_grpc.SynStub(agent_channel),
                conference_id,
                caller.participant_id,
                on_event=options.on_agent_event,
            )
            print("Call started.")
            try:
                await talk(voice_agent, caller, watch)
            finally:
                await caller.leave()
                await _cleanup(
                    stub.RemoveParticipant(
                        participant.RemoveParticipantRequest(
                            conference_id=conference_id, participant_id=caller.participant_id
                        ),
                        timeout=TIMEOUT_S,
                    )
                )
                await watch.finish()
        finally:
            await _cleanup(
                stub.TerminateConference(
                    conference.TerminateConferenceRequest(conference_id=conference_id),
                    timeout=TIMEOUT_S,
                )
            )

    if isinstance(caller, PhoneCaller) and options.recording:
        audio.write_wav(options.recording, caller.heard, 8_000)
        print(f"Call ended. What the caller heard: {options.recording.resolve()}")
    else:
        print("Call ended.")
    return CallResult(
        agent_events=voice_agent.events,
        call_events=watch.events,
        history=voice_agent.history,
        replies={r.utterance_id: r.text for r in voice_agent.replies_by_id.values()},
    )


async def _cleanup(rpc: Awaitable[object]) -> None:
    """A teardown call: logs a failure instead of raising it, so it can't hide the error
    that ended the call. The participant or conference may be gone already."""
    try:
        await rpc
    except grpc.aio.AioRpcError as e:
        if error_reason(e) not in ("PARTICIPANT_NOT_FOUND", "CONFERENCE_NOT_FOUND"):
            log.warning("teardown failed: %s %s", e.code().name, e.details())
