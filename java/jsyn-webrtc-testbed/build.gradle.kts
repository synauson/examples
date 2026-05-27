plugins {
    java
    id("org.springframework.boot") version "3.5.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.synauson"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    // Benashby Nexus — same source used by jsyn-windows-quickstart.
    // Credentials come from env vars in CI and gradle.properties in dev.
    maven {
        name = "BenashbyNexus"
        url = uri("https://nexus.benashby.com/repository/maven-public")
        credentials {
            username = System.getenv("NEXUS_USER")
                ?: project.findProperty("nexusUser") as String? ?: ""
            password = System.getenv("NEXUS_PASSWORD")
                ?: project.findProperty("nexusPassword") as String? ?: ""
        }
    }
}

// JSyn coordinates. Bump these to whichever snapshot is current in Nexus
// when re-running the build — the version strings encode branch, sha, and
// timestamp so they sort lexicographically.
val jsynVersion = "main-c15ac7f-202605262244-SNAPSHOT"
val jsynNativesLinuxVersion = "main-c15ac7f-202605262244-SNAPSHOT"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // JSyn — pure Java API for the Synauson media server.
    implementation("com.synauson:jsyn:$jsynVersion")
    // Platform-specific native libraries (libsynauson_jni.so + libonnxruntime.so).
    // NativeLoader inside JSyn extracts these from the classpath at JVM startup.
    runtimeOnly("com.synauson:jsyn-natives-linux:$jsynNativesLinuxVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // GStreamer + ONNX Runtime are process-global singletons. Each test
    // class gets its own JVM so the native runtime is torn down cleanly
    // between integration tests, matching the rust workspace's --test-threads=1.
    setForkEvery(1)
    maxParallelForks = 1
}

// ---------------------------------------------------------------------------
// Frontend build wiring.
//
// Vite's outDir is ../src/main/resources/static so the production bundle lands
// directly on Spring Boot's classpath. The Exec task declares its inputs and
// outputs so Gradle skips `npm run build` when nothing has changed in the
// frontend source tree.
//
// processResources depends on frontendBuild, which means `./gradlew bootJar`
// transparently produces a single fat jar containing the compiled SPA.
// ---------------------------------------------------------------------------
val frontendDir = layout.projectDirectory.dir("frontend")
val frontendStaticOut = layout.projectDirectory.dir("src/main/resources/static")

val frontendInstall = tasks.register<Exec>("frontendInstall") {
    workingDir = frontendDir.asFile
    inputs.files("frontend/package.json", "frontend/package-lock.json")
    outputs.dir("frontend/node_modules")
    commandLine("npm", "ci", "--no-audit", "--no-fund")
}

val frontendBuild = tasks.register<Exec>("frontendBuild") {
    dependsOn(frontendInstall)
    workingDir = frontendDir.asFile
    inputs.dir("frontend/src")
    inputs.files(
        "frontend/package.json",
        "frontend/package-lock.json",
        "frontend/vite.config.ts",
        "frontend/tailwind.config.js",
        "frontend/postcss.config.js",
        "frontend/tsconfig.json",
        "frontend/index.html",
    )
    outputs.dir(frontendStaticOut)
    commandLine("npm", "run", "build")
}

tasks.named("processResources") { dependsOn(frontendBuild) }

// Allow `./gradlew bootRun` against a stale frontend bundle (no incremental
// build) by adding a -P flag. Saves a few seconds when iterating on Java only.
if (project.hasProperty("skipFrontend")) {
    tasks.named("frontendBuild") { enabled = false }
    tasks.named("frontendInstall") { enabled = false }
}
