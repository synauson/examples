# gRPC voice agent (Python)

A voice agent written in Python against the Synauson server's gRPC API. It runs the same
conversation as the [jsyn voice agent](../../java/jsyn-voice-agent): a simulated caller
books a table, changes their mind while the agent is still talking, and hangs up. The
agent answers each turn, streams its replies as they are written, stops when the caller
talks over it, and picks its event stream up again after a dropped connection.

## What it shows

| Part | What it does | Code to read |
|---|---|---|
| The agent | Reads the caller's agent event stream. Starts a reply on `EagerEndOfTurn` and drops it on `TurnResumed`; `EndOfTurn` confirms it, or starts one when no eager end came. Streams each reply into `Speak` piece by piece under one utterance id and ends it with `SPEAK_RELEASE_END`. When the caller talks over it, cancels the reply and keeps what they heard (`heard_text`). | [`agent.py`](src/grpc_voice_agent/agent.py) |
| The event stream | Subscribes with `StreamAgentEvents` and, after a disconnect, subscribes again with the last `seq` it handled as `after_seq`, so no event is lost or repeated. Asks for a heartbeat every second (`heartbeat_ms`) and treats three missed ones as a dead connection: it cancels the stream and resumes. That catches a connection dropped without an error, which no status code reports. | [`stream.py`](src/grpc_voice_agent/stream.py) |
| Errors | Every error the server sends carries a `google.rpc.ErrorInfo`; the agent reads its `reason` with `grpc-status` and decides by it, never by the status code: a reply the caller talked over (`UTTERANCE_ENDED`) is dropped, a lagging stream (`AGENT_SUBSCRIBER_LAGGED`) resumes, an expired cursor (`AGENT_REPLAY_EXPIRED`) starts afresh. Errors without one come from the gRPC library itself, such as `UNAVAILABLE` on a dropped connection. | [`rpc.py`](src/grpc_voice_agent/rpc.py), [`stream.py`](src/grpc_voice_agent/stream.py) |
| The call | Starts a conference, adds the caller's participant and runs the agent and the caller as tasks of one `asyncio.TaskGroup`. Every unary call has a deadline (`timeout=`). However the call ends, `SIGTERM` included, it removes the participant and ends the conference on the server. | [`call.py`](src/grpc_voice_agent/call.py), [`__main__.py`](src/grpc_voice_agent/__main__.py) |
| The caller | `--caller sip` (the default): a phone on a SIP call that sends the caller's lines as PCMU over RTP and records what it hears. `--caller file`: the server plays the lines from WAV files. | [`callers.py`](src/grpc_voice_agent/callers.py), [`audio.py`](src/grpc_voice_agent/audio.py) |

[`replies.py`](src/grpc_voice_agent/replies.py) stands in for your LLM, and the callers
stand in for the person on the phone. Neither is part of an agent.

## What you need

- A license key in `SYNAUSON_LICENSE_KEY`. Any key works: every license has speech to
  text and text to speech, and a call takes one of its conferences and one of its AI
  sessions.
- Docker, to run the server (`cr.synauson.com/synauson/synauson-server`, which anyone
  can pull) and, with compose, the agent. The agent speaks the API of server 1.8.0 and
  newer (`synauson.v1`, published as [synauson/api](https://github.com/synauson/api));
  older servers don't serve its RPCs.
- To run the agent on the host instead: [uv](https://docs.astral.sh/uv/). It installs
  Python 3.13 and the pinned dependencies from `uv.lock`.
- About 2.5 GB of disk: about 1 GB of models, which the server downloads on its first
  start, and the speech to text weights it prepares from them.

### The API

The agent talks to the server through `synauson-api`, the server's gRPC API as a Python
package (`from synauson.v1 import ...`), installed with its `status` extra:

```sh
pip install "synauson-api[status]>=1.0.1"
```

The extra brings `grpcio-status` and `googleapis-common-protos`, which read the
`google.rpc.ErrorInfo` on every error the server sends. `synauson-api` 1.0.1 or newer
needs server 1.8.0 or newer. `pyproject.toml` lists it as the example's dependency, and
`uv sync` installs it.

## Run it

With Docker Compose: the server and the agent in containers, on one network. The SIP
call's RTP stays between the two containers.

```bash
cd python/grpc-voice-agent
export SYNAUSON_LICENSE_KEY=<your license key>
docker compose up --build --exit-code-from agent
CALLER=file docker compose up --build --exit-code-from agent   # the file caller
```

On the host, with the server in a container on the host's network (Linux):

```bash
just server              # or the docker run command in the justfile
just agent               # the SIP caller: uv run grpc-voice-agent
just agent --caller file
just agent --late-join 5 # the caller joins a conference that has run for 5 s
```

The first start downloads the models; the agent logs `waiting:` lines with their state
until the server is ready.

## What you should see

The agent's output from a compose run on a Linux host. It prints the conversation, and logs
what goes wrong (a lost stream, the engine's recoverable errors) to stderr. The caller's
lines are what speech to text heard of the recordings, so their wording varies from run
to run:

```text
Waiting for speech to text and text to speech...
Call started.
  (caller starts: "Hi, I'd like to book a table for tomorrow evening.")
caller: I I'd like to book a uh table for tomorrow evening
agent:  Sure, I can book that for you. For how many people, and at what time would you like to come in tomorrow evening?
  (caller starts: "Sorry, make that four people at seven.")
  (caller talked over the agent; they heard "Sure, I")
agent:  Got it, a table for four. Is there anything else I can do for you?
caller: Sorry make that four people at seven
  (caller starts: "No, that's all. Thank you, bye.")
agent:  You're welcome. Goodbye!
caller: know that's bald thank you. Bye.
agent:  You're welcome. Goodbye!
Call ended. What the caller heard: /work/call.wav
```

An `agent:` line can come before the `caller:` line it answers: the reply started on the
eager end of the caller's turn, before turn detection confirmed it. When a `TurnResumed`
withdraws an eager end, the agent drops that reply and answers again once the turn ends,
so a reply can appear twice. With the SIP caller, `call.wav` holds what the phone heard.

## Configuration

| Setting | Default | Effect |
|---|---|---|
| `SYNAUSON_LICENSE_KEY` | none | The server's license key |
| `--server` | `localhost:50051` | The server's gRPC address |
| `--caller` | `sip` | `sip`: a phone on a SIP call; `file`: the server plays the lines |
| `--late-join` | 0 | Seconds the conference runs before the caller joins |
| `--lines` | `caller` | The directory of the caller's WAV lines, for the SIP caller |
| `--lines-uri` | `file:///caller` | The same directory where the server reads it, for the file caller. Compose and `just server` mount it there. |
| `--phone-ip` | the address that routes to the server | Where the server sends the call's RTP |
| `CALLER` | `sip` | Compose only: the `--caller` the agent runs with |

The server needs `--stt-eager true` for eager end of turn on a participant with speech to
text; both compose and `just server` pass it.

## Troubleshooting

| What you see | Cause and fix |
|---|---|
| `the license can't use ['FEATURE_STT', 'FEATURE_TTS'] now (LICENSE_STATE_…: …)` | The license starts nothing new: the licensing server refused it, or no usage report has reached `usage.synauson.com` for 7 days. |
| `the server doesn't allow eager end of turn: start it with --stt-eager true` | Add `--stt-eager true` to the server's command. |
| The agent waits several minutes at `waiting: …` | The server's first start downloads about 1 GB of models and prepares the speech to text weights. The volumes keep them for later runs. |
| `WARNING grpc_voice_agent.agent: engine: STT_LAGGING: …` during a call | Speech to text on the server fell behind the caller, so turns can end before their last words. The server's machine is short of CPU for speech. |
| `no utterance_started from the agent within 60 s` | The agent heard no turn from the caller. With the SIP caller on the host, check that nothing blocks UDP between the server and this machine, and pass `--phone-ip` if the server can't reach the address the agent picked. |
| The file caller fails with `FILE_NOT_FOUND` or an error about the file | The server can't read the lines at `--lines-uri`. Mount `caller/` into the server's container at `/caller`. |
| `DEADLINE_EXCEEDED` on `GetCapabilities` at startup | The server didn't answer within 60 s: it isn't running, or not at `--server`. Check `docker logs`. |
| `WARNING grpc_voice_agent.stream: agent stream lost (…); resume after seq N` | The agent's connection dropped, or three heartbeats went missing, and it subscribed again from the last event it handled. Nothing is lost. It gives up after 30 s of failures. |
