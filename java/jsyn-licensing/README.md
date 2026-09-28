# jsyn licensing tour

A runnable, self-checking tour of how Synauson licensing works in a jsyn application:
startup, the license file, capabilities, air-gapped hosts and usage limits. Each chapter
starts real jsyn runtimes, does what an application would do, and **checks every claim it
prints**. If the runtime behaves differently, the tour exits non-zero, and CI runs it on
every push against a real free-tier license.

Read the output next to the source. [`LicenseHealth`](src/main/java/com/example/licensing/LicenseHealth.java)
is meant to be copied into your application.

## Run it

You need a license key in `SYNAUSON_LICENSE_KEY` (a free-tier key works) and HTTPS access to
`license.synauson.com` and `dl.synauson.com`.

**In a container** (only podman or docker needed):

```bash
podman build -t jsyn-licensing-tour .
podman run --rm -e SYNAUSON_LICENSE_KEY -v jsyn-licensing-tour:/tour jsyn-licensing-tour
podman run --rm -e SYNAUSON_LICENSE_KEY -v jsyn-licensing-tour:/tour jsyn-licensing-tour limits
```

**On the host** (Java 21 and GStreamer 1.26; on Windows, `gradlew.bat`):

```bash
./gradlew run                              # every chapter, in order
./gradlew run --args="air-gapped limits"   # some chapters
```

`just tour`, `just tour-container` and `just clean` wrap these. The tour keeps its files
under `build/licensing-tour/` (`/tour` in the container). The `online` chapter checks out
the license file and downloads the models that later chapters reuse, so run it at least once
before running other chapters on their own.

| Chapter | What it shows |
|---|---|
| `startup` | What stops a runtime from starting: a missing or malformed key (`InvalidArgumentException`), and a signed rejection from the server (`PermissionDeniedException`). An unreachable server never does. |
| `online` | Check-out, the `capabilities()` report, the cached license file (mode 0600), licensed model downloads, restarting on the cache during an outage. |
| `capabilities` | Entitlements and how streams and AI conferences are counted. Turn detection includes VAD for free. |
| `air-gapped` | `offline(true)` with a provided license file, the file being bound to its key, and the free-tier floor. |
| `limits` | Refusal of new work only, degrading to no AI, releasing slots, the conference limit, and your own `maxConferences` cap. |

## How licensing works

**Every runtime runs under a key.** There is no keyless mode; the free tier is a key too.
One key runs any number of runtimes on any number of machines. Seats aren't counted.

**The key buys a license file.** At startup the runtime sends the key to
`license.synauson.com` and checks out a *license file*: a certificate signed by Synauson
listing the license's capabilities and usage limits. The runtime verifies it with a public
key built into jsyn and caches it in the state directory as `license.lic`. It renews the
file about once a day. Server responses are signed as well, so a spoofed server can neither
reject nor upgrade your key.

**Capabilities** are the AI features a license includes:

| Code | Feature | Notes |
|---|---|---|
| `FEATURE_VAD` | Voice activity detection | |
| `FEATURE_TURN_DETECTION` | Smart Turn end-of-turn detection | Includes the VAD that drives it, which counts no VAD stream |

Conferencing is in every license. A capability the license lacks throws
`PermissionDeniedException` naming the code. Models that no licensed capability uses are
never downloaded.

**Limits** count concurrent use, and `null` means unlimited:

| Limit | Counts |
|---|---|
| conferences | running conferences |
| AI conferences | conferences with at least one AI stream (one per conference, however many streams) |
| streams, per capability | participants that capability runs on |

Limits are checked only when something new starts: a conference, or an AI capability on a
participant. Above the limit (plus the license's *overdraft*, e.g. 0.25 admits up to 125%,
rounded up, with the excess logged as overage) the new thing is refused with
`LimitExceededException`. **Nothing already running is ever stopped**, not by limits, not by
a suspended license and not by an outage. Today each runtime counts on its own
(`capabilities().limitsScope` is `"this instance"`).

**The free-tier floor** is the least a runtime with a well-formed key ever gets: 10
conferences, 2 of them with AI, both capabilities. A runtime runs at the floor when it has
no usable license file: the server hasn't answered yet and there's no cache, it's offline
without a file, or the file expired during a long outage.

### License states

`capabilities().license.state`, and what to do about each:

| State | Source | Meaning | `LicenseHealth` |
|---|---|---|---|
| `licensed` | `server` | Checked out or renewed by this process. | OK |
| `licensed` | `cache` | The server is unreachable, so the runtime runs on the cached file until `fileExpiry` (about 30 days after the last renewal). | DEGRADED |
| `licensed` | `provided` | Running on your `licenseFile`: offline, or the server is unreachable. | OK until 7 days before `fileExpiry` |
| `free-tier-floor` | — | No usable license file. The runtime upgrades itself when one arrives, unless it is offline. | DEGRADED |
| `rejected` | — | The server refused the license while it was running (suspended, expired). New work is refused; live calls continue. | FAILING |

`LicenseHealth` also reports DEGRADED while any limit is fully in use.

### Configuration

| `JSynConfig` | Environment fallback | Default |
|---|---|---|
| `licenseKey` (required) | `SYNAUSON_LICENSE_KEY` (used only when `licenseKey` is `null`) | — |
| `stateDir` (license cache) | `SYNAUSON_STATE_DIR` | `~/.local/state/synauson`, `%LOCALAPPDATA%\synauson\state` |
| `modelStore` | `SYNAUSON_MODEL_STORE` | `~/.cache/synauson/models`, `%LOCALAPPDATA%\synauson\models` |
| `licenseFile` | — | none |
| `offline` | — | `false` |

### Errors

| Exception | When | Fix |
|---|---|---|
| `InvalidArgumentException` from `new JSyn` | Missing or malformed key | Set the key |
| `PermissionDeniedException` from `new JSyn` | The server refused the key (`NOT_FOUND`, `SUSPENDED`, `EXPIRED`, `BANNED`) | Contact Synauson |
| `PermissionDeniedException` adding a participant | The license lacks that capability, or was rejected while running | Upgrade, or add the participant without it |
| `LimitExceededException` | A license limit, or your own `maxConferences` / `maxParticipantsPerConference` | Wait for capacity, degrade, or raise the limit. The message says which limit it was |
| `FailedPreconditionException` adding a participant | The capability's model isn't in the store yet | Wait for `capabilities().models` to report `ready` |

## Deploying

- **Containers and Kubernetes:** pass the key as `SYNAUSON_LICENSE_KEY` from a Secret. Mount
  volumes at the state directory and the model store so restarts reuse the license file and
  the models. Never bake `license.lic` into an image: it embeds your key.
- **Autoscaling:** a new instance never fails to start because the licensing server is down.
  It starts on its cached file, or at the free-tier floor, and upgrades when the server
  answers.
- **Air-gapped hosts:** set `offline(true)`, give the host a `licenseFile`, and fill its
  model store. The file can be one from Synauson or a `license.lic` copied from a connected
  machine using the same key. Copy the model store from a connected machine, or use
  `JSyn.importModels`. Replace the file before its `fileExpiry`.
- **Monitoring:** poll `capabilities()`. It never touches the network. Serve
  `LicenseHealth` from your health endpoint, and alert on DEGRADED lasting more than a day or
  on any FAILING.
