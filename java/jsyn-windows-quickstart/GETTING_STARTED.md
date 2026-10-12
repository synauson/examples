# Getting started with jsyn on Windows

This guide takes a Windows machine with nothing installed to all three quickstart
programs running. [README.md](README.md) is the short version: what the programs show
and how to add jsyn to your own project. The [jsyn README](https://github.com/synauson/jsyn)
covers the SDK itself.

- [1. Install a JDK 11](#1-install-a-jdk-11)
- [2. Install the Visual C++ runtime](#2-install-the-visual-c-runtime)
- [3. Install GStreamer](#3-install-gstreamer)
- [4. Install Git](#4-install-git)
- [5. Set your license key](#5-set-your-license-key)
- [Get the code](#get-the-code)
- [Build and run the first program](#build-and-run-the-first-program)
- [Native participant I/O](#native-participant-io)
- [Voice activity detection](#voice-activity-detection)
- [Troubleshooting](#troubleshooting)
- [Next steps](#next-steps)

Commands that change the machine run in a PowerShell window opened as Administrator
(right-click PowerShell, **Run as administrator**). The rest run in an ordinary window.

## 1. Install a JDK 11

The project's Gradle build compiles and runs the programs with Java 11, so it needs a
JDK 11 installed, even if you already have a newer JDK. Gradle itself runs on the JDK
in `JAVA_HOME`, else the `java` on `Path`, and this project's Gradle version needs
that one to be 11 to 23.

1. Download the Eclipse Temurin JDK 11 `.msi` for Windows x64 from
   [adoptium.net](https://adoptium.net/temurin/releases/?os=windows&arch=x64&package=jdk&version=11).
2. Run the installer with its default options.
3. Open a new PowerShell window and check:

   ```powershell
   java -version
   ```

   If another JDK was installed first, this may print that one instead. Up to 23 that is
   fine: Gradle finds the JDK 11 on its own. If it prints 24 or later, point Gradle at
   the JDK 11 in each window you build from:

   ```powershell
   $env:JAVA_HOME = (Resolve-Path 'C:\Program Files\Eclipse Adoptium\jdk-11*').Path
   ```

## 2. Install the Visual C++ runtime

The engine's DLLs need the Microsoft Visual C++ runtime, which a clean Windows install
lacks. In an Administrator PowerShell:

```powershell
Start-BitsTransfer -Source 'https://aka.ms/vc14/vc_redist.x64.exe' -Destination "$env:TEMP\vc_redist.x64.exe"
Start-Process "$env:TEMP\vc_redist.x64.exe" -Wait -ArgumentList '/install', '/quiet', '/norestart'
Test-Path "$env:WINDIR\System32\VCRUNTIME140_1.dll"   # True
```

## 3. Install GStreamer

GStreamer does the media processing. You need the MSVC build (not MinGW) of version
1.26.7, and only its runtime installer: jsyn ships its own native library prebuilt, so
the devel installer isn't needed. GStreamer 1.28 is not supported, because it breaks
WebRTC.

1. Download and install the runtime MSI (about 90 MB), for all users, with the
   **Complete** feature set, into `C:\gstreamer`. In an Administrator PowerShell:

   ```powershell
   $gst = '1.26.7'
   Start-BitsTransfer -Source "https://gstreamer.freedesktop.org/data/pkg/windows/$gst/msvc/gstreamer-1.0-msvc-x86_64-$gst.msi" -Destination "$env:TEMP\gst-runtime.msi"
   Start-Process msiexec.exe -Wait -ArgumentList '/i', "$env:TEMP\gst-runtime.msi", '/quiet', '/norestart', 'ADDLOCAL=ALL', 'INSTALLDIR=C:\gstreamer\'
   Test-Path 'C:\gstreamer\1.0\msvc_x86_64\bin\gstreamer-1.0-0.dll'   # True
   ```

   To use the installer's window instead, open
   [the MSI](https://gstreamer.freedesktop.org/data/pkg/windows/1.26.7/msvc/gstreamer-1.0-msvc-x86_64-1.26.7.msi),
   choose **Complete**, and install to `C:\gstreamer\`.

2. Set the GStreamer root and add its `bin` folder to the machine `Path`, in the same
   Administrator PowerShell:

   ```powershell
   [Environment]::SetEnvironmentVariable('GSTREAMER_1_0_ROOT_MSVC_X86_64', 'C:\gstreamer\1.0\msvc_x86_64\', 'Machine')
   $p = [Environment]::GetEnvironmentVariable('Path', 'Machine')
   if ($p -notlike '*C:\gstreamer\1.0\msvc_x86_64\bin*') {
       [Environment]::SetEnvironmentVariable('Path', "$p;C:\gstreamer\1.0\msvc_x86_64\bin", 'Machine')
   }
   ```

3. Close every PowerShell window and your IDE, so new ones pick up the change. Sign out
   and back in if they still don't. Then, from a new ordinary window:

   ```powershell
   gst-inspect-1.0 --version
   ```

   It should report `GStreamer 1.26.7`. If the command isn't found, check that
   `Test-Path C:\gstreamer\1.0\msvc_x86_64\bin\gst-inspect-1.0.exe` returns `True` and
   that the folder is on `Path`.

4. Build GStreamer's plugin registry, once for each Windows account that runs the
   programs:

   ```powershell
   gst-inspect-1.0 coreelements
   ```

   GStreamer's first start for an account scans every installed plugin, which can take
   tens of seconds. Doing it now keeps that wait out of the first program you run.

jsyn's [installation guide](https://github.com/synauson/jsyn/blob/v1.6.0/docs/install.md#windows)
has the same steps with more detail, a check for every element jsyn needs, and the
firewall rules a server needs.

## 4. Install Git

```powershell
git --version
```

If Git isn't installed, get it from [git-scm.com](https://git-scm.com/download/win),
install it with the default options, and open a new PowerShell window.

## 5. Set your license key

jsyn doesn't run without a license key from Synauson. Set it in the PowerShell window
you'll run the programs from:

```powershell
$env:SYNAUSON_LICENSE_KEY = "<your license key>"
```

On their first start the programs download the models into
`%LOCALAPPDATA%\synauson\models`. You install nothing by hand, but the machine must
reach the hosts the [README](README.md#what-you-need) lists.

## Get the code

```powershell
cd $HOME
git clone https://github.com/synauson/examples.git
cd examples\java\jsyn-windows-quickstart
```

## Build and run the first program

```powershell
.\gradlew.bat build
```

The first build downloads Gradle and the dependencies, then prints `BUILD SUCCESSFUL`.

```powershell
.\gradlew.bat run
```

This runs `FilePlaybackExample`, which plays a generated two-second tone through a
conference. Between its own lines the engine prints warnings, for example that the
models aren't installed yet while they download; this program doesn't need them. Its
own output is:

```text
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

If you see that, jsyn works on this machine. The code is in
`src\main\java\com\example\jsyn\FilePlaybackExample.java`.

## Native participant I/O

```powershell
.\gradlew.bat runNativeIOExample
```

`NativeParticipantIOExample` plays a file into the conference and routes it to a native
participant. It writes audio into the conference from Java with `write()`, and reads the
mixed audio back with `read()` into `output.wav`:

```text
File participant                 Native participant
(plays input.wav) ------------>  (your Java code)
                                   write(): send audio into the conference
                                   read():  receive the mixed audio
```

It ends with a summary like this one (the byte counts and the RMS vary a little from
run to run):

```text
=== I/O Summary ===
Ingress bytes written: 76800
Egress bytes read: 78720

✓ Output written to: C:\Users\...\output.wav
  Size: 78720 bytes
  Duration: 2.46 seconds (approx)
  RMS energy: 19419.01

✓ Validation passed - audio contains signal
```

## Voice activity detection

This program needs a WAV recording of speech: 16 kHz, mono, 16-bit PCM. To convert another recording, use
`ffmpeg -i in.m4a -ac 1 -ar 16000 -sample_fmt s16 speech.wav`.

```powershell
.\gradlew.bat runVadExample --args="C:\path\to\speech.wav"
```

`VadDetectionExample` waits for the runtime to download the VAD model, streams the
recording into a native participant with VAD in 20 ms frames, then 1.5 s of silence,
and waits for the speech start and end events. On a five-second recording:

```text
=== JSyn VAD Detection Example ===

Loaded C:\path\to\speech.wav (5.0 s of audio)

Initializing JSyn...
JSyn initialized: license '…' (…)

Waiting for the VAD model...
Model ready

Conference started: vad-example-conference

Creating native participant with VAD (PCM 16 kHz mono)

Streaming the recording, then 1.5 s of silence...
✓ VAD: speech START (#1)
✓ VAD: speech END (… ms)

=== VAD Detection Summary ===
Speech START events: 1
✓ VAD detected the speech
JSyn shutdown complete

=== Example completed successfully ===
```

## Troubleshooting

### `Could not resolve com.synauson:jsyn`

Gradle can't download jsyn. Check that `build.gradle.kts` lists
`https://maven.synauson.com/releases` under `repositories` (it needs no credentials),
that the machine can reach it:

```powershell
curl.exe -sI https://maven.synauson.com/releases/com/synauson/jsyn/maven-metadata.xml
```

and then retry with `.\gradlew.bat build --refresh-dependencies`.

### `No matching toolchains found for requested specification: {languageVersion=11, ...}`

Gradle found no JDK 11. Install one ([step 1](#1-install-a-jdk-11)) and open a new
PowerShell window.

### `UnsatisfiedLinkError: missing native: com/synauson/jsyn/natives/windows-x86_64/… — add jsyn-natives-<platform> to your classpath`

`jsyn-natives-windows` isn't on the runtime classpath. Add it as a `runtimeOnly`
dependency ([README.md](README.md#use-jsyn-in-your-own-project)).

### `UnsatisfiedLinkError: …onnxruntime.dll: Can't find dependent libraries`

The Visual C++ runtime is missing. Install it ([step 2](#2-install-the-visual-c-runtime)).

### `UnsatisfiedLinkError: …\synauson_jni.dll: Can't find dependent libraries`

Windows can't find the GStreamer DLLs, or the Visual C++ runtime. Check that
`C:\gstreamer\1.0\msvc_x86_64\bin` is on `Path` in the window you run from
(`$env:Path -split ';' | Select-String gstreamer`), and restart the terminal or IDE
after changing it.

### `GStreamer sanity check failed: …`

GStreamer loaded, but plugins are missing. Reinstall the runtime MSI with the
**Complete** feature set, check `GSTREAMER_1_0_ROOT_MSVC_X86_64`
([step 3.2](#3-install-gstreamer)), and run `gst-inspect-1.0 coreelements`.

### `new JSyn(...)` fails with a license error

`SYNAUSON_LICENSE_KEY` is missing or malformed (`InvalidArgumentException`), or there is
no license (`PermissionDeniedException`): the licensing server refused the key, or the
machine can't reach `usage.synauson.com` and has no license file from an earlier start.
Set a valid key in the same window you run from, and check HTTPS access to
`usage.synauson.com` and `license.synauson.com`.

### `FailedPreconditionException` naming a model

The model hasn't finished downloading. Wait for it with `jsyn.capabilities()`, as
`VadDetectionExample` does. If it never becomes ready, check that the machine can reach
Cloudflare R2 (`*.r2.cloudflarestorage.com`), where the models download from.

### `IllegalStateException: The license can't use sentito-1 now`

`runVadExample` stops with this while the license starts nothing new: the licensing
server refused it, or no usage report has reached `usage.synauson.com` for 7 days.
`capabilities().license.state` says which (`rejected` or `disconnected`).

### The first run waits tens of seconds before anything happens

GStreamer is building its plugin registry. Run `gst-inspect-1.0 coreelements` once
([step 3.4](#3-install-gstreamer)).

### `✓` prints as `Γ£ô`

PowerShell reads the program's UTF-8 output in another encoding; the output is otherwise
correct. Run this in the window first:

```powershell
[Console]::OutputEncoding = [Text.Encoding]::UTF8
```

### `java.lang.OutOfMemoryError`

The programs run in their own JVM, not Gradle's, so `org.gradle.jvmargs` doesn't affect
them. To give them more heap, add this to `build.gradle.kts`:

```kotlin
tasks.withType<JavaExec>().configureEach { maxHeapSize = "2g" }
```

## Next steps

- Read the three programs in `src\main\java\com\example\jsyn\`; each is a single file.
- To add jsyn to your own project, see [README.md](README.md#use-jsyn-in-your-own-project).
- For the concepts and a guide to each feature, see the
  [jsyn README](https://github.com/synauson/jsyn).
