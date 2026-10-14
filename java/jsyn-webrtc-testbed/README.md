# jsyn WebRTC testbed

A Spring Boot and React application that hosts a live WebRTC audio conference with
[jsyn](https://github.com/synauson/jsyn). Up to two browsers open `/room/<name>`,
where `<name>` is the participant's name (1 to 128 letters, digits, `.`, `_` or `-`,
the ids jsyn accepts), and join the one jsyn conference. They hear
each other through it, and every voice activity (VAD) and turn detection event for
either participant streams live to both pages. It is published as a container image.

## What it shows

- WebRTC signaling over a WebSocket: the browser's SDP offer, jsyn's answer, and
  trickle ICE both ways.
- Routing: every participant hears every other, and the routes are rebuilt as people
  join and leave.
- Detector events fanned out to every browser, with only the detectors whose models
  are ready.
- Rejoining: the same name joining again replaces the old session cleanly.
- Startup and shutdown in the right order: waiting for the models, and on
  teardown closing subscriptions before participants.

| Code | What to read it for |
|---|---|
| [`ConferenceService`](src/main/java/com/synauson/testbed/room/ConferenceService.java) | The jsyn calls: adding WebRTC participants, ICE, routing, detector subscriptions, rejoin and teardown |
| [`AiFeatures`](src/main/java/com/synauson/testbed/config/AiFeatures.java) | Waiting for the models and turning on only the detectors that can run |
| [`JSynConfiguration`](src/main/java/com/synauson/testbed/config/JSynConfiguration.java) | Creating the runtime and the conference as Spring beans |
| [`RoomWebSocketHandler`](src/main/java/com/synauson/testbed/signaling/RoomWebSocketHandler.java) | The signaling messages |
| [`peer.ts`](frontend/src/lib/peer.ts) | The browser side: `RTCPeerConnection`, the offer, ICE |

## What you need

- A Synauson license key in `SYNAUSON_LICENSE_KEY`. Any key runs the conference and the
  VAD and turn events.
- A Linux host with Docker. The container uses host networking, because WebRTC
  advertises the host's own network addresses to the browsers. Docker Desktop on macOS
  and Windows doesn't give a container those addresses, even with its host networking
  turned on, so run the testbed on a Linux machine.
- A browser that can reach that host: the same machine, or one on the same network.
- HTTPS access to `usage.synauson.com`, `license.synauson.com`, `status.synauson.com`
  and Cloudflare R2 (`*.r2.cloudflarestorage.com`), where the models download from, and
  a synchronized clock (NTP). jsyn's
  [network and firewall](https://github.com/synauson/jsyn/blob/v1.7.0/docs/install.md#network-and-firewall) table says what each is for.
  The testbed also asks a public STUN server (`stun.l.google.com`, UDP 19302) for its
  public address.

## Run it

```bash
docker run --rm --network host \
  -e SYNAUSON_LICENSE_KEY \
  -v synauson-testbed-models:/var/lib/synauson/models \
  -v synauson-testbed-state:/var/lib/synauson/state \
  cr.synauson.com/synauson/synauson-webrtc-testbed:latest
```

Then open `http://localhost:8080/room/alice` and click **Join the room**. Open
`http://localhost:8080/room/bob` in a second browser or tab to talk to yourself. One
browser is enough to see the events: speak, and they appear in the Event stream panel.

The image is tagged `latest` (the newest release) and with each release's version, such
as `1.7.0`. The two volumes keep the downloaded models and the cached license across
restarts. To check that an image was built from this repository's release, use the
[GitHub CLI](https://cli.github.com/):
`gh attestation verify oci://cr.synauson.com/synauson/synauson-webrtc-testbed:1.7.0 -R synauson/examples`.

## What you should see

At startup the log says which detectors are on:

```text
License: license '…' (…). Detectors: VAD on, turn detection on
```

A detector is `off` when its model hadn't downloaded within the wait
(`TESTBED_MODEL_WAIT_SECONDS`), or when the license starts nothing new (`rejected` or
`disconnected`); the `Model …` lines above it and the `License:` line say which. In
the browser, each participant's tile shows SPEAKING or LISTENING, and the Event stream
panel lists each speech start and end and each turn-detection result with
its probability.

## Configuration

Set these as environment variables (`-e` for the container). The defaults are in
[`application.yml`](src/main/resources/application.yml).

| Variable | Default | Effect |
|---|---|---|
| `SYNAUSON_LICENSE_KEY` | none | The license key, used when `TESTBED_LICENSE_KEY` is blank |
| `TESTBED_LICENSE_KEY` | blank | The license key, if you'd rather not use `SYNAUSON_LICENSE_KEY` |
| `TESTBED_SERVER_PORT` | `8080` | The HTTP and WebSocket port |
| `TESTBED_CONFERENCE_ID` | `testbed` | The id of the one conference every browser joins |
| `TESTBED_MODEL_STORE` | `/var/lib/synauson/models` | Where the runtime downloads the models |
| `TESTBED_STATE_DIR` | `/var/lib/synauson/state` | Where the runtime caches the license file |
| `TESTBED_MODEL_WAIT_SECONDS` | `120` | How long startup waits for the models before running without them |
| `TESTBED_STUN_SERVER` | `stun://stun.l.google.com:19302` | The server side's STUN server, as `stun://host:port` |
| `TESTBED_VAD_THRESHOLD` | `0.5` | VAD's speech probability threshold, above 0 and below 1 |
| `TESTBED_VAD_MIN_SILENCE_MS` | `100` | Silence before a speech end |
| `TESTBED_VAD_MIN_SPEECH_MS` | `250` | Speech before a speech start |
| `TESTBED_TURN_DETECTION_BUFFERED_SAMPLES` | `160` | The audio, in 16 kHz samples, a participant must have sent before turn detection decides. Values under 4000 (250 ms) count as 4000, and more than 128000 is refused |
| `TESTBED_TURN_DETECTION_CONFIDENCE_THRESHOLD` | `0.65` | The probability at which a turn counts as complete |

## Build and develop

Build the image the same way the published one is built, with the repository's root as
the build context:

```bash
cd java/jsyn-webrtc-testbed
just build-image      # then: just run
```

To work on the code, run the backend and the frontend on the host. That needs a JDK 21,
Node 22 and GStreamer 1.24 or 1.26:

```bash
# Terminal 1: Spring Boot on port 8080, with the models and license cache under build/synauson
cd java/jsyn-webrtc-testbed
export SYNAUSON_LICENSE_KEY=<your license key>
just dev

# Terminal 2: the Vite dev server on port 5173, which forwards /ws to port 8080
cd java/jsyn-webrtc-testbed/frontend
npm install
npm run dev
```

Open `http://localhost:5173/room/alice`; the page reloads as you edit. For Java-only
changes, `./gradlew bootRun -PskipFrontend` skips the frontend build. Run `just` to list
every recipe.

## How it works

```text
Browser /room/<id>              Spring Boot (port 8080)                jsyn
──────────────────              ───────────────────────                ────
                                GET /, GET /room/<id>  -> the React app
open WS /ws/room/<id>  ------>  RoomWebSocketHandler
                                ConferenceService
{sdp_offer}            ------>  conference.addWebRtcParticipant()  ->  WebRTC participant
{ice_candidate}        ------>  handle.addIceCandidate()           ->  WebRTC participant
                       <------  {sdp_answer}
                       <------  {ice_candidate}
                       <------  VAD and turn events, sent to every socket
```

When the same participant name opens the page again, for example after a refresh or in
a second tab, the new session replaces the old one:

- The old socket receives `{"type":"error","code":"REPLACED","message":"Another browser joined as alice"}`.
- The old session is torn down in order: its event subscriptions first, then its
  participant.
- Each session carries an epoch, so late callbacks from the old session are ignored, and
  a late close from the old socket can't remove the new session.

## Troubleshooting

| What you see | Cause and fix |
|---|---|
| The page loads, but ICE fails and no audio flows | The container isn't on the host network (`--network host`), or it runs under Docker Desktop on macOS or Windows. Run it on a Linux host. |
| It works on the LAN but not across the internet | The testbed configures STUN only, with no TURN relay, so peers behind NATs that STUN can't get through won't connect. The browser side's STUN server is `stun:stun.l.google.com:19302`, set in [`peer.ts`](frontend/src/lib/peer.ts). jsyn itself supports TURN (`WebRtcParticipantSpec.Builder.turnServers`); to relay through TURN, pass a server in `ConferenceService` and add the same one to the browser's `iceServers`. |
| A third browser gets `CONFERENCE_FULL` | The testbed takes two participants at a time. Close one of the other pages first. |
| Startup fails with "license key" in the error | Set `SYNAUSON_LICENSE_KEY` (or `TESTBED_LICENSE_KEY`). The runtime doesn't start without a key, or with one the licensing server refuses. |
| No VAD or turn events | Check the startup line `Detectors: VAD …, turn detection …`. A detector is off when its model hadn't downloaded within `TESTBED_MODEL_WAIT_SECONDS`, or when the license starts nothing new (`rejected` or `disconnected`). |
| A host run fails with `GStreamer sanity check failed: …` | Install GStreamer 1.24 or 1.26 and its plugins; see jsyn's [installation guide](https://github.com/synauson/jsyn/blob/v1.7.0/docs/install.md#linux). `gst-inspect-1.0 webrtcbin` checks the WebRTC plugin. |
| A host run fails with `UnsatisfiedLinkError: missing native: …` | `jsyn-natives-linux` isn't on the classpath. `./gradlew dependencies --configuration runtimeClasspath` should list it next to `com.synauson:jsyn`. |
