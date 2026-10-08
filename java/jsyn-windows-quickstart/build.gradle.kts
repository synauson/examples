plugins {
    application
    java
}

group = "com.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    // The public Synauson Maven repository; no credentials needed.
    maven {
        name = "Synauson"
        url = uri("https://maven.synauson.com/releases")
    }
}

dependencies {
    // JSyn API - pure Java library for Synauson media server integration
    implementation("com.synauson:jsyn:1.5.0")

    // Platform-specific native libraries (Windows x86_64), released with jsyn.
    // Contains synauson_jni.dll and onnxruntime.dll
    runtimeOnly("com.synauson:jsyn-natives-windows:1.5.0")
}

application {
    mainClass = "com.example.jsyn.FilePlaybackExample"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(11)
    }
}

tasks.register("runVadExample", JavaExec::class) {
    group = "application"
    description = "Run the VAD (Voice Activity Detection) example"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.example.jsyn.VadDetectionExample"
}

tasks.register("runNativeIOExample", JavaExec::class) {
    group = "application"
    description = "Run the NativeParticipant bidirectional I/O example"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.example.jsyn.NativeParticipantIOExample"
}
