# Getting started with jsyn on Windows

This guide takes a Windows machine with nothing installed to all three quickstart programs
running. [README.md](README.md) is the short overview of what the programs show and how to use
jsyn in your own project; the [jsyn README](https://github.com/synauson/jsyn) covers the SDK.

The steps match what this repository's CI does on its Windows runner before running the
programs.

## Prerequisites

### 1. Java 11 or later

```powershell
java -version
```

If that prints version 11 or higher, go to step 2. Otherwise:

1. Download the Eclipse Temurin JDK 11 `.msi` for Windows x64:
   https://adoptium.net/temurin/releases/?os=windows&arch=x64&package=jdk&version=11
2. Run the installer with the default options.
3. Open a **new** PowerShell window and check `java -version` again.

### 2. GStreamer 1.26.7 (MSVC x86_64)

GStreamer does the media processing. You need the **MSVC** build (not MinGW), and only the
**runtime** package: jsyn ships its native library prebuilt, so the devel MSI is not needed.
Use 1.26: GStreamer 1.28 changed webrtcbin's pad API, which breaks WebRTC.

1. Download the runtime MSI (about 150 MB):
   https://gstreamer.freedesktop.org/data/pkg/windows/1.26.7/msvc/gstreamer-1.0-msvc-x86_64-1.26.7.msi
2. Run it, choose the **Complete** installation, and keep the default location, which puts the
   files under `C:\gstreamer\1.0\msvc_x86_64\`.

   To install it silently instead (as CI does), from an elevated PowerShell:

   ```powershell
   msiexec /i gstreamer-1.0-msvc-x86_64-1.26.7.msi /quiet /norestart ADDLOCAL=ALL INSTALLDIR=C:\gstreamer\
   ```

3. Set the GStreamer root and add its `bin` directory to `PATH`, machine-wide. Open PowerShell
   **as Administrator** and run:

   ```powershell
   [Environment]::SetEnvironmentVariable(
       "GSTREAMER_1_0_ROOT_MSVC_X86_64",
       "C:\gstreamer\1.0\msvc_x86_64",
       "Machine")
   $p = [Environment]::GetEnvironmentVariable("Path", "Machine")
   if (-not $p.Contains("C:\gstreamer\1.0\msvc_x86_64\bin")) {
       [Environment]::SetEnvironmentVariable(
           "Path", "$p;C:\gstreamer\1.0\msvc_x86_64\bin", "Machine")
   }
   ```

4. Close every PowerShell window (and your IDE) so new ones pick up the variables. Sign out
   and back in if they still don't. Then check the install from a new, non-elevated window:

   ```powershell
   gst-inspect-1.0 --version
   ```

   It should report `GStreamer 1.26.7`. If the command isn't found, check that
   `Test-Path C:\gstreamer\1.0\msvc_x86_64\bin\gst-inspect-1.0.exe` returns `True` and that
   the directory is on `PATH`.

5. Build the GStreamer plugin registry, once per Windows user account:

   ```powershell
   gst-inspect-1.0 coreelements
   ```

   The first GStreamer start for a user scans every installed plugin, which can take tens of
   seconds. Doing it now keeps that scan out of the first program you run.

### 3. Git

```powershell
git --version
```

If it isn't installed, get it from https://git-scm.com/download/win, install with the
default options, and reopen PowerShell.

### 4. A Synauson license key

jsyn does not run without a license key; free-tier keys work (see
[synauson.com](https://synauson.com)). Set it in the PowerShell window you'll run the
programs from:

```powershell
$env:SYNAUSON_LICENSE_KEY = "your-license-key"
```

At startup the runtime downloads the AI models your license includes into
`%LOCALAPPDATA%\synauson\models`. There is nothing to install by hand.

## Get the code

```powershell
cd C:\Users\YourName\Projects   # or wherever you keep code
git clone https://github.com/synauson/examples.git
cd examples\java\jsyn-windows-quickstart
```

`ls` should show `build.gradle.kts`, `settings.gradle.kts`, `gradlew`, `gradlew.bat`,
`gradle\`, `src\`, `README.md`, `GETTING_STARTED.md` and `.gitignore` (hidden unless you use
`ls -Force`).

## Build and run the first program

```powershell
.\gradlew.bat build
```

The first build downloads Gradle and the dependencies, then prints `BUILD SUCCESSFUL`.

```powershell
.\gradlew.bat run
```

This runs `FilePlaybackExample`, which plays a generated two-second tone through a
conference:

```
=== JSyn File Playback Example ===

Generated test WAV: C:\Users\...\test.wav
Initializing JSyn...
JSyn initialized successfully

Starting conference: example-conference
Conference started

Adding file participant:
  Participant ID: file-player
  File URI: file:///C:/Users/.../test.wav
  Loop playback: false

✓ Playback started
✓ End of stream (EOS) - playback complete

Playback completed successfully!
Terminating conference...
JSyn shutdown complete

=== Example completed successfully ===
```

If you see that, jsyn is working. Code: `src\main\java\com\example\jsyn\FilePlaybackExample.java`.

## The other two programs

### Native participant I/O

```powershell
.\gradlew.bat runNativeIOExample
```

`NativeParticipantIOExample` plays a file into the conference, routes it to a native
participant, writes PCM into the conference from Java with `write()`, and reads the mixed
audio back with `read()` into `output.wav`:

```
File participant                Native participant
(plays input.wav) ───────────►  (your Java code)
                                  ├─ write(): send audio into the conference
                                  └─ read(): receive the mixed audio
```

It ends with a summary like:

```
=== I/O Summary ===
Ingress bytes written: 76800
Egress bytes read: 76800

✓ Output written to: ...\output.wav
  Size: 76800 bytes
  Duration: 2.4 seconds (approx)
  RMS energy: 19528.06

✓ Validation passed - audio contains signal
```

### Voice activity detection

This one needs a license that includes `FEATURE_VAD` and a WAV recording of speech (16 kHz,
mono, 16-bit PCM). Silero VAD is trained on speech and won't trigger on a synthetic tone.

```powershell
.\gradlew.bat runVadExample --args="C:\path\to\speech.wav"
```

`VadDetectionExample` waits for the runtime to download the Silero VAD model, streams the
recording into a native participant with VAD in 20 ms frames, then 1.5 s of silence, and
waits for the speech start and end events:

```
=== JSyn VAD Detection Example ===

Loaded C:\path\to\speech.wav (3.2 s of audio)

Initializing JSyn...
JSyn initialized: <your license>

Waiting for the Silero VAD model...
Model ready

Conference started: vad-example-conference

Creating native participant with VAD (PCM 16 kHz mono)

Streaming the recording, then 1.5 s of silence...
✓ VAD: speech START (#1)
✓ VAD: speech END (2848 ms)

=== VAD Detection Summary ===
Speech START events: 1
✓ VAD detected the speech
JSyn shutdown complete

=== Example completed successfully ===
```

## Troubleshooting

### `Could not resolve com.synauson:jsyn`

Gradle can't download jsyn. Check that `build.gradle.kts` lists
`https://maven.synauson.com/releases` under `repositories` (no credentials are needed), that
the machine can reach it (`curl https://maven.synauson.com/releases/com/synauson/jsyn/maven-metadata.xml`),
then retry with `.\gradlew.bat build --refresh-dependencies`.

### `UnsatisfiedLinkError: missing native: com/synauson/jsyn/natives/windows-x86_64/… — add jsyn-natives-<platform> to your classpath`

`jsyn-natives-windows` isn't on the runtime classpath. Add it as a `runtimeOnly` dependency at
the same version as `jsyn`.

### `UnsatisfiedLinkError: …\synauson_jni.dll: Can't find dependent libraries`

Windows can't find the GStreamer DLLs. Check that `C:\gstreamer\1.0\msvc_x86_64\bin` is on
`PATH` in the window you run from (`$env:PATH -split ';' | Select-String gstreamer`), and
restart the terminal or IDE after changing it.

### `GStreamer sanity check failed: …`

GStreamer loaded but its plugins are missing or incomplete. Reinstall the runtime MSI with the
**Complete** installation, check `GSTREAMER_1_0_ROOT_MSVC_X86_64` (step 2.3), and run
`gst-inspect-1.0 coreelements`.

### `new JSyn(...)` fails with a license error

`SYNAUSON_LICENSE_KEY` is missing or malformed (`InvalidArgumentException`), or the
licensing server refused the key (`PermissionDeniedException`). Set a valid key in the same
window you run from.

### `FailedPreconditionException` naming a model

The model hasn't finished downloading. Wait for it with `jsyn.capabilities()`, as
`VadDetectionExample` does. If it never becomes ready, check that the machine can reach
`dl.synauson.com`.

### `PermissionDeniedException` when adding a participant with VAD

Your license doesn't include that capability (for example `FEATURE_VAD`).

### The first run takes tens of seconds before anything happens

GStreamer is building its plugin registry. Run `gst-inspect-1.0 coreelements` once (step 2.5).

### `java.lang.OutOfMemoryError`

The programs run in their own JVM, not Gradle's, so `org.gradle.jvmargs` doesn't affect them.
To give them more heap, add this to `build.gradle.kts`:

```kotlin
tasks.withType<JavaExec>().configureEach { maxHeapSize = "2g" }
```

## Next steps

- Read the three programs in `src\main\java\com\example\jsyn\`; each is a single file.
- To add jsyn to your own project, see [README.md](README.md#use-jsyn-in-your-own-project).
- For the API, concepts and the jsyn tests that show each feature, see the
  [jsyn README](https://github.com/synauson/jsyn).
