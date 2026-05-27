plugins {
    application
    java
}

group = "com.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    // Benashby Nexus - includes both snapshots and releases
    maven {
        name = "BenashbyNexus"
        url = uri("https://nexus.benashby.com/repository/maven-public")
        isAllowInsecureProtocol = false
        credentials {
            username = System.getenv("NEXUS_USER") ?: project.findProperty("nexusUser") as String? ?: ""
            password = System.getenv("NEXUS_PASSWORD") ?: project.findProperty("nexusPassword") as String? ?: ""
        }
    }
}

dependencies {
    // JSyn API - pure Java library for Synauson media server integration
    implementation("com.synauson:jsyn:main-f970797-202605260203-SNAPSHOT")

    // Platform-specific native libraries (Windows x86_64)
    // Contains synauson_jni.dll and onnxruntime.dll
    runtimeOnly("com.synauson:jsyn-natives-windows:main-f45a548-202605261302-SNAPSHOT")
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
