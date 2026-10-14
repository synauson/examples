# jsyn Windows quickstart

A minimal Gradle project that uses [jsyn](https://github.com/synauson/jsyn) on Windows,
with jsyn and its natives from the public Synauson Maven repository. It holds three
small programs, each a single file you can read in a few minutes and copy into your own
application.

New to Windows development, or setting up a fresh machine?
[GETTING_STARTED.md](GETTING_STARTED.md) takes you from nothing installed to all three
programs running.

## What it shows

| Gradle task | Program | What it shows |
|---|---|---|
| `run` | [`FilePlaybackExample`](src/main/java/com/example/jsyn/FilePlaybackExample.java) | Starting jsyn and a conference, playing a generated WAV file through a file participant, waiting for its end-of-stream event, and shutting down |
| `runNativeIOExample` | [`NativeParticipantIOExample`](src/main/java/com/example/jsyn/NativeParticipantIOExample.java) | A native participant: routing a file to it, writing audio into the conference from Java and reading the mixed audio back into a WAV file |
| `runVadExample` | [`VadDetectionExample`](src/main/java/com/example/jsyn/VadDetectionExample.java) | Waiting for the voice activity model to download, streaming a speech recording into a native participant with VAD, and receiving speech start and end events |

## What you need

- Windows 10 or 11, x64.
- A JDK 11. The build compiles and runs the programs with Java 11 through a Gradle
  toolchain, so a JDK 11 must be installed even if you also have a newer one; Gradle
  finds it. Gradle itself runs on the JDK in `JAVA_HOME`, else the `java` on `Path`,
  which must be 11 to 23 for this project's Gradle version.
- The Microsoft Visual C++ Redistributable, x64.
- GStreamer 1.26.7, the MSVC x86_64 runtime installer with the Complete feature set,
  with its `bin` folder on `Path`.
- A Synauson license key in `SYNAUSON_LICENSE_KEY`. Any key runs all three programs.
- HTTPS access to `usage.synauson.com`, `license.synauson.com`, `status.synauson.com`
  and Cloudflare R2 (`*.r2.cloudflarestorage.com`), where the models download from, and
  a synchronized clock (NTP). jsyn's
  [network and firewall](https://github.com/synauson/jsyn/blob/v1.7.0/docs/install.md#network-and-firewall) table says what each is for.
- For `runVadExample`, a WAV recording of speech: 16 kHz, mono, 16-bit PCM. VAD is
  trained on speech and doesn't react to a test tone.

[GETTING_STARTED.md](GETTING_STARTED.md) has the steps for each of these.

## Run it

From PowerShell:

```powershell
git clone https://github.com/synauson/examples.git
cd examples\java\jsyn-windows-quickstart
$env:SYNAUSON_LICENSE_KEY = "<your license key>"

.\gradlew.bat run
.\gradlew.bat runNativeIOExample
.\gradlew.bat runVadExample --args="C:\path\to\speech.wav"
```

To turn another recording into the format `runVadExample` reads, use
`ffmpeg -i in.m4a -ac 1 -ar 16000 -sample_fmt s16 speech.wav`.

## What you should see

Each program prints what it does, and prints this line when it succeeds:

```text
=== Example completed successfully ===
```

[GETTING_STARTED.md](GETTING_STARTED.md#build-and-run-the-first-program) shows each
program's full output. The engine also prints warnings to the console, for example that
a model isn't installed yet: on the first run it downloads the models, in the
background, into `%LOCALAPPDATA%\synauson\models`.

## Use jsyn in your own project

Add the repository and the two dependencies to your `build.gradle.kts`, as this project
does:

```kotlin
repositories {
    mavenCentral()
    maven {
        name = "Synauson"
        url = uri("https://maven.synauson.com/releases") // public, no credentials
    }
}

dependencies {
    implementation("com.synauson:jsyn:1.7.0")
    // the engine and ONNX Runtime; GStreamer and the Visual C++ runtime are installed separately
    runtimeOnly("com.synauson:jsyn-natives-windows:1.8.0")
}
```

jsyn and its natives have separate versions, and an engine fix can ship as a natives
release without a new jsyn. Use the natives version that jsyn's release notes name, or a
newer one. Then start from whichever program is closest to what you need.

## Configuration

The programs take no settings beyond `SYNAUSON_LICENSE_KEY` and the WAV path for
`runVadExample`. They keep jsyn's defaults, among them the model store
`%LOCALAPPDATA%\synauson\models` and the license cache in
`%LOCALAPPDATA%\synauson\state`. To change those, or anything else, see jsyn's
[configuration guide](https://github.com/synauson/jsyn/blob/v1.7.0/docs/configuration.md).

## Troubleshooting

[GETTING_STARTED.md](GETTING_STARTED.md#troubleshooting) lists the common errors and
their fixes, from Gradle not finding jsyn to Windows not finding GStreamer.
