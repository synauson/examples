plugins {
    application
    java
}

group = "com.example"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    // The public Synauson Maven repository; no credentials needed.
    maven {
        name = "Synauson"
        url = uri("https://maven.synauson.com/releases")
    }
}

// jsyn and its natives are versioned separately: an engine-only fix is a
// natives release with no new jsyn.
val jsynVersion = "1.6.0"
val jsynNativesVersion = "1.7.1"
val nativesArtifact =
    if (System.getProperty("os.name").lowercase().contains("windows")) "jsyn-natives-windows"
    else "jsyn-natives-linux"

dependencies {
    implementation("com.synauson:jsyn:$jsynVersion")
    // libsynauson_jni + onnxruntime for this OS; jsyn extracts them at startup.
    runtimeOnly("com.synauson:$nativesArtifact:$jsynNativesVersion")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "com.example.voiceagent.Main"
}

tasks.named<JavaExec>("run") {
    // The model store and the cached license go under build/, so `./gradlew clean`
    // starts from scratch (and downloads the models again).
    workingDir = projectDir
    systemProperty("voiceagent.workDir", layout.buildDirectory.dir("voice-agent").get().asFile.path)
    standardInput = System.`in`
}

tasks.test {
    useJUnitPlatform()
    systemProperty("voiceagent.workDir", layout.buildDirectory.dir("voice-agent").get().asFile.path)
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
