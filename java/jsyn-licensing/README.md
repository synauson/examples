# jsyn licensing

How to set up Synauson licensing in a jsyn application, with a tour that checks each
behaviour against a live runtime and prints what it found. jsyn's
[licensing guide](https://github.com/synauson/jsyn/blob/v1.6.0/docs/licensing-and-models.md)
explains how licensing works; this example shows it happening, and holds code you can
copy.

## What it shows

The tour runs in chapters, each a small program against its own runtime:

| Chapter | What it shows | Code to read |
|---|---|---|
| `startup` | Which keys stop a runtime from starting: none, malformed, unknown to the licensing server | [`StartupChapter`](src/main/java/com/example/licensing/StartupChapter.java) |
| `online` | Checking out a license file through the usage service, the capabilities report, the share of the session pool, the cached file, model downloads, a restart on the cache | [`OnlineChapter`](src/main/java/com/example/licensing/OnlineChapter.java) |
| `capabilities` | Every license has every AI feature, and how participants take AI sessions | [`CapabilitiesChapter`](src/main/java/com/example/licensing/CapabilitiesChapter.java) |
| `air-gapped` | Running offline on a license file, a file used with the wrong key, and an offline start with no license file, which is refused | [`AirGappedChapter`](src/main/java/com/example/licensing/AirGappedChapter.java) |
| `limits` | Refusals at the session limit, joining without AI, sessions freed, your own `maxConferences` | [`LimitsChapter`](src/main/java/com/example/licensing/LimitsChapter.java) |

Two pieces are written to be copied into your application:

- [`LicenseHealth`](src/main/java/com/example/licensing/LicenseHealth.java) turns
  `jsyn.capabilities()` into OK, DEGRADED or FAILING with reasons, for a health
  endpoint. `capabilities()` never touches the network, so polling it is cheap.
- [`Tour.awaitEntitledModels`](src/main/java/com/example/licensing/Tour.java) waits
  until every model has downloaded, before you add participants
  with AI.

## What you need

- A Synauson license key in `SYNAUSON_LICENSE_KEY`. Any key works. With a Free key
  (2 AI sessions) the `limits` chapter fills the pool and shows the refusal; with a
  larger pool it skips that part. Every runtime on the key shares its pool, so run the
  tour where nothing else uses the key.
- HTTPS access to `usage.synauson.com`, `license.synauson.com`, `status.synauson.com`
  and Cloudflare R2 (`*.r2.cloudflarestorage.com`), where the models download from, and
  a synchronised clock (NTP). jsyn's
  [network and firewall](https://github.com/synauson/jsyn/blob/v1.6.0/docs/install.md#network-and-firewall) table says what each is for.
- In a container: Docker. On the host: a JDK 21, which Gradle also runs on (from
  `JAVA_HOME`, else the `java` on `PATH`; this Gradle version doesn't run on JDK 25),
  and on Linux GStreamer 1.24 or 1.26, or on Windows the GStreamer 1.26.7 MSVC runtime
  and the Visual C++ Redistributable. jsyn's
  [installation guide](https://github.com/synauson/jsyn/blob/v1.6.0/docs/install.md) has the steps.

## Run it

In a container:

```bash
cd java/jsyn-licensing
docker build -t jsyn-licensing-tour .
docker run --rm -e SYNAUSON_LICENSE_KEY -v jsyn-licensing-tour:/tour jsyn-licensing-tour
```

With [just](https://github.com/casey/just), `just tour-container` does the same.

On the host (`gradlew.bat` on Windows):

```bash
./gradlew run                              # every chapter
./gradlew run --args="online limits"       # some of them
```

The `online` chapter checks out the license file and downloads the models the later
chapters reuse, so run it once before you run other chapters on their own.

## What you should see

Each chapter prints a heading, then each step and what the runtime did. A `✓` line is a
claim the tour checked. With a Free key, the start of the `online` chapter looks like
this:

```text
━━━ 2 · Online ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
At startup the runtime reports to usage.synauson.com, which checks out a license file
from license.synauson.com for it: a file signed by Synauson that names how many AI
sessions the license may run at once. The runtime caches the file in the state directory
and renews it about once a day. Every 30 seconds it reports its session counts and gets
back its share of the license's pool, which every runtime on the license shares.
JSyn.capabilities() reports what the runtime knows about its license.

▸ Start with your key and an empty state directory
    license                    valid · license '…' (…), perpetual (every AI feature, Free plan, 2 concurrent AI sessions; file from server, works until …)
    license id                 …
    plan                       all
    file from                  server
    file expires               … (renewed about daily; without contact new calls stop sooner, below)
    license expires            never
    AI sessions                0 in use of 2, ok · counted on all instances of this license, no burst
    usage service              leased · a share of 2 session(s) · 1 instance(s), 0 session(s) in use
    new calls stop at          … without a report getting through
    …
  ✓ valid, from a file just checked out through the usage service, with every AI feature
    and AI sessions 0 in use of 2, ok
```

The tour ends with:

```text
✓ Tour complete. Every statement above was checked against a live runtime.
```

If a check fails, the tour prints `✗` with the chapter and the reason, and exits with a
non-zero status. The engine's own warnings and errors also appear in the output.

## Configuration

| Setting | Default | Effect |
|---|---|---|
| `SYNAUSON_LICENSE_KEY` | none | The license key every chapter uses |
| Chapters | all, in order | The program's arguments, as in `./gradlew run --args="online limits"` |
| Working directory | `build/licensing-tour/` on the host, `/tour` in the container | The tour's model store, state directories and license files. `./gradlew clean`, or `just clean`, deletes them. |

## Troubleshooting

| What you see | Cause and fix |
|---|---|
| `Set SYNAUSON_LICENSE_KEY to your Synauson license key` | The variable isn't set in this shell or wasn't passed to the container (`-e SYNAUSON_LICENSE_KEY`). |
| `PermissionDeniedException` in the `startup` or `air-gapped` chapter, followed by a `✓` line | Expected: those steps use a made-up key, or no license file, on purpose, and the `✓` line confirms the refusal. |
| The `online` chapter fails with `PermissionDeniedException` | Your key is unknown to the licensing server, suspended or banned, or this machine can't reach `usage.synauson.com` (the message says which). |
| `usage.synauson.com leased no share` | Reports aren't getting through: check HTTPS access to `usage.synauson.com`. |
| The `capabilities` chapter skips the second participant, or `limits` refuses earlier than the license's limit | Another runtime on the same key holds part of the pool. Run the tour where nothing else uses the key. |
| A chapter waits a long time for models | The first run downloads every model, about 1 GB. Later runs reuse the `jsyn-licensing-tour` volume or `build/licensing-tour/`. |
| `Unknown chapter '…'` | Use `startup`, `online`, `capabilities`, `air-gapped` or `limits`. |
| Host runs fail with `UnsatisfiedLinkError` or a GStreamer error | See jsyn's [troubleshooting guide](https://github.com/synauson/jsyn/blob/v1.6.0/docs/troubleshooting.md), or run in the container. |
