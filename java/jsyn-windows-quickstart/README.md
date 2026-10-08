# JSyn Windows Quickstart

Reference implementation showing how to use JSyn (Synauson Java client) on Windows by pulling published artifacts from the Synauson Maven repository.

**This is a completely standalone project** - it does not depend on the Synauson source code. It demonstrates exactly what a third-party Windows developer needs to do to integrate JSyn into their application.

## Prerequisites

### 1. Java 11 or later

```powershell
java -version
```

### 2. GStreamer 1.26.7 (MSVC x86_64)

Download and install both runtime and development packages:

- **Runtime**: `gstreamer-1.0-msvc-x86_64-1.26.7.msi`
- **Development**: `gstreamer-1.0-devel-msvc-x86_64-1.26.7.msi`

Download from: https://gstreamer.freedesktop.org/data/pkg/windows/1.26.7/msvc/

**Important**: Install to the default location (`C:\gstreamer\1.0\msvc_x86_64\`).

After installation, add to your PATH:

```powershell
$env:PATH += ";C:\gstreamer\1.0\msvc_x86_64\bin"
```

Verify installation:

```powershell
gst-inspect-1.0 --version
```

### 3. A Synauson license key

jsyn runs under a license key (free-tier keys work). Set it in the environment before
running the examples:

```powershell
$env:SYNAUSON_LICENSE_KEY = "your-license-key"
```

At startup the runtime downloads the AI models your license includes (for example sentito
VAD) into `%LOCALAPPDATA%\synauson\models`; nothing to install by hand. The VAD example also
needs a WAV recording of speech (16 kHz, mono, 16-bit PCM), because sentito-1 is trained on
speech and won't trigger on a synthetic tone.

## Project Structure

```
jsyn-windows-quickstart/
├── build.gradle.kts                    # Gradle build configuration
├── src/main/java/com/example/jsyn/
│   ├── FilePlaybackExample.java        # Simple file playback demo
│   └── VadDetectionExample.java        # VAD detection demo
└── README.md                            # This file
```

## Dependencies

This project pulls JSyn from the Synauson Maven repository:

```kotlin
// Pure Java API
implementation("com.synauson:jsyn:1.5.0")

// Windows native libraries (synauson_jni.dll + onnxruntime.dll)
runtimeOnly("com.synauson:jsyn-natives-windows:1.5.0")
```

The artifacts are published to:
- **Repository**: https://maven.synauson.com/releases
- **Group**: `com.synauson`
- **Artifacts**: `jsyn`, `jsyn-natives-windows`

The repository is public; no credentials are needed.

## Running the Examples

### 1. File Playback Example

Plays a generated 2-second sine wave and demonstrates basic JSyn usage:

```powershell
./gradlew.bat run
```

Expected output:

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

### 2. VAD Detection Example

Streams a speech recording into a native participant with VAD enabled and waits for VAD to
report the speech starting and ending. Pass a WAV file of speech (16 kHz, mono, 16-bit PCM):

```powershell
./gradlew.bat runVadExample --args="C:\path\to\speech.wav"
```

It waits for the runtime to download the sentito-1 model first, and needs a license that
includes `FEATURE_VAD`. Expected output:

```
=== JSyn VAD Detection Example ===

Loaded C:\path\to\speech.wav (3.2 s of audio)

Initializing JSyn...
JSyn initialized: <your license>

Waiting for the sentito-1 model...
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

### 3. NativeParticipant Bidirectional I/O Example

Demonstrates the JNI layer's bidirectional audio pathway - writing audio bytes to the conference (ingress) and reading mixed audio back (egress):

```powershell
./gradlew.bat runNativeIOExample
```

**Architecture:**
```
File Participant → Native Participant (Java read/write)
(plays test.wav)   ├─ Ingress: write() sends PCM to conference
                   └─ Egress: read() receives mixed audio from conference
```

Expected output:

```
=== JSyn NativeParticipant I/O Example ===

Test directory: C:\Users\...\jsyn-native-io-...
Input WAV: ...\input.wav
Output WAV: ...\output.wav

Initializing JSyn...
JSyn initialized

Conference started: native-io-conference

Adding file participant:
  ID: file-source
  File: input.wav

Adding native participant:
  ID: native-receiver
  Format: PCM 16kHz mono S16LE

✓ File playback started
Routing:
  file-source → native-receiver
  native-receiver → native-receiver (loopback)

Writing synthetic ingress audio...
Reading egress audio...
✓ File playback complete (EOS)

=== I/O Summary ===
Ingress bytes written: 76800
Egress bytes read: 76800

✓ Output written to: ...\output.wav
  Size: 76800 bytes
  Duration: 2.4 seconds (approx)
  RMS energy: 19528.06

✓ Validation passed - audio contains signal

Terminating conference...
JSyn shutdown complete

=== Example completed successfully ===
Output file: ...\output.wav
```

**Use case:** Custom audio processing - your Java application can:
- Send programmatically generated audio into the conference via `write()`
- Receive the mixed conference audio via `read()` for recording, streaming, or analysis
- Process audio in real-time before/after conference mixing

## Integration into Your Project

To use JSyn in your own Windows Java project:

### 1. Add the Synauson Maven repository to `build.gradle.kts`:

```kotlin
repositories {
    mavenCentral()
    maven {
        name = "Synauson"
        url = uri("https://maven.synauson.com/releases")
    }
}
```

### 2. Add JSyn dependencies:

```kotlin
dependencies {
    implementation("com.synauson:jsyn:1.5.0")
    runtimeOnly("com.synauson:jsyn-natives-windows:1.5.0")
}
```

### 3. Initialize JSyn in your code:

```java
import com.synauson.jsyn.JSyn;
import com.synauson.jsyn.JSynConfig;

JSynConfig config = JSynConfig.builder()
    .licenseKey(System.getenv("SYNAUSON_LICENSE_KEY"))  // or leave unset to read it from the environment
    .rtpPortMin(40000)
    .rtpPortMax(40199)
    .build();

try (JSyn jsyn = new JSyn(config)) {
    // Use JSyn...
}
```

## Troubleshooting

### `UnsatisfiedLinkError: no synauson_jni in java.library.path`

- Verify `jsyn-natives-windows` is in your runtime classpath
- Check that GStreamer bin directory is in PATH

### `new JSyn(...)` fails with a license error

- Set `SYNAUSON_LICENSE_KEY`; the runtime won't start without a key, or with one the
  licensing server refuses.

### `FailedPreconditionException` naming a model, or `PermissionDeniedException`

- `FailedPreconditionException`: the model hasn't finished downloading yet. Wait for it with
  `jsyn.capabilities()` as `VadDetectionExample` does.
- `PermissionDeniedException`: your license doesn't include that capability (for example
  `FEATURE_VAD`).

### `gst_init failed`

- Verify GStreamer is installed correctly
- Check that `C:\gstreamer\1.0\msvc_x86_64\bin` is in PATH
- Try running `gst-inspect-1.0 --version` to verify installation

## API Reference

See the JSyn JavaDoc for complete API documentation:

- `JSyn` - Main runtime entry point
- `Conference` - Represents a multi-participant audio conference
- `FileParticipantSpec` - Configuration for file playback participants
- `NativeParticipantSpec` - Configuration for programmatic audio participants
- `VadConfig`, `TurnDetectionConfig` - Detector configurations

## Version Information

- **JSyn version**: `1.5.0` (with `jsyn-natives-windows` `1.5.0`)
- **GStreamer**: 1.26.7 (MSVC x86_64)
- **ONNX Runtime**: 1.24.4 (embedded in jsyn-natives-windows)
- **Java**: 11 or later

## License

This example code is provided as-is for demonstration purposes.
