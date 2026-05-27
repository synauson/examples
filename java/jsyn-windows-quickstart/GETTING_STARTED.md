# Getting Started with JSyn on Windows

This guide walks you through everything you need to set up and run JSyn (Synauson Java Client) on Windows. Follow these steps exactly and you'll have working audio processing in about 15 minutes.

## What is JSyn?

JSyn is a Java library that lets you:
- Create multi-party audio conferences
- Play audio files into conferences
- Record conference audio
- Detect voice activity (when someone is speaking)
- Send and receive raw audio bytes for custom processing
- All powered by GStreamer + ONNX machine learning under the hood

## Prerequisites

You need three things installed on your Windows machine:

### 1. Java Development Kit (JDK) 11 or later

**Check if you have it:**
```powershell
java -version
```

If you see `java version "11"` or higher, you're good. Skip to step 2.

**If you need to install Java:**
1. Download **Eclipse Temurin JDK 11** (LTS version): https://adoptium.net/temurin/releases/?os=windows&arch=x64&package=jdk&version=11
2. Download the `.msi` installer for Windows x64
3. Run the installer - use all default options
4. Open a **new** PowerShell window and verify: `java -version`

### 2. GStreamer 1.26.7 (MSVC x86_64)

GStreamer is the multimedia framework that does all the audio processing. You need the **MSVC version** (not MinGW).

**Download these two files:**

**Runtime package** (required to run):
- URL: https://gstreamer.freedesktop.org/data/pkg/windows/1.26.7/msvc/gstreamer-1.0-msvc-x86_64-1.26.7.msi
- Size: ~150 MB

**Development package** (required to build):
- URL: https://gstreamer.freedesktop.org/data/pkg/windows/1.26.7/msvc/gstreamer-1.0-devel-msvc-x86_64-1.26.7.msi  
- Size: ~20 MB

**Install both packages:**

1. **Install runtime first:**
   - Double-click `gstreamer-1.0-msvc-x86_64-1.26.7.msi`
   - **IMPORTANT:** Install to the default path: `C:\gstreamer\`
   - Select "Complete" installation (not "Typical")
   - Click through to finish

2. **Install development package:**
   - Double-click `gstreamer-1.0-devel-msvc-x86_64-1.26.7.msi`
   - Install to the **same** path: `C:\gstreamer\`
   - Select "Complete" installation
   - Click through to finish

**Add GStreamer to your PATH:**

Open PowerShell **as Administrator** and run:

```powershell
[Environment]::SetEnvironmentVariable(
    "Path",
    [Environment]::GetEnvironmentVariable("Path", "Machine") + ";C:\gstreamer\1.0\msvc_x86_64\bin",
    "Machine"
)
```

**Verify installation:**

Close and reopen PowerShell (to pick up new PATH), then run:

```powershell
gst-inspect-1.0 --version
```

You should see:
```
gst-inspect-1.0 version 1.26.7
GStreamer 1.26.7
...
```

If you get `command not found`, the PATH wasn't set correctly. Double-check the path exists:

```powershell
Test-Path C:\gstreamer\1.0\msvc_x86_64\bin
```

Should return `True`.

### 3. Git (to clone this repository)

**Check if you have it:**
```powershell
git --version
```

**If you need to install Git:**
1. Download from: https://git-scm.com/download/win
2. Run installer with default options
3. Reopen PowerShell and verify: `git --version`

## Getting the Code

Open PowerShell and navigate to where you want the code:

```powershell
cd C:\Users\YourName\Projects  # or wherever you keep code
git clone https://github.com/benashby/synauson.git
cd synauson\examples\jsyn-windows-quickstart
```

You should now be in the quickstart directory. Verify with:

```powershell
ls
```

You should see:
```
build.gradle.kts
gradle\
gradlew
gradlew.bat
README.md
settings.gradle.kts
src\
```

## Configuring Nexus Credentials

JSyn libraries are published to a private Nexus repository. You'll receive credentials separately (username and password).

**Edit `gradle.properties`:**

Open `gradle.properties` in a text editor (Notepad++ or VS Code):

```powershell
notepad gradle.properties
```

You'll see:
```properties
nexusUser=github-ci
nexusPassword=<some-password>
```

**Replace the values** with the credentials you were given:

```properties
nexusUser=your-username-here
nexusPassword=your-password-here
```

Save and close the file.

**⚠️ Security note:** This file contains credentials. Don't commit it to git or share it publicly.

## Running Your First Example

Let's verify everything works by running the file playback example.

**Build the project:**

```powershell
.\gradlew.bat build
```

First time will take ~30 seconds to download dependencies. You should see:

```
BUILD SUCCESSFUL in 15s
```

**Run the example:**

```powershell
.\gradlew.bat run
```

You should see output like this:

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

**If you see this, you're done!** JSyn is working correctly.

## Understanding the Examples

The quickstart includes three examples that demonstrate different JSyn capabilities:

### Example 1: File Playback (simplest)

**What it does:** Plays a generated WAV file through JSyn

**Run it:**
```powershell
.\gradlew.bat run
```

**Code:** `src\main\java\com\example\jsyn\FilePlaybackExample.java`

**Key concepts:**
- Initializing JSyn runtime
- Creating a conference
- Adding a file participant
- Listening to events (playback started, playback finished)
- Clean shutdown

**When to use this pattern:**
- Playing hold music
- Playing announcements
- Playing pre-recorded messages

### Example 2: Voice Activity Detection (VAD)

**What it does:** Detects when someone is speaking vs. silence

**⚠️ Requires ONNX models** (not included - see below)

**Run it:**
```powershell
.\gradlew.bat runVadExample
```

**Code:** `src\main\java\com\example\jsyn\VadDetectionExample.java`

**Key concepts:**
- Creating a native participant (for programmatic audio I/O)
- Writing raw PCM audio bytes
- Subscribing to VAD events (speech start/end)
- Real-time voice activity detection

**When to use this pattern:**
- Detecting when participants are speaking
- Implementing push-to-talk features
- Trimming silence from recordings

**About ONNX models:** The VAD example requires machine learning models. If you don't have them, you'll see:

```
ERROR: ONNX models not found at: C:\...\models
Expected: silero_vad.onnx
```

You can skip this example for now and use the file playback or native I/O examples instead.

### Example 3: Native Participant Bidirectional I/O

**What it does:** Shows how to send and receive raw audio bytes

**Run it:**
```powershell
.\gradlew.bat runNativeIOExample
```

**Code:** `src\main\java\com\example\jsyn\NativeParticipantIOExample.java`

**Key concepts:**
- Creating a native participant
- **Ingress (write):** Sending PCM bytes from Java into the conference
- **Egress (read):** Reading mixed conference audio from the conference into Java
- Saving audio to a WAV file
- Audio routing (connecting participants)

**When to use this pattern:**
- Building a softphone (send/receive real-time audio)
- Recording conference audio
- Processing audio in real-time (echo cancellation, noise reduction, etc.)
- Streaming audio to/from external systems

**How it works:**

```
File Participant               Native Participant
(plays test.wav) ───────────> (your Java code)
                                  ├─ write(): send audio into conference
                                  └─ read(): receive mixed audio from conference
```

The example:
1. Creates a file participant playing a test WAV
2. Creates a native participant that receives the file audio
3. Writes synthetic audio via `write()` (ingress)
4. Reads mixed conference audio via `read()` (egress)
5. Saves the received audio to `output.wav`
6. Validates that audio was received successfully

**Expected output:**

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

This proves the JNI layer is working correctly for both directions.

## Using JSyn in Your Own Project

Ready to integrate JSyn into your application? Here's how:

### 1. Copy dependency configuration

Create a `build.gradle.kts` in your project with these dependencies:

```kotlin
plugins {
    application
    java
}

repositories {
    mavenCentral()
    maven {
        name = "BenashbyNexus"
        url = uri("https://nexus.benashby.com/repository/maven-public")
        credentials {
            username = System.getenv("NEXUS_USER") ?: project.findProperty("nexusUser") as String?
            password = System.getenv("NEXUS_PASSWORD") ?: project.findProperty("nexusPassword") as String?
        }
    }
}

dependencies {
    // JSyn API - pure Java
    implementation("com.synauson:jsyn:main-65a2237-202605252000-SNAPSHOT")
    
    // Windows native libraries (includes synauson_jni.dll + onnxruntime.dll)
    runtimeOnly("com.synauson:jsyn-natives-windows:main-65a2237-202605252000-SNAPSHOT")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(11)
    }
}
```

### 2. Add Nexus credentials

Create `gradle.properties` (in your project root):

```properties
nexusUser=your-username
nexusPassword=your-password
```

**Or** set environment variables (better for CI/CD):

```powershell
$env:NEXUS_USER = "your-username"
$env:NEXUS_PASSWORD = "your-password"
```

### 3. Initialize JSyn in your code

```java
import com.synauson.jsyn.JSyn;
import com.synauson.jsyn.JSynConfig;
import com.synauson.jsyn.participant.Conference;

public class MyApp {
    public static void main(String[] args) {
        // Configure JSyn
        JSynConfig config = JSynConfig.builder()
            .modelsDir("C:/path/to/models")  // Can be empty if not using VAD/SmartTurn
            .rtpPortMin(40000)                // UDP ports for RTP (future SIP/WebRTC)
            .rtpPortMax(40199)
            .build();
        
        // Initialize JSyn runtime (loads native libraries)
        try (JSyn jsyn = new JSyn(config)) {
            
            // Create a conference
            try (Conference conference = jsyn.startConference("my-conference")) {
                
                // Add participants, route audio, etc.
                // See examples for patterns
                
            } // Conference auto-terminates
            
        } // JSyn auto-shuts down
    }
}
```

### 4. Common patterns

**Play a file:**
```java
conference.addFileParticipant(FileParticipantSpec.builder()
    .id("file-1")
    .uri("file:///C:/path/to/audio.wav")
    .loopPlayback(false)
    .build());
```

**Create a native participant for custom audio I/O:**
```java
NativeParticipant participant = conference.addNativeParticipant(
    "native-1",
    NativeParticipantSpec.builder()
        .format(NativeAudioFormat.PCM_S16LE16K_MONO)
        .build()
);

// Send audio into conference
byte[] pcmData = ...; // Your audio bytes (16-bit PCM, 16kHz, mono)
participant.write(pcmData, 0, pcmData.length);

// Read audio from conference
byte[] buffer = new byte[1600]; // 50ms at 16kHz
int bytesRead = participant.read(buffer, 0, buffer.length);
```

**Listen to events:**
```java
Subscription sub = conference.streamFileEvents("file-1", event -> {
    if (event instanceof FileEvent.PlaybackStarted) {
        System.out.println("Playback started!");
    }
});
// Don't forget to close subscription when done
sub.close();
```

**Route audio between participants:**
```java
conference.updatePartyAudioConnections(new ConnectionMatrix(
    ConnectionEntry.connect("source-id", "destination-id")
));
```

## Troubleshooting

### "Could not resolve com.synauson:jsyn"

**Problem:** Gradle can't download JSyn from Nexus.

**Solutions:**
1. Check your `gradle.properties` - are credentials correct?
2. Try setting environment variables instead:
   ```powershell
   $env:NEXUS_USER = "your-username"
   $env:NEXUS_PASSWORD = "your-password"
   .\gradlew.bat build --refresh-dependencies
   ```
3. Test Nexus access directly:
   ```powershell
   curl -u "username:password" https://nexus.benashby.com/repository/maven-public/
   ```

### "UnsatisfiedLinkError: no synauson_jni in java.library.path"

**Problem:** Native library (DLL) not loading.

**Solutions:**
1. Make sure `jsyn-natives-windows` is in your `runtimeOnly` dependencies
2. Check that GStreamer bin directory is in PATH:
   ```powershell
   $env:PATH -split ';' | Select-String gstreamer
   ```
   Should show: `C:\gstreamer\1.0\msvc_x86_64\bin`
3. Restart your IDE/terminal to pick up new PATH

### "Failed to initialize GStreamer"

**Problem:** GStreamer not installed correctly.

**Solutions:**
1. Verify installation:
   ```powershell
   gst-inspect-1.0 --version
   ```
2. Check exact path:
   ```powershell
   Test-Path C:\gstreamer\1.0\msvc_x86_64\bin\gst-inspect-1.0.exe
   ```
   Should return `True`
3. Reinstall both GStreamer packages (runtime + devel) to `C:\gstreamer\`

### "Failed to load ONNX model"

**Problem:** VAD example can't find machine learning models.

**Solution:** This is expected if you don't have ONNX models. Use the file playback or native I/O examples instead, which don't require models.

If you need VAD/SmartTurn capabilities, ask for the models directory separately.

### Build is slow / keeps re-downloading

**Problem:** Gradle daemon isn't running.

**Solution:** Remove `--no-daemon` flag from commands. First build after daemon starts takes longer, then subsequent builds are fast.

### "java.lang.OutOfMemoryError"

**Problem:** Not enough heap for Gradle or JSyn.

**Solutions:**
1. Increase Gradle heap in `gradle.properties`:
   ```properties
   org.gradle.jvmargs=-Xmx2g
   ```
2. Increase app heap when running:
   ```powershell
   .\gradlew.bat run -Dorg.gradle.jvmargs="-Xmx2g"
   ```

## Next Steps

Now that you have JSyn working:

1. **Read the examples** - Look at the source code in `src\main\java\com\example\jsyn\` to understand the patterns
2. **Experiment** - Modify the examples to play your own WAV files or generate different audio
3. **Check the API docs** - JSyn classes are documented with JavaDoc
4. **Ask questions** - If something doesn't work, ask! Common issues are usually simple fixes

## Version Information

This quickstart was tested with:

- **JSyn version:** `main-65a2237-202605252000-SNAPSHOT`
- **GStreamer:** 1.26.7 (MSVC x86_64)
- **ONNX Runtime:** 1.24.4 (embedded in jsyn-natives-windows)
- **Java:** 11 or later
- **Windows:** Windows 10/11 (64-bit)

## Quick Reference

**Build project:**
```powershell
.\gradlew.bat build
```

**Run examples:**
```powershell
.\gradlew.bat run                    # File playback
.\gradlew.bat runVadExample          # VAD (requires models)
.\gradlew.bat runNativeIOExample     # Bidirectional I/O
```

**Clean build:**
```powershell
.\gradlew.bat clean build
```

**Refresh dependencies:**
```powershell
.\gradlew.bat build --refresh-dependencies
```

**Show all tasks:**
```powershell
.\gradlew.bat tasks
```

---

**Questions or issues?** Contact your Synauson administrator.
