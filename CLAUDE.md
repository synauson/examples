# CLAUDE.md

Reference applications for [jsyn](https://github.com/synauson/jsyn), the Java library that
runs the Synauson media engine in-process. **This repository is public.** Everything in it
is customer-facing documentation that happens to compile, so write for someone copying it
into their own application.

## Layout

Each example under `java/` is a self-contained Gradle project with its own wrapper and
README. There is no root build: `cd` into the example first.

| Example | What | Runs on |
|---|---|---|
| `java/jsyn-webrtc-testbed` | Spring Boot + React WebRTC conference with live VAD / Smart Turn events; published as a container image. See its own `CLAUDE.md` | Linux (container) |
| `java/jsyn-licensing` | Self-checking tour of licensing: keys, license files, capabilities, air-gapped hosts, limits | Linux, Windows, container |
| `java/jsyn-windows-quickstart` | Minimal Gradle project: file playback, native audio I/O, VAD | Windows |

## Commands

```bash
# testbed
cd java/jsyn-webrtc-testbed && just            # lists recipes: build-image, up, dev, test, ...
./gradlew build                                # unit tests; with SYNAUSON_LICENSE_KEY also the full-app test
cd frontend && npm test                        # vitest

# licensing tour (needs SYNAUSON_LICENSE_KEY and network)
cd java/jsyn-licensing && just tour-container  # or: ./gradlew run --args="limits"

# windows quickstart (Windows only)
.\gradlew.bat run; .\gradlew.bat runVadExample --args="C:\path\speech.wav"
```

Host runs need GStreamer **1.26** (not 1.28: its webrtcbin pad API breaks the testbed) and
the Java version of the example: 21 for the testbed and the tour, 11 for the quickstart.
When the host lacks them, use the container recipes.

## Rules

- **jsyn version:** examples use the released `com.synauson:jsyn` and the matching
  `jsyn-natives-*` from `https://maven.synauson.com/releases` (no credentials). Versions are
  pinned in each `build.gradle.kts` (currently `1.4.0`). Bump every example together, and
  keep jsyn and its natives equal.
- **License keys:** every runtime needs `SYNAUSON_LICENSE_KEY`; there is no keyless mode.
  Never commit a key or a `license.lic` (it embeds the key). jsyn behaviour around licensing
  is documented and tested in `java/jsyn-licensing`; check it there before explaining it
  anywhere else.
- **Models:** runtimes download the models their license includes at startup. Code that
  adds a detector must wait for `JSyn.capabilities().models` to report `ready`, or handle
  `FailedPreconditionException`.
- **Event subscriptions:** subscribe to a participant's VAD / Smart Turn events *after*
  adding the participant (the participant carries the detector). Tear down in reverse:
  subscriptions, then the participant, then the conference.
- **Examples are verified, not illustrative.** CI runs each one against the real runtime and
  a real license. When you change behaviour, change the assertion that proves it too.
- **Public content:** no personal names, emails, internal hostnames or local paths in files
  or commit messages.

## CI and commits

`.github/workflows/ci.yml` runs on every branch push; pull requests are disabled, and `main`
only fast-forwards to a commit whose checks passed. Jobs: `testbed` (self-hosted Linux,
needs `SYNAUSON_LICENSE_KEY`), `testbed-image` (builds and health-checks the image),
`licensing` (the tour, with the free-tier `LICENSING_TOUR_LICENSE_KEY`), and `quickstart`
(self-hosted Windows; pulls a speech fixture from the private synauson repo with the
read-only `SYNAUSON_DEPLOY_KEY`).

Commits are made by the `synauson[bot]` GitHub App through maintainer tooling outside this
repo. Don't commit or push with a personal git identity. Commit subjects are lowercase,
imperative, under 60 characters, with no prefix, no body trailers and no AI attribution.
