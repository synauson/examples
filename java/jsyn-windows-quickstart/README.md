# JSyn Windows Quickstart

Reference implementation showing how to use JSyn (Synauson Java client) on Windows by pulling published artifacts from Nexus.

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

### 3. ONNX Models (for VAD example only)

The VAD (Voice Activity Detection) example requires ONNX Runtime models. If you don't have them:

- **File playback example**: Works without models (uses stub directory)
- **VAD example**: Requires `silero_vad.onnx` in `../../models/` directory

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

This project pulls JSyn from Nexus:

```kotlin
// Pure Java API
implementation("com.synauson:jsyn:main-65a2237-202605252000-SNAPSHOT")

// Windows native libraries (synauson_jni.dll + onnxruntime.dll)
runtimeOnly("com.synauson:jsyn-natives-windows:main-65a2237-202605252000-SNAPSHOT")
```

The artifacts are published to:
- **Repository**: https://nexus.benashby.com/repository/maven-public
- **Group**: `com.synauson`
- **Artifacts**: `jsyn`, `jsyn-natives-windows`

### Repository Authentication

The Nexus repository requires authentication. Credentials are configured in `gradle.properties`:

```properties
nexusUser=github-ci
nexusPassword=<password>
```

For CI/CD environments, set environment variables instead:

```bash
export NEXUS_USER=github-ci
export NEXUS_PASSWORD=<password>
```

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

Demonstrates voice activity detection by writing synthetic audio with speech/silence patterns:

```powershell
./gradlew.bat runVadExample
```

**Note**: Requires ONNX models at `../../models/silero_vad.onnx`.

Expected output:

```
=== JSyn VAD Detection Example ===

Initializing JSyn with VAD models...
JSyn initialized

Conference started: vad-example-conference

Creating native participant with VAD:
  Participant ID: native-vad-test
  Sample rate: 16 kHz
  Channels: mono
  VAD threshold: 0.5

Native participant created

Generating and writing synthetic audio:
  - 1 second of speech (440 Hz sine wave)
  - 1 second of silence
  - 1 second of speech (880 Hz sine wave)
  - 1 second of silence

Wrote speech segment 1 (440 Hz, 1s)
Wrote silence segment 1 (1s)
Wrote speech segment 2 (880 Hz, 1s)
Wrote silence segment 2 (1s)

Waiting for VAD events...
✓ VAD: Speech START (event #1)
✓ VAD: Speech END (duration: 1024 ms, event #1)
✓ VAD: Speech START (event #2)
✓ VAD: Speech END (duration: 1024 ms, event #2)

=== VAD Detection Summary ===
Speech START events: 2
Speech END events: 2

✓ VAD working correctly - detected multiple speech segments

Terminating conference...
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

### 1. Add Nexus repository to `build.gradle.kts`:

```kotlin
repositories {
    mavenCentral()
    maven {
        name = "BenashbyNexus"
        url = uri("https://nexus.benashby.com/repository/maven-public")
    }
}
```

### 2. Add JSyn dependencies:

```kotlin
dependencies {
    implementation("com.synauson:jsyn:main-65a2237-202605252000-SNAPSHOT")
    runtimeOnly("com.synauson:jsyn-natives-windows:main-65a2237-202605252000-SNAPSHOT")
}
```

### 3. Initialize JSyn in your code:

```java
import com.synauson.jsyn.JSyn;
import com.synauson.jsyn.JSynConfig;

JSynConfig config = JSynConfig.builder()
    .modelsDir("C:/path/to/models")  // Required, can be empty for file-only
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

### `Failed to load ONNX model`

- Verify `models` directory contains `silero_vad.onnx`
- For file-only examples, models directory can be empty

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
- `VadConfig`, `SmartTurnConfig` - Detector configurations

## Version Information

- **JSyn version**: `main-65a2237-202605252000-SNAPSHOT`
- **GStreamer**: 1.26.7 (MSVC x86_64)
- **ONNX Runtime**: 1.24.4 (embedded in jsyn-natives-windows)
- **Java**: 11 or later

## License

This example code is provided as-is for demonstration purposes.
