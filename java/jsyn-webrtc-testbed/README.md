# JSyn WebRTC Testbed

A Docker-published reference implementation that demonstrates how to use the **[jsyn](https://github.com/synauson/jsyn)** Java client library to host a live WebRTC audio conference with real-time **VAD** (Voice Activity Detection) and **fermata-1 Detection** events.

Two browsers connect at `/room/<id>`, audio flows through synauson, and every inference event for every participant is streamed live to both browsers and rendered in a developer-observability UI.

> **Single-participant mode is fully functional.** Open just one browser at `/room/alice`, click Join, and speak — VAD and Turn events appear in the event rail immediately. No second browser required to evaluate the detection pipeline.

---

## Run the published image

```bash
docker run --rm \
  --network host \
  -e SYNAUSON_LICENSE_KEY=<your license key> \
  -v synauson-testbed-models:/var/lib/synauson/models \
  -v synauson-testbed-state:/var/lib/synauson/state \
  -e TESTBED_CONFERENCE_ID=demo \
  cr.synauson.com/synauson/synauson-webrtc-testbed:latest
```

The testbed needs a Synauson license key (free-tier keys work). At startup the runtime
downloads the models your license includes into the models volume and caches the license
file in the state volume, so restarts reuse both. It attaches only the detectors the license
includes: VAD, turn detection, or neither. The startup log says which:

```
License: <your license>. Detectors: VAD on, turn detection on
```

Then open two browsers:

```
http://localhost:8080/room/alice
http://localhost:8080/room/bob
```

**Docker host networking is required on Linux** — webrtcbin advertises the host's real IP for ICE candidates. Docker Desktop for Mac/Windows does not support `--network host`; run natively on Linux for media to work.

---

## Configuration

Set these as environment variables (`-e` for the container). They bind to `testbed.*` in
`src/main/resources/application.yml`, which holds the defaults.

| Variable | Default | What |
|---|---|---|
| `SYNAUSON_LICENSE_KEY` | none | The license key, read by jsyn when `TESTBED_LICENSE_KEY` is blank |
| `TESTBED_LICENSE_KEY` | blank | The license key, if you'd rather not use `SYNAUSON_LICENSE_KEY` |
| `TESTBED_SERVER_PORT` | `8080` | HTTP and WebSocket port |
| `TESTBED_CONFERENCE_ID` | `testbed` | Id of the single conference every room joins |
| `TESTBED_MODEL_STORE` | `/var/lib/synauson/models` | Where the runtime downloads the licensed models |
| `TESTBED_STATE_DIR` | `/var/lib/synauson/state` | Where the runtime caches the license file |
| `TESTBED_MODEL_WAIT_SECONDS` | `120` | How long startup waits for the models before running without them |
| `TESTBED_STUN_SERVER` | `stun://stun.l.google.com:19302` | Server-side STUN URI (`stun://host:port`) |
| `TESTBED_VAD_THRESHOLD` | `0.5` | VAD speech probability threshold (0 to 1) |
| `TESTBED_VAD_MIN_SILENCE_MS` | `100` | Silence before a `SpeechEnd` |
| `TESTBED_VAD_MIN_SPEECH_MS` | `250` | Speech before a `SpeechStart` |
| `TESTBED_TURN_DETECTION_BUFFERED_SAMPLES` | `160` | Samples turn detection buffers before each inference |
| `TESTBED_TURN_DETECTION_CONFIDENCE_THRESHOLD` | `0.65` | Probability at which turn detection reports the turn complete |

`just dev` points the model store and state directory at `build/synauson/` so a host run
doesn't need `/var/lib/synauson`.

---

## Local development

Two-terminal dev loop (hot reload + live JSyn backend):

```bash
# Terminal 1 — Spring Boot backend (port 8080)
cd java/jsyn-webrtc-testbed
export SYNAUSON_LICENSE_KEY=<your license key>
just dev        # bootRun with the model store and license cache under build/synauson

# Terminal 2 — Vite dev server (port 5173, proxies /ws → 8080)
cd java/jsyn-webrtc-testbed/frontend
npm run dev
```

Open `http://localhost:5173/room/alice` — React Fast Refresh updates on every file save; WebSocket signalling tunnels to the Spring backend.

For Java-only iteration (skip the npm build):

```bash
./gradlew bootRun -PskipFrontend
```

`./gradlew test` runs the unit tests, and with `SYNAUSON_LICENSE_KEY` set (and GStreamer
installed) also starts the whole application against the real runtime. Set
`TESTBED_EXPECT_DETECTORS=true` as well to require that the license enabled both VAD and
turn detection, as CI does.

---

## Architecture

```
Browser /room/<id>                Spring Boot (port 8080)             JSyn / synauson
──────────────────                ──────────────────────              ───────────────
                                  GET /                → SPA (classpath:/static)
                                  GET /room/<id>       → SPA (SPA fallback resolver)

  open WS /ws/room/<id>  ───────►
                                  PathParticipantIdInterceptor
                                  RoomWebSocketHandler
                                  ConferenceService
  {sdp_offer}            ───────► conference.addWebRtcParticipant() ─► webrtcbin
  {ice_candidate}        ───────► handle.addIceCandidate()     ──────► webrtcbin
                                  ◄────────────────────────────────── {sdp_answer}
                                  ◄────────────────────────────────── {ice_candidate}
                                  ◄──── VAD/Turn events (fanned out to all sockets)
```

**ConferenceService** is the core JSyn orchestrator — it demonstrates:
- Recreate-on-rejoin: if the same participant ID opens again, the prior session is evicted and the new one wins
- Epoch tokens: stale callbacks from superseded sessions are dropped
- Reference-equality cleanup: a stale TCP RST can't kill the current session
- License-aware detectors: `AiFeatures` waits for the licensed models at startup and attaches only the detectors the license includes
- Single-participant mode: VAD/Turn detection runs with just one browser; `rewireMesh()` produces an empty `ConnectionMatrix` (no audio routing) but the detector subscriptions are live


---

## Recreate-on-rejoin

Refreshing the browser or opening `/room/alice` in a second tab evicts the prior session:

- The prior socket receives `{"type":"error","code":"REPLACED","message":"Another browser joined as alice"}`
- Native state (GStreamer pipeline, ONNX subscriptions) is torn down in the correct order (subscriptions first, participant removal second)
- The new session takes over and audio flows normally

---

## Troubleshooting

**ICE fails / audio doesn't flow**
- Ensure `--network host` is present. Docker Desktop bridge networking breaks webrtcbin's ICE candidate advertisement.
- The testbed is built for peers that can reach the host directly (the same machine or LAN). It
  configures STUN only: `TESTBED_STUN_SERVER` takes a `stun://host:port` URI for the server
  side, and the browser side uses `stun:stun.l.google.com:19302` (hard-coded in
  `frontend/src/lib/peer.ts`). It has no TURN setting, so peers behind NATs that STUN can't
  traverse won't connect. jsyn itself supports TURN (`WebRtcParticipantSpec.Builder.turnServers`);
  to relay through TURN, pass it in `ConferenceService` and add the same server to the
  browser's `iceServers`.

**GStreamer plugin errors on bare metal**
Install the required plugins (Ubuntu/Debian):
```bash
apt-get install libgstreamer1.0-0 gstreamer1.0-plugins-base \
                gstreamer1.0-plugins-good gstreamer1.0-plugins-bad \
                gstreamer1.0-nice
```
These match the image's runtime packages. `gstreamer1.0-nice` provides the ICE transport
webrtcbin needs. The distribution must ship GStreamer 1.26: 1.28 breaks webrtcbin.

**`UnsatisfiedLinkError: missing native: com/synauson/jsyn/natives/linux-x86_64/… — add jsyn-natives-<platform> to your classpath`**
- The `jsyn-natives-linux` jar must be on the runtime classpath. Verify that `./gradlew dependencies --configuration runtimeClasspath` resolves both `com.synauson:jsyn` and `com.synauson:jsyn-natives-linux`.

**Startup fails with `GStreamer sanity check failed: …`**
- GStreamer 1.26 and the plugin packages above must be installed on the host. Run
  `gst-inspect-1.0 --version` to check the version and `gst-inspect-1.0 webrtcbin` to check
  the plugin.

**Startup fails with "license key" in the error**
- Set `SYNAUSON_LICENSE_KEY` (or `TESTBED_LICENSE_KEY`). The runtime won't start without a key, or with one the licensing server refuses.

**No VAD or Turn events**
- Check the startup line `Detectors: VAD …, turn detection …`. A detector is off when the license doesn't include it, or when its model hadn't downloaded within `TESTBED_MODEL_WAIT_SECONDS` (default 120); the `Model …` lines above it say which.
