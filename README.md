# Synauson examples

Complete, runnable applications built on [jsyn](https://github.com/synauson/jsyn), the
Java SDK that runs the [Synauson](https://synauson.com) media engine inside your JVM, and
one on the Synauson server's gRPC API. Each one is a whole application, from its
dependencies to a clean shutdown, that you can run as it is and copy from.

To learn the SDK itself (installing it, the concepts and a guide per feature), start
at the [jsyn README](https://github.com/synauson/jsyn).

## Examples

| Example | What it shows | Level | Runs on |
|---|---|---|---|
| [Windows quickstart](java/jsyn-windows-quickstart) | A minimal Gradle project: playing a file, sending and receiving audio from Java, and voice activity detection | Start here | Windows |
| [Voice agent](java/jsyn-voice-agent) | Speech to text on a recording, text to speech into a WAV file, and an agent that answers a caller's turns, streams its replies and stops when the caller talks over it, with the caller in the JVM or on a SIP call | Intermediate | Linux, Windows, Docker |
| [gRPC voice agent](python/grpc-voice-agent) | The same voice agent in Python, against the Synauson server's gRPC API, with a caller on a SIP call | Intermediate | Linux, Docker |
| [Licensing](java/jsyn-licensing) | Setting up licensing: keys and license files, the capabilities report, the shared session and conference pools, offline hosts and limits, checked by a tour against a live runtime | Intermediate | Linux, Windows, Docker |
| [WebRTC testbed](java/jsyn-webrtc-testbed) | A Spring Boot and React app: two browsers join one conference over WebRTC, and VAD and turn events stream live to the page | Full application | Linux (Docker or host) |

Every example has a README in the same shape: what it shows, what you need, how to run
it, the output to expect, its settings and troubleshooting.

## What you need

- A Synauson license key, in the `SYNAUSON_LICENSE_KEY` environment variable. Every
  runtime needs one; Synauson issues keys, free ones included. Every key runs every
  example: each plan has every AI feature (voice activity and turn detection, speech to
  text, text to speech), and plans differ in how many AI sessions and conferences run at
  once (4 and 10 on the Free plan), burst room and support. At startup the runtime
  downloads the models.
  [How licensing connects](https://synauson.com/licensing/) explains what the runtime
  sends, and the [license agreement](https://synauson.com/legal/eula/) is the binding
  text.
- HTTPS access to `usage.synauson.com`, which every runtime reports to,
  `license.synauson.com` and `status.synauson.com`, and Cloudflare R2
  (`*.r2.cloudflarestorage.com`) for the models, and a synchronized clock (NTP). jsyn's
  [network and firewall](https://github.com/synauson/jsyn/blob/v1.7.0/docs/install.md#network-and-firewall)
  table says what each one is for.
- To run an example in a container: Docker.
- To run on the host: the Java version the example names (21, or 11 for the Windows
  quickstart) and GStreamer 1.24 or 1.26. The gRPC voice agent needs
  [uv](https://docs.astral.sh/uv/) instead, and runs the server in Docker. jsyn's
  [installation guide](https://github.com/synauson/jsyn/blob/v1.7.0/docs/install.md) has
  the steps for Linux and Windows.

jsyn and its natives come from the public Maven repository
`https://maven.synauson.com/releases`, which needs no credentials. Each example's
`build.gradle.kts` pins the versions it was tested with: jsyn 1.7.0 and jsyn-natives
1.8.0.

## Run one

```bash
export SYNAUSON_LICENSE_KEY=<your license key>

# The voice agent, in a container
cd java/jsyn-voice-agent && just container agent

# The gRPC voice agent and the server, in containers
cd python/grpc-voice-agent && docker compose up --build --exit-code-from agent

# The licensing tour, in a container
cd java/jsyn-licensing && just tour-container

# The WebRTC testbed, from its published image; then open http://localhost:8080/room/alice
docker run --rm --network host -e SYNAUSON_LICENSE_KEY \
  cr.synauson.com/synauson/synauson-webrtc-testbed:latest
```

```powershell
# The Windows quickstart
cd java\jsyn-windows-quickstart
.\gradlew.bat run
```

The `just` recipes need [just](https://github.com/casey/just); each example's README
also gives the plain commands.

## Releases and support

Each release of these examples is tagged with the jsyn version it uses. Before release,
every example was built and run against that jsyn release, a real license and the real
runtime: the Windows quickstart on Windows, the voice agent on Windows and Linux, the
others on Linux. The gRPC voice agent ran against the server image its `compose.yaml`
pins.
[CHANGELOG.md](CHANGELOG.md) lists what each release changed.

For help, see [SUPPORT.md](SUPPORT.md). To report a security problem, see
[SECURITY.md](SECURITY.md).

## License

Apache 2.0. See [LICENSE](LICENSE).
