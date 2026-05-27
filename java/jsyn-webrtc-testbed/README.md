# JSyn WebRTC Testbed

A Docker-published reference implementation that demonstrates how to use the **[jsyn](https://nexus.benashby.com/repository/maven-public/com/synauson/jsyn/)** Java client library to host a live WebRTC audio conference with real-time **VAD** (Voice Activity Detection) and **Smart-Turn Detection** events.

Two browsers connect at `/room/<id>`, audio flows through synauson, and every inference event for every participant is streamed live to both browsers and rendered in a developer-observability UI.

> **Single-participant mode is fully functional.** Open just one browser at `/room/alice`, click Join, and speak — VAD and Turn events appear in the event rail immediately. No second browser required to evaluate the detection pipeline.

---

## Run from Docker Hub

```bash
docker run --rm \
  --network host \
  -e TESTBED_CONFERENCE_ID=demo \
  bashby/synauson-webrtc-testbed:latest
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
cd examples/jsyn-webrtc-testbed
./gradlew bootRun

# Terminal 2 — Vite dev server (port 5173, proxies /ws → 8080)
cd examples/jsyn-webrtc-testbed/frontend
npm run dev
```

Open `http://localhost:5173/room/alice` — React Fast Refresh updates on every file save; WebSocket signalling tunnels to the Spring backend.

For Java-only iteration (skip the npm build):

```bash
./gradlew bootRun -PskipFrontend
```

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
- Single-participant mode: VAD/Turn detection runs with just one browser; `rewireMesh()` produces an empty `ConnectionMatrix` (no audio routing) but the detector subscriptions are live

See `docs/superpowers/specs/2026-05-26-jsyn-webrtc-testbed-design.md` for the full architecture.

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
- The `jsyn-natives-linux` jar must be on the runtime classpath. Verify Nexus credentials are set (`NEXUS_USER`/`NEXUS_PASSWORD`) and that `./gradlew dependencies --configuration runtimeClasspath` resolves both `com.synauson:jsyn` and `com.synauson:jsyn-natives-linux`.

**`Failed to initialise JSyn: GStreamer sanity check failed`**
- GStreamer 1.26 must be installed on the host. Run `gst-inspect-1.0 --version` to verify.
