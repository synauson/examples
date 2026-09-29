# jsyn Windows quickstart

A minimal, standalone Gradle project that uses [jsyn](https://github.com/synauson/jsyn) on
Windows, pulling the published artifacts from the public Synauson Maven repository. It is what
a Windows developer needs to get jsyn running in their own application, and nothing more. CI
builds it and runs all three programs on Windows on every push.

New to Windows development or setting up a fresh machine? Follow
[GETTING_STARTED.md](GETTING_STARTED.md), which goes from nothing installed to all three
programs running, with the expected output and troubleshooting. For the jsyn API itself, see
the [jsyn README](https://github.com/synauson/jsyn).

## What's in it

| Gradle task | Program | Shows |
|---|---|---|
| `run` | `FilePlaybackExample` | Starting jsyn and a conference, playing a generated WAV file through a file participant, waiting for its end-of-stream event, shutting down |
| `runNativeIOExample` | `NativeParticipantIOExample` | A native participant: writing PCM into the conference from Java and reading the mixed audio back, with explicit routing |
| `runVadExample` | `VadDetectionExample` | Waiting for the licensed VAD model, streaming a speech recording into a native participant with VAD, and receiving speech start and end events |

```
jsyn-windows-quickstart/
├── build.gradle.kts                  # dependencies and the three run tasks
├── settings.gradle.kts
├── gradlew, gradlew.bat, gradle/     # Gradle wrapper
├── src/main/java/com/example/jsyn/
│   ├── FilePlaybackExample.java
│   ├── NativeParticipantIOExample.java
│   └── VadDetectionExample.java
├── GETTING_STARTED.md                # step-by-step Windows setup
├── README.md                         # this file
└── .gitignore
```

## Requirements

- Windows x64 and Java 11 or later.
- GStreamer 1.26.7 MSVC x86_64, the **runtime** MSI only (jsyn compiles nothing, so the devel
  MSI isn't needed), with its `bin` directory on `PATH`. See
  [GETTING_STARTED.md](GETTING_STARTED.md#2-gstreamer-1267-msvc-x86_64) for the install,
  environment variables and the one-time plugin registry build.
- A Synauson license key in `SYNAUSON_LICENSE_KEY` (free-tier keys work). At startup the
  runtime downloads the AI models your license includes into `%LOCALAPPDATA%\synauson\models`.
- For `runVadExample`: a license that includes `FEATURE_VAD`, and a WAV recording of speech
  (16 kHz, mono, 16-bit PCM). Silero VAD won't trigger on a synthetic tone.

## Run it

```powershell
git clone https://github.com/synauson/examples.git
cd examples\java\jsyn-windows-quickstart
$env:SYNAUSON_LICENSE_KEY = "your-license-key"

.\gradlew.bat run
.\gradlew.bat runNativeIOExample
.\gradlew.bat runVadExample --args="C:\path\to\speech.wav"
```

Each program prints `=== Example completed successfully ===` at the end.
[GETTING_STARTED.md](GETTING_STARTED.md#troubleshooting) lists the common errors and their
fixes.

## Use jsyn in your own project

Add the repository and the two dependencies to `build.gradle.kts`, as this project does:

```kotlin
repositories {
    mavenCentral()
    maven {
        name = "Synauson"
        url = uri("https://maven.synauson.com/releases") // public, no credentials
    }
}

dependencies {
    implementation("com.synauson:jsyn:1.5.0")
    // synauson_jni.dll and onnxruntime.dll (ONNX Runtime is bundled; nothing to install)
    runtimeOnly("com.synauson:jsyn-natives-windows:1.5.1")
}
```

Use the newest `jsyn-natives-windows` that jsyn is tested with; an engine-only fix ships as a
natives release without a new jsyn. Then start from whichever of the
three programs is closest to what you need.

## License

Apache 2.0, like the rest of this repository. See [LICENSE](../../LICENSE).
