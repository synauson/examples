# CLAUDE.md

Complete reference applications for [jsyn](https://github.com/synauson/jsyn), the Java
library that runs the Synauson media engine in-process. This repository is public, and its
code is read as documentation by developers copying it into their own applications. Write
for them.

jsyn's own README and tests document the SDK API; link there instead of restating it here.
The engine is [synauson](https://github.com/synauson/synauson), a private repository.

## Layout

Each example under `java/` is a self-contained Gradle project with its own wrapper and
README. There is no root build and no root justfile: `cd` into the example first.

| Example | What | Runs on |
|---|---|---|
| `java/jsyn-webrtc-testbed` | Spring Boot + React WebRTC conference with live VAD / turn detection events; published as a container image. See its own `CLAUDE.md` | Linux (container) |
| `java/jsyn-licensing` | How to set up licensing, with a tour that checks it against a live runtime | Linux, Windows, container |
| `java/jsyn-windows-quickstart` | Minimal Gradle project: file playback, native audio I/O, VAD | Windows |

## Commands

```bash
# testbed
cd java/jsyn-webrtc-testbed && just            # lists recipes: build-image, up, run, dev, test, ...
./gradlew build                                # unit tests; with SYNAUSON_LICENSE_KEY also the full-app test
cd frontend && npm test                        # vitest

# licensing tour (needs SYNAUSON_LICENSE_KEY and network)
cd java/jsyn-licensing && just tour-container  # every chapter, in a container
./gradlew run --args="online limits"           # `online` downloads what later chapters reuse; run it first

# windows quickstart (Windows only)
.\gradlew.bat run
.\gradlew.bat runNativeIOExample
.\gradlew.bat runVadExample --args="C:\path\speech.wav"
```

Host runs need GStreamer 1.26 (1.28 changed the webrtcbin pad API, which breaks the testbed) and
the Java version of the example: 21 for the testbed and the tour, 11 for the quickstart.
When the host lacks them, use the container recipes.

## Rules

- Examples use the released `com.synauson:jsyn` and `jsyn-natives-*` from
  `https://maven.synauson.com/releases` (no credentials). Both versions are pinned in each
  example's `build.gradle.kts` and repeated in the quickstart README's dependency snippet.
  Bump every example and that snippet together. jsyn and the natives drift: an
  engine-only fix is a natives release with no jsyn release, so pin the newest natives
  that jsyn's own `gradle.properties` (`jsynNativesVersion`) tests.
- `publish-webrtc-testbed.yml` reads the natives version by grepping `jsynNativesLinuxVersion`
  in the testbed's `build.gradle.kts`. Don't rename or inline that `val`.
- Every runtime needs `SYNAUSON_LICENSE_KEY`; there is no keyless mode.
  Never commit a key or a `license.lic` (it embeds the key). jsyn behaviour around licensing
  is documented and tested in `java/jsyn-licensing`; check it there before explaining it
  anywhere else.
- Runtimes download the models their license includes at startup. Code that
  adds a detector must wait for `jsyn.capabilities().models` to report `ready`, or handle
  `FailedPreconditionException`.
- Subscribe to a participant's VAD / turn detection events *after*
  adding the participant (the participant carries the detector). Tear down in reverse:
  subscriptions, then the participant, then the conference.
- CI runs every example against the real runtime and a real license. When you change
  behaviour, update the assertion that checks it.
- Public repository: no personal names, emails, internal hostnames, home paths or
  credential-store names in files or commit messages.

## CI

`.github/workflows/ci.yml` runs on every branch push. Its jobs:

| Job | Runner | What | Required for main |
|---|---|---|---|
| `testbed` | `gh-runner-l` (self-hosted Linux) | frontend tests, then `./gradlew build` with `SYNAUSON_LICENSE_KEY` and `TESTBED_EXPECT_DETECTORS=true` | yes |
| `testbed-image` | `ubuntu-latest` | builds the image, runs it, waits for health and greps the `Detectors:` line | yes |
| `quickstart` | `gh-runner-w` (self-hosted Windows) | GStreamer runtime MSI, `gst-inspect-1.0 coreelements`, then all three examples; the speech fixture comes from the private synauson repo via the read-only `SYNAUSON_DEPLOY_KEY` | yes |
| `licensing` | `gh-runner-l` | the whole tour with the free-tier `LICENSING_TOUR_LICENSE_KEY` | **no** |

`licensing` is not a required check, so main can move while the tour is red: check its
result yourself before calling a change done.

`.github/workflows/publish-webrtc-testbed.yml` runs on pushes to main that touch the testbed
(`ubuntu-latest`). It waits for the pinned `jsyn-natives-linux` POM to appear, then pushes
the image (tags `latest` and `<branch>-<sha>-<timestamp>`) with the `REGISTRY_*` secrets;
it is pulled as `cr.synauson.com/synauson/synauson-webrtc-testbed`.

The testbed Dockerfile's `local-natives` target is driven from the synauson repo's justfile
(`testbed-build-image`, which locates this checkout through `TESTBED_EXAMPLES_REPO`). Keep
that target and its `_synauson/` build-context layout working.

## Keeping these files true

This file, `java/jsyn-webrtc-testbed/CLAUDE.md` (each has an `AGENTS.md` link to it)
and each example's README are what agents and developers act on. A change is not done
until they are true again, in the same commit:

- Before you finish, reread what covers your change and fix, delete or add what the
  next agent needs. SDK facts belong in jsyn's README; point there.
- `python3 tools/check-agent-docs.py` (CI's `agent-docs` job) fails on dead paths,
  `just` recipes, Gradle tasks, npm scripts and env vars these files name. It can't
  tell whether a sentence is still true. The script is a copy of the synauson repo's;
  change it there first.
- Subagents follow this too: list any stale sentence you couldn't fix in your report.
  When you delegate work, put the first bullet in the prompt.

## Commits

- Only the `synauson[bot]` GitHub App writes branches and tags (rulesets). The maintainer
  commits through the `synauson-bot` tool (`synauson-bot help` has the procedure); never
  `git push` with a personal identity. Leave changes as uncommitted edits unless told
  otherwise.
- Pull requests are disabled. `main` is append-only and fast-forwards only to a commit whose
  required checks passed.
- Commit messages are a subject only: lowercase, imperative, under 60 characters, no prefix,
  no body, no trailers, no AI attribution.
