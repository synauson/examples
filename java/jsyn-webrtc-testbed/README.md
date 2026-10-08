# JSyn WebRTC Testbed

A Docker-published reference implementation that demonstrates how to use the **[jsyn](https://maven.synauson.com/releases/com/synauson/jsyn/)** Java client library to host a live WebRTC audio conference with real-time **VAD** (Voice Activity Detection) and **fermata-1 Detection** events.

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
installed) also starts the whole application against the real runtime.

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
  {sdp_offer}            ───────► JSyn.addWebRtcParticipant()  ──────► webrtcbin
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
- For cross-NAT testing (peers on different networks), run coturn alongside and set `TESTBED_STUN_SERVER=turn://user:pass@coturn.example:3478`.

**GStreamer plugin errors on bare metal**
Install the required plugins (Ubuntu/Debian):
```bash
apt-get install libgstreamer1.0-0 gstreamer1.0-plugins-base \
                gstreamer1.0-plugins-good gstreamer1.0-plugins-bad
```

**`UnsatisfiedLinkError: no synauson_jni in java.library.path`**
- The `jsyn-natives-linux` jar must be on the runtime classpath. Verify that `./gradlew dependencies --configuration runtimeClasspath` resolves both `com.synauson:jsyn` and `com.synauson:jsyn-natives-linux`.

**`Failed to initialise JSyn: GStreamer sanity check failed`**
- GStreamer 1.26 must be installed on the host. Run `gst-inspect-1.0 --version` to verify.

**Startup fails with "license key" in the error**
- Set `SYNAUSON_LICENSE_KEY` (or `TESTBED_LICENSE_KEY`). The runtime won't start without a key, or with one the licensing server refuses.

**No VAD or Turn events**
- Check the startup line `Detectors: VAD …, turn detection …`. A detector is off when the license doesn't include it, or when its model hadn't downloaded within `TESTBED_MODEL_WAIT_SECONDS` (default 120); the `Model …` lines above it say which.
