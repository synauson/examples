# Synauson Examples

Reference applications showing how to build with [jsyn](https://github.com/synauson/jsyn) and the
[synauson](https://synauson.com) media server ecosystem.

Each example is a self-contained, runnable project — not a code snippet. Clone it, follow the
`README.md` inside, and you have a working application you can adapt.

## Examples

### Java

| Example | Description |
|---------|-------------|
| [jsyn-webrtc-testbed](java/jsyn-webrtc-testbed) | Docker-published Spring Boot + React app. Two browsers join a room, audio flows through synauson, and every VAD and Smart Turn Detection inference event streams live to the UI. Reference implementation for WebRTC conferencing with real-time event observability. |
| [jsyn-windows-quickstart](java/jsyn-windows-quickstart) | Minimal Gradle project demonstrating jsyn on Windows — GStreamer setup, Nexus artifact resolution, and three working examples: file playback, native audio I/O, and VAD detection. |

More examples coming as synauson adds SIP, additional language adapters, and extended conference
features.

## Prerequisites

Each example lists its own prerequisites in its `README.md`. The common thread:

- A running synauson instance (see [synauson.com](https://synauson.com) for how to obtain one)
- jsyn artifacts from the synauson Nexus registry (credentials provided with your synauson license)
- GStreamer 1.26.x installed on the host

## License

Apache 2.0 — see [LICENSE](LICENSE).
