# jsyn licensing

How to set up Synauson licensing in a jsyn application, with a runnable tour that checks
each behaviour described here against a live runtime. CI runs the tour on every push with a
real free-tier license.

## Setting it up

A runtime needs a license key and two directories that survive restarts:

```java
JSynConfig config = JSynConfig.builder()
        // Omit licenseKey to read $SYNAUSON_LICENSE_KEY.
        .licenseKey(System.getenv("SYNAUSON_LICENSE_KEY"))
        // The cached license file. Put it on a volume.
        .stateDir("/var/lib/synauson/state")
        // The models the license includes, downloaded at startup. Also a volume.
        .modelStore("/var/lib/synauson/models")
        .build();

try (JSyn jsyn = new JSyn(config)) {
    // ...
}
```

`new JSyn` throws only when the key is missing or malformed (`InvalidArgumentException`)
or the licensing server refuses it (`PermissionDeniedException`). If the server can't be
reached, the runtime still starts.

A detector needs its model on disk. Before adding a participant with VAD or turn
detection, wait until `jsyn.capabilities().models` reports `ready` (see
`Tour.awaitEntitledModels`), or handle `FailedPreconditionException`.

When the license has no room for AI on another participant, decide what the call should do.
The tour joins it without AI:

```java
try {
    conference.addNativeParticipant(id, withVad);
} catch (LimitExceededException | PermissionDeniedException e) {
    log.warn("{} joins without AI: {}", id, e.getMessage());
    conference.addNativeParticipant(id, withoutAi);
}
```

For a health endpoint, copy [`LicenseHealth`](src/main/java/com/example/licensing/LicenseHealth.java).
It turns `jsyn.capabilities()` into OK, DEGRADED or FAILING with reasons. `capabilities()`
never touches the network, so polling it is cheap.

## How licensing works

Every runtime runs under a license key; the free tier is a key too. One key can run any
number of runtimes on any number of machines.

At startup the runtime sends the key to `license.synauson.com` and checks out a license
file, a certificate signed by Synauson that lists the license's capabilities and limits.
The runtime verifies the signature with a public key built into jsyn, caches the file as
`license.lic` in the state directory, and renews it about once a day. Server responses are
signed as well, so a spoofed server can't reject or upgrade a key.

Capabilities are the AI features a license can include. Conferencing is in every license.

| Code | Feature |
|---|---|
| `FEATURE_VAD` | Voice activity detection |
| `FEATURE_TURN_DETECTION` | Turn detection end-of-turn detection, including the VAD that drives it |

Asking for a capability the license lacks throws `PermissionDeniedException` naming the
code. The runtime downloads only the models that some licensed capability uses.

Limits count what runs at the same time: conferences, AI conferences (a conference with at
least one AI participant) and streams per capability (the participants it runs on). The VAD
inside turn detection counts no VAD stream. A limit is checked only when something new
starts. Past the limit, plus the license's overdraft if it has one, the new thing fails with
`LimitExceededException`. Calls already running are never stopped, whatever happens to the
license or the licensing server. For now each runtime counts its own usage
(`capabilities().limitsScope` is `"this instance"`).

A runtime without a usable license file runs at the free-tier floor: 10 conferences, 2 of
them with AI, both capabilities. That happens when the server hasn't answered yet and there
is no cache, when the runtime is offline without a file, or when the cached file expires
during a long outage.

## License states

`capabilities().license` reports the state and where the license file came from:

| State | Source | Meaning | `LicenseHealth` |
|---|---|---|---|
| `licensed` | `server` | Checked out or renewed by this runtime | OK |
| `licensed` | `cache` | The server is unreachable; the cached file works until `fileExpiry`, about 30 days after the last renewal | DEGRADED |
| `licensed` | `provided` | Running on the `licenseFile` you configured | OK, DEGRADED in the last 7 days before `fileExpiry` |
| `free-tier-floor` | | No usable license file; the runtime upgrades when one arrives, unless it's offline | DEGRADED |
| `rejected` | | The server refused the license while it was running; new work fails, live calls continue | FAILING |

`LicenseHealth` also reports DEGRADED while any limit is fully used.

## Configuration

| `JSynConfig` | Fallback | Default |
|---|---|---|
| `licenseKey` (required) | `$SYNAUSON_LICENSE_KEY`, read only when `licenseKey` is `null` | |
| `stateDir` | `$SYNAUSON_STATE_DIR` | `~/.local/state/synauson`, `%LOCALAPPDATA%\synauson\state` |
| `modelStore` | `$SYNAUSON_MODEL_STORE` | `~/.cache/synauson/models`, `%LOCALAPPDATA%\synauson\models` |
| `licenseFile` | | none |
| `offline` | | `false` |

## Errors

| Exception | Cause | What to do |
|---|---|---|
| `InvalidArgumentException` from `new JSyn` | Missing or malformed key | Set the key |
| `PermissionDeniedException` from `new JSyn` | The server refused the key: `NOT_FOUND`, `SUSPENDED`, `EXPIRED` or `BANNED` | Contact Synauson |
| `PermissionDeniedException` adding a participant | The license lacks the capability, or was refused while running | Add the participant without it, or upgrade |
| `LimitExceededException` | A license limit, or your own `maxConferences` or `maxParticipantsPerConference`; the message names it | Degrade, retry later, or raise the limit |
| `FailedPreconditionException` adding a participant | The capability's model isn't downloaded yet | Wait for `ready` in `capabilities().models` |

## Deploying

In containers and Kubernetes, pass the key as `SYNAUSON_LICENSE_KEY` from a Secret and mount
volumes at the state directory and the model store. Don't bake `license.lic` into an image,
because it contains the key. New instances start even while the licensing server is down:
they use the cached file, or the free-tier floor, and upgrade when the server answers.

On an air-gapped host, set `offline(true)` and `licenseFile(...)`, and fill the model store
beforehand. The license file can come from Synauson, or it can be the `license.lic` from a
connected machine that uses the same key; a file only works with the key it was checked out
with. Copy the model store from a connected machine, or import the models with
`JSyn.importModels`. Replace the file before its `fileExpiry`.

A connected runtime also uses a configured `licenseFile` whenever it can't reach the
server, which protects a first start during an outage.

## Running the tour

You need `SYNAUSON_LICENSE_KEY` (a free-tier key works) and HTTPS access to
`license.synauson.com` and `dl.synauson.com`. In a container, with podman or docker:

```bash
podman build -t jsyn-licensing-tour .
podman run --rm -e SYNAUSON_LICENSE_KEY -v jsyn-licensing-tour:/tour jsyn-licensing-tour
```

On the host, with Java 21 and GStreamer 1.26 (`gradlew.bat` on Windows):

```bash
./gradlew run                              # every chapter
./gradlew run --args="air-gapped limits"   # some of them
```

The `online` chapter checks out the license file and downloads the models that the later
chapters reuse, so run it once before running others on their own. The tour writes its files
under `build/licensing-tour/`, or `/tour` in the container.

| Chapter | Shows |
|---|---|
| `startup` | Which keys stop a runtime from starting |
| `online` | Check-out, the capabilities report, the cached file, model downloads, a restart on the cache |
| `capabilities` | Entitlement checks and how streams and AI conferences are counted |
| `air-gapped` | Running offline on a provided file, a file used with the wrong key, the free-tier floor |
| `limits` | Refusals, joining without AI, freed slots, the conference limit, your own `maxConferences` |
