# Synauson Examples

Reference applications showing how to build with [jsyn](https://github.com/synauson/jsyn) and the
[synauson](https://synauson.com) media server ecosystem.

Each example is a complete project you can run. Clone it, follow the
`README.md` inside, and you have a working application you can adapt.

## Examples

### Java

| Example | Description |
|---------|-------------|
| [jsyn-webrtc-testbed](java/jsyn-webrtc-testbed) | Docker-published Spring Boot + React app. Two browsers join a room, audio flows through synauson, and every VAD and Smart Turn Detection inference event streams live to the UI. Reference implementation for WebRTC conferencing with real-time event observability. |
| [jsyn-licensing](java/jsyn-licensing) | How to set up Synauson licensing in a jsyn application: license keys and files, capabilities, air-gapped hosts, and usage limits. Includes a tour that CI runs against a real free-tier license. |
| [jsyn-windows-quickstart](java/jsyn-windows-quickstart) | Minimal Gradle project for jsyn on Windows: GStreamer setup, Maven dependencies, and three examples covering file playback, native audio I/O and VAD. |

More examples coming as synauson adds SIP, additional language adapters, and extended conference
features.

## Prerequisites

Each example lists its own prerequisites in its `README.md`. The common thread:

- A Synauson license key in `SYNAUSON_LICENSE_KEY` (free-tier keys work; see [synauson.com](https://synauson.com)).
  jsyn runs the media engine in-process and downloads the AI models your license includes.
- jsyn from the public Synauson Maven repository, `https://maven.synauson.com/releases` (no credentials needed)
- GStreamer 1.26.x installed on the host

## License

Apache 2.0. See [LICENSE](LICENSE).
