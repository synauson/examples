# CLAUDE.md: WebRTC testbed

Spring Boot 3.5 (Java 21) + React/Vite/Tailwind. Browsers open `/room/<id>`, signal over
`/ws/room/<id>`, and send audio through one jsyn conference; VAD and Smart Turn events fan
out to every socket. The README covers running it and the architecture.

## Where things are

- `room/ConferenceService.java`: the jsyn orchestration and the most delicate code here.
  It relies on these invariants:
  - The same participant id joining again evicts the old session
    (the old socket gets `REPLACED`).
  - Epoch tokens drop callbacks from superseded sessions.
  - Cleanup compares references, so a late close from an old socket can't remove the
    current session.
  - Teardown removes the event subscriptions first, then the participant.
- `config/AiFeatures.java`: waits (up to `TESTBED_MODEL_WAIT_SECONDS`) for the licensed
  models, then enables only the detectors the license includes. The startup line
  `Detectors: VAD on, turn detection on` is asserted by CI (`testbed-image`).
- `signaling/envelope/`: the JSON wire format. It is mirrored by `frontend/src/lib/types.ts`
  and `signaling.ts`, and pinned by `EnvelopeJsonTest`. Change both sides together.
- `frontend/src/lib/probBucket.ts`: Smart Turn probability buckets. The thresholds (0.65 /
  0.20) are calibrated against observed Smart Turn v3 output; the tests pin them.

## Building and testing

- `npm run build` writes into `../src/main/resources/static/` (git-ignored), and
  `processResources` depends on it. `-PskipFrontend` skips it for Java-only iteration.
- `./gradlew test` forks a JVM per test class (`forkEvery 1`): GStreamer and ONNX Runtime are
  process-global. With `SYNAUSON_LICENSE_KEY` set, `TestbedApplicationTests` starts the whole
  app against the real runtime. `TESTBED_EXPECT_DETECTORS=true` also requires both detectors.
- The image runs on `debian:trixie` for GStreamer 1.26. Don't move it to a
  `eclipse-temurin` runtime image (Ubuntu, GStreamer 1.28 breaks webrtcbin's pad API).
- `--target local-natives` compiles `libsynauson_jni.so` from a synauson checkout placed at
  `_synauson/` in the build context and patches it into the fat jar. The nested jar must stay
  STORED (`zip -0`). The synauson repo's own dev loop drives this target.
- Run it with `--network host` on Linux: webrtcbin advertises the host's address in ICE.
