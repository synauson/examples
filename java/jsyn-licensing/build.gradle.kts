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

// jsyn and its natives are released together; keep them equal.
val jsynVersion = "1.4.0"
val nativesArtifact =
    if (System.getProperty("os.name").lowercase().contains("windows")) "jsyn-natives-windows"
    else "jsyn-natives-linux"

dependencies {
    implementation("com.synauson:jsyn:$jsynVersion")
    // libsynauson_jni + onnxruntime for this OS; jsyn extracts them at startup.
    runtimeOnly("com.synauson:$nativesArtifact:$jsynVersion")
}

application {
    mainClass = "com.example.licensing.LicensingTour"
}

tasks.named<JavaExec>("run") {
    // Everything the tour writes (model store, state dirs, license files) goes
    // under build/, so `./gradlew clean` starts it from scratch.
    workingDir = projectDir
    systemProperty("tour.workDir", layout.buildDirectory.dir("licensing-tour").get().asFile.path)
}
