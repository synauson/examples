# jsyn voice agent

Speech in and speech out with jsyn, all inside the JVM: speech to text on a recording,
text to speech into a WAV file, and a voice agent that holds a short phone conversation
with a simulated caller.

## What it shows

| Command | What it does | Code to read |
|---|---|---|
| `agent` (the default) | A voice agent books a table with a simulated caller. The caller changes their mind while the agent is still talking; the agent stops, prints what the caller heard of its reply, and answers again. What the caller heard goes to `call.wav`. | [`VoiceAgent`](src/main/java/com/example/voiceagent/VoiceAgent.java), [`AgentStream`](src/main/java/com/example/voiceagent/AgentStream.java), [`CallDemo`](src/main/java/com/example/voiceagent/CallDemo.java) |
| `agent --sip` | The same call, with the caller on a SIP call: a simulated phone sends their voice as PCMU over RTP and records the agent's | [`SimulatedPhone`](src/main/java/com/example/voiceagent/SimulatedPhone.java) |
| `agent --late-join <seconds>` | The caller joins a conference that has already run that long, as a call transferred into a running conference does | [`CallDemo`](src/main/java/com/example/voiceagent/CallDemo.java) |
| `transcribe <file.wav>` | Speech to text on a recording, printed as it is decoded and then turn by turn | [`Transcribe`](src/main/java/com/example/voiceagent/Transcribe.java) |
| `speak "<text>" [out.wav]` | Text to speech into a WAV file (`speech.wav` by default), with the time each word played | [`SpeakToFile`](src/main/java/com/example/voiceagent/SpeakToFile.java) |

## What you need

- A license key in `SYNAUSON_LICENSE_KEY`. Any key works: every license has speech to
  text and text to speech, and a call takes one of its AI sessions.
- In a container: Docker. On the host: a JDK 21, which Gradle also runs on (from
  `JAVA_HOME`, else the `java` on `PATH`; this Gradle version doesn't run on JDK 25),
  and on Linux GStreamer 1.24 or 1.26, or on Windows the GStreamer 1.26.7 MSVC runtime
  and the Visual C++ Redistributable. jsyn's
  [installation guide](https://github.com/synauson/jsyn/blob/v1.7.0/docs/install.md) has the steps.
- HTTPS access to `usage.synauson.com`, `license.synauson.com`, `status.synauson.com`
  and Cloudflare R2 (`*.r2.cloudflarestorage.com`), where the models download from, and
  a synchronized clock (NTP). jsyn's
  [network and firewall](https://github.com/synauson/jsyn/blob/v1.7.0/docs/install.md#network-and-firewall) table says what each is for.
- About 2.5 GB of disk: about 1 GB of models, and about 1.2 GB of speech to text
  weights the engine prepares for your CPU. The first start downloads and prepares
  them, which takes a few minutes; the commands print each model's state while they
  wait.

## Run it

In a container, with [just](https://github.com/casey/just). The `jsyn-voice-agent`
volume keeps the models between runs, and files are read from and written to this
directory:

```bash
cd java/jsyn-voice-agent
export SYNAUSON_LICENSE_KEY=<your license key>
just container agent
just container agent --sip
just container speak "Your table for four is booked for seven tomorrow evening." booked.wav
just container transcribe booked.wav
```

Without just, the same container commands are:

```bash
docker build -t jsyn-voice-agent .
docker run --rm -e SYNAUSON_LICENSE_KEY -v jsyn-voice-agent:/data -v "$PWD:/work" -w /work \
  jsyn-voice-agent agent --sip
```

On the host:

```bash
./gradlew run                                                   # the agent
./gradlew run --args="agent --sip --late-join 5"                # a SIP caller who joins late
./gradlew run --args="speak 'Your table for four is booked for seven tomorrow evening.' booked.wav"
./gradlew run --args="transcribe booked.wav"
```

```powershell
.\gradlew.bat run
.\gradlew.bat run --args="agent --sip"
.\gradlew.bat run --args="speak 'Your table for four is booked for seven tomorrow evening.' booked.wav"
.\gradlew.bat run --args="transcribe booked.wav"
```

## What you should see

These are from runs on a Linux host. While the models download and load, each command
prints `waiting:` lines with their state.

`speak` prints when each word started playing, in milliseconds from the start:

```text
Loading text to speech...
     25 ms  Your
    200 ms  table
    900 ms  for
   1075 ms  four
   1325 ms  is
   1475 ms  booked
   2075 ms  for
   2200 ms  seven
   2550 ms  tomorrow
   2950 ms  evening.
Spoke 3970 ms of audio; 0 ms waiting for synthesis.
Wrote …/booked.wav
```

`transcribe` prints the text as it is decoded, then the turns. Turn detection splits the
recording at its pauses, so the turns and their wording vary with the recording:

```text
Loading speech to text...
Transcribing booked.wav (4 s):
 Your table before is booked for seven tomorrow evening

Transcript (1 turns):
  Your table before is booked for seven tomorrow evening
```

`agent` prints the conversation. The caller's lines are what speech to text heard of the
simulated voice, mistakes included, so their wording varies from run to run. An `agent:`
line can come before the `caller:` line it answers: the reply started on the caller's
eager end of turn, before turn detection confirmed it. When a `TurnResumed` withdraws an
eager end, the agent drops that reply and answers again once the turn ends, so a reply
can appear twice. `agent --sip` prints the same conversation.

```text
Call started.
  (caller starts: "Hi, I'd like to book a table for tomorrow evening.")
caller: High eyed like to book a table for tomorrow evening
agent:  Sure, I can book that for you. For how many people, and at what time would you like to come in tomorrow evening?
  (caller starts: "Sorry, make that four people at seven.")
  (caller talked over the agent; they heard "Sure, I")
agent:  Got it, a table for four. Is there anything else I can do for you?
caller: Sorry make that four people at seven
  (caller starts: "No, that's all. Thank you, bye.")
agent:  You're welcome. Goodbye!
caller: know that's old. Thank you. Bye.
agent:  You're welcome. Goodbye!
Call ended. What the caller heard: …/call.wav
```

The engine also prints its own warnings and errors to the output.

## Configuration

| Setting | Default | Effect |
|---|---|---|
| `SYNAUSON_LICENSE_KEY` | none | The license key |
| The data directory | `build/voice-agent/` on the host, the `jsyn-voice-agent` volume (`/data`) in the container | The model store and the cached license. `./gradlew clean`, or `just clean`, deletes them. |

`transcribe` takes 16-bit PCM WAV at 8, 16 or 48 kHz, mono or stereo. Convert anything
else first, for example with
`ffmpeg -i in.mp3 -ac 1 -ar 16000 -sample_fmt s16 out.wav`.

## How the agent works

A voice agent in jsyn is your code acting on one participant, the caller's. That
participant carries everything the agent needs:

```java
conf.addNativeParticipant("caller", NativeParticipantSpec.builder()
        .format(NativeAudioFormat.PCM_S16LE16K_MONO)
        .vad(VadConfig.defaults())                       // speech start and stop
        .turnDetection(TurnDetectionConfig.defaults()    // when the caller has finished,
                .withTurns(TurnConfigUpdate.none().withEager(true))) // and an early guess
        .stt(SttConfig.defaults())                       // what they said
        .tts(TtsConfig.defaults().withVoice("en-us-f1")) // the agent's voice, played to them
        .build());
```

Eager end of turn on a participant with speech to text also needs
`JSynConfig.Builder.sttEager(true)` on the runtime ([`Engine`](src/main/java/com/example/voiceagent/Engine.java)).

With `agent` the caller is a native participant, so the demo needs no phone line or
browser. With `agent --sip` it is a SIP participant, as for a phone call: your SIP stack
handles the signaling and the SDP, and gives jsyn the phone's address, port and codec.
`SipParticipantSpec` takes the same `vad`, `turnDetection`, `stt` and `tts` options.

The agent reads the caller's agent event stream (`conf.streamAgentEvents`), one ordered
stream of what happens on the call. These events drive the conversation:

- `EagerEndOfTurn`: the caller has probably finished. The agent starts its reply now,
  sending it to `conf.speak` piece by piece as an LLM would stream it, under one
  utterance id, and closes it with `Speak.Release.END`.
- `TurnResumed`: the caller went on, so the early reply is dropped
  (`conf.cancelUtterance`).
- `EndOfTurn`: the turn ended, and `text` is what the caller said. It confirms the early
  reply when their texts match; otherwise the agent answers now. If the caller said more
  before they heard any of a reply, the agent drops it and answers everything at once.
- `SpeechStarted` while the agent is talking: the caller barged in.
  `conf.cancelUtterances` stops the agent and returns what the caller heard of each
  reply (`heardText`), so the conversation history records only that.
- `UtteranceDone` and `UtteranceInterrupted`: a reply finished playing, or was stopped.
  `UtteranceFailed` ends the demo with the reason, and `StreamEnded` ends the call.

The observer runs on an engine thread, and the engine never waits for it, so
[`AgentStream`](src/main/java/com/example/voiceagent/AgentStream.java) only queues events
there and the agent takes them on its own thread. When the observer falls too far behind
and the engine drops it, `AgentStream` subscribes again after the last event it queued,
so the agent loses and repeats nothing.

[`Replies`](src/main/java/com/example/voiceagent/Replies.java) stands in for your LLM.
[`SimulatedCaller`](src/main/java/com/example/voiceagent/SimulatedCaller.java) and
[`SimulatedPhone`](src/main/java/com/example/voiceagent/SimulatedPhone.java) stand in for
the person on the phone: [`Rehearsal`](src/main/java/com/example/voiceagent/Rehearsal.java)
makes their voice with a second speaker before the call, and it goes into the call 20 ms
at a time, the way a call's audio arrives. None of these is part of an agent.

jsyn's [voice agents guide](https://github.com/synauson/jsyn/blob/v1.7.0/docs/voice-agents.md),
[text to speech guide](https://github.com/synauson/jsyn/blob/v1.7.0/docs/text-to-speech.md)
and [SIP guide](https://github.com/synauson/jsyn/blob/v1.7.0/docs/sip.md) describe every
event and option.

The [gRPC voice agent](../../python/grpc-voice-agent) runs the same agent in Python
against the Synauson server.

## Troubleshooting

| What you see | Cause and fix |
|---|---|
| `the license can't use [FEATURE_STT, FEATURE_TTS] now (…)` | The license starts nothing new: the licensing server refused it (`rejected`), or no usage report has reached `usage.synauson.com` for 7 days (`disconnected`). The message gives the state. |
| `InvalidArgumentException: no license key configured: set SYNAUSON_LICENSE_KEY …` | Set the variable in this shell, or pass it to the container (`just container` does). |
| The commands wait several minutes at `waiting: …` | The first start downloads about 1 GB of models and prepares the speech to text weights. Later runs reuse them. |
| `speech failed to load` or `speech not ready within …` | The models didn't download or load; the message gives the reason. Check access to Cloudflare R2 (`*.r2.cloudflarestorage.com`) and the free disk space. |
| `(engine: STT_LAGGING: …)` during a call | Speech to text fell behind the caller, so turns can end before their last words. The machine is short of CPU for speech, often because something else is using it. See jsyn's [capacity guide](https://github.com/synauson/jsyn/blob/v1.7.0/docs/sizing.md). |
| `no UtteranceStarted from the agent within 60 s` | The agent heard no turn from the caller. With `--sip`, check that nothing on this machine blocks UDP on the loopback address. |
| `no such file: …` from `transcribe` | Give a path relative to this directory (`java/jsyn-voice-agent`) or a full path. In the container, the file must be under this directory. |
| `Spoke … ms of audio; N ms waiting for synthesis` with N above 0 | The machine is short of CPU for speech, often because something else is using it. See jsyn's [capacity guide](https://github.com/synauson/jsyn/blob/v1.7.0/docs/sizing.md). |
| Host runs fail with `UnsatisfiedLinkError` or a GStreamer error | See jsyn's [troubleshooting guide](https://github.com/synauson/jsyn/blob/v1.7.0/docs/troubleshooting.md), or run in the container. |
