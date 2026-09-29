# Synauson Examples

Complete, runnable applications built on [jsyn](https://github.com/synauson/jsyn), the Java
library that runs the [Synauson](https://synauson.com) media engine in-process.

This repository is for seeing a whole application work end to end, from dependencies to
teardown. To learn the SDK itself (installing it, a first conference, the concepts, and which
jsyn test shows each feature), start at the [jsyn README](https://github.com/synauson/jsyn).

CI builds and runs every example on each push, against the released jsyn, a real license and
the real runtime.

## Examples

| Example | What it demonstrates end to end | Runs on |
|---|---|---|
| [jsyn-webrtc-testbed](java/jsyn-webrtc-testbed) | A Spring Boot + React app: browsers join a room over WebRTC, audio is mixed through one jsyn conference, and every VAD and Smart Turn event streams live to the UI. Shows WebSocket signalling, rejoin handling, license-aware detectors and clean shutdown. Published as a container image. | Linux (Docker, host networking) |
| [jsyn-licensing](java/jsyn-licensing) | How to set up licensing in a jsyn application: license keys and files, the capabilities report, air-gapped hosts and usage limits. A tour checks each claim against a live runtime. | Docker, or the host on Linux or Windows |
| [jsyn-windows-quickstart](java/jsyn-windows-quickstart) | A minimal Gradle project for Windows: GStreamer setup, the Maven dependencies, and three small programs for file playback, native audio I/O and VAD. | Windows |

## What you need

- **A Synauson license key** in `SYNAUSON_LICENSE_KEY`. Free-tier keys work; see
  [synauson.com](https://synauson.com). There is no keyless mode. At startup the runtime
  downloads the AI models your license includes.
- **To run in a container** (testbed, licensing tour): Docker only.
- **To run on the host**:
  - Java 21 for the testbed and the licensing tour, Java 11 or later for the Windows quickstart.
  - **GStreamer 1.26.** GStreamer 1.28 changed webrtcbin's pad API and breaks WebRTC, so avoid
    distributions that ship it. On Debian or Ubuntu with 1.26:

    ```bash
    sudo apt-get install libgstreamer1.0-0 gstreamer1.0-plugins-base \
      gstreamer1.0-plugins-good gstreamer1.0-plugins-bad gstreamer1.0-nice
    ```

    On Windows, install the GStreamer 1.26 MSVC runtime MSI; the
    [quickstart's guide](java/jsyn-windows-quickstart/GETTING_STARTED.md) walks through it.

jsyn and its natives (`jsyn-natives-linux`, `jsyn-natives-windows`) come from the public
Maven repository `https://maven.synauson.com/releases`; no credentials are needed. Each
example pins the jsyn version in its own `build.gradle.kts`.

## Running them

Each example's README has the full instructions. The short version:

```bash
export SYNAUSON_LICENSE_KEY=<your license key>

# WebRTC testbed: the published image, then open http://localhost:8080/room/alice
docker run --rm --network host -e SYNAUSON_LICENSE_KEY \
  cr.synauson.com/synauson/synauson-webrtc-testbed:latest

# Licensing tour, in a container
cd java/jsyn-licensing && just tour-container
```

```powershell
# Windows quickstart
cd java\jsyn-windows-quickstart
.\gradlew.bat run
```

## License

Apache 2.0. See [LICENSE](LICENSE).
