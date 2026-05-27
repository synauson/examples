# syntax=docker/dockerfile:1.6
# =============================================================================
# Synauson WebRTC Testbed — Multi-stage Docker build
#
# Build context: repo root (docker build -f examples/jsyn-webrtc-testbed/Dockerfile .)
#
# Stages:
#   1. frontend  — Node 22 builds the Vite + React + TS bundle into /work/frontend/dist
#   2. backend   — JDK 21 compiles the Spring Boot app; copies the frontend dist
#                  into src/main/resources/static before running bootJar
#   3. native    — Rust on debian:trixie compiles libsynauson_jni.so against the
#                  same glibc (2.41) as the runtime container. The jsyn-natives-linux
#                  artifact from Nexus is built on Ubuntu 26.04 (glibc 2.43) and
#                  cannot run on debian:trixie; building locally avoids that mismatch.
#   4. patcher   — Replaces libsynauson_jni.so inside the nested jsyn-natives-linux
#                  JAR that lives inside the Spring Boot fat jar, using zip in-place.
#   5. runtime   — JRE 21 + GStreamer 1.26 from apt; patched jar runs with the
#                  locally compiled native library.
# =============================================================================
ARG NODE_VERSION=22
ARG JDK_VERSION=21
ARG RUST_VERSION=1.88.0
ARG ORT_VERSION=1.24.4

# ---- Stage 1: frontend -------------------------------------------------------
FROM node:${NODE_VERSION}-bookworm-slim AS frontend
WORKDIR /work
# Cache the npm install layer separately so code changes don't re-trigger it.
COPY examples/jsyn-webrtc-testbed/frontend/package*.json frontend/
RUN cd frontend && npm ci --no-audit --no-fund
COPY examples/jsyn-webrtc-testbed/frontend/ frontend/
# Override outDir so Vite writes to /work/frontend/dist (the Docker build uses
# the VITE_OUT_DIR env var; local dev uses the default ../src/main/resources/static).
ENV VITE_OUT_DIR=/work/frontend/dist
RUN cd frontend && npm run build

# ---- Stage 2: backend --------------------------------------------------------
FROM eclipse-temurin:${JDK_VERSION}-jdk AS backend
WORKDIR /work
# Cache Gradle dependency resolution by copying build files first.
COPY examples/jsyn-webrtc-testbed/gradle/ gradle/
COPY examples/jsyn-webrtc-testbed/gradlew \
     examples/jsyn-webrtc-testbed/settings.gradle.kts \
     examples/jsyn-webrtc-testbed/build.gradle.kts \
     examples/jsyn-webrtc-testbed/gradle.properties ./
RUN chmod +x gradlew
# Pull Nexus credentials from build args so they're not baked into the layer.
ARG NEXUS_USER
ARG NEXUS_PASSWORD
RUN NEXUS_USER=${NEXUS_USER} NEXUS_PASSWORD=${NEXUS_PASSWORD} \
    ./gradlew --no-daemon -PskipFrontend dependencies > /dev/null 2>&1 || true
COPY examples/jsyn-webrtc-testbed/src/ src/
# Copy the Vite-built SPA from the frontend stage into Spring Boot's classpath.
COPY --from=frontend /work/frontend/dist/ src/main/resources/static/
RUN NEXUS_USER=${NEXUS_USER} NEXUS_PASSWORD=${NEXUS_PASSWORD} \
    ./gradlew --no-daemon -PskipFrontend bootJar

# ---- Stage 3: native ---------------------------------------------------------
# Builds libsynauson_jni.so from source on debian:trixie so the binary links
# against glibc 2.41 (matching the runtime stage) instead of the dev machine's
# glibc 2.43. ORT and GStreamer dev headers are required by synauson-core and
# synauson-onnx, which synauson-jni pulls in transitively.
FROM debian:trixie AS native
ARG RUST_VERSION
ARG ORT_VERSION
ARG ORT_URL=https://github.com/microsoft/onnxruntime/releases/download/v${ORT_VERSION}/onnxruntime-linux-x64-${ORT_VERSION}.tgz

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential \
      curl \
      ca-certificates \
      pkg-config \
      protobuf-compiler \
      libgstreamer1.0-dev \
      libgstreamer-plugins-base1.0-dev \
      libgstreamer-plugins-bad1.0-dev \
 && rm -rf /var/lib/apt/lists/*

RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs \
      | sh -s -- -y --default-toolchain ${RUST_VERSION} --profile minimal --no-modify-path
ENV PATH=/root/.cargo/bin:${PATH}

RUN mkdir -p /opt && \
    curl -sSL "${ORT_URL}" | tar xz -C /opt && \
    mv /opt/onnxruntime-linux-x64-${ORT_VERSION} /opt/onnxruntime

ENV ORT_DYLIB_PATH=/opt/onnxruntime/lib/libonnxruntime.so.${ORT_VERSION}
ENV PROTOC=/usr/bin/protoc

WORKDIR /src
COPY . .
RUN cargo build --release -p synauson-jni

# ---- Stage 4: patcher --------------------------------------------------------
# Replaces the pre-built libsynauson_jni.so embedded inside jsyn-natives-linux
# (which lives as a nested JAR inside the Spring Boot fat JAR) with the version
# compiled in the native stage. zip -u updates an existing entry in-place.
FROM debian:trixie AS patcher
RUN apt-get update && apt-get install -y --no-install-recommends \
      unzip \
      zip \
 && rm -rf /var/lib/apt/lists/*

COPY --from=backend /work/build/libs/synauson-webrtc-testbed-*.jar /opt/testbed/app.jar
COPY --from=native /src/target/release/libsynauson_jni.so /opt/testbed/

WORKDIR /opt/testbed
RUN set -e && \
    # Locate the nested natives JAR inside the Spring Boot fat JAR.
    NATIVES_ENTRY=$(unzip -l app.jar | grep 'jsyn-natives-linux' | awk '{print $4}') && \
    mkdir -p /tmp/patchdir && \
    unzip -j app.jar "$NATIVES_ENTRY" -d /tmp/patchdir/ && \
    NATIVES_JAR="/tmp/patchdir/$(basename $NATIVES_ENTRY)" && \
    # Find the classpath path where libsynauson_jni.so lives inside the nested JAR
    # (e.g. com/benashby/jsyn/natives/linux-x86_64/libsynauson_jni.so).
    INNER_PATH=$(unzip -l "$NATIVES_JAR" | grep 'libsynauson_jni\.so$' | awk '{print $4}') && \
    echo "Patching inner JAR entry: $INNER_PATH" && \
    # Stage the replacement .so at the identical relative path so zip replaces the
    # correct entry rather than adding a new one at the archive root.
    mkdir -p "/tmp/newso/$(dirname $INNER_PATH)" && \
    cp libsynauson_jni.so "/tmp/newso/$INNER_PATH" && \
    cd /tmp/newso && zip "$NATIVES_JAR" "$INNER_PATH" && \
    # Put the patched nested JAR back in the fat JAR using STORE (zip -0).
    # Spring Boot's nested-JAR ClassLoader requires STORED compression (method 0)
    # for inner JARs — DEFLATED breaks ClassLoader.getResourceAsStream on nested entries.
    mkdir -p "/tmp/treedir/$(dirname $NATIVES_ENTRY)" && \
    cp "$NATIVES_JAR" "/tmp/treedir/$NATIVES_ENTRY" && \
    cd /tmp/treedir && zip -0 /opt/testbed/app.jar "$NATIVES_ENTRY" && \
    rm -rf /tmp/patchdir /tmp/newso /tmp/treedir

# ---- Stage 5: runtime --------------------------------------------------------
# Use debian:trixie (Debian 13, stable since June 2025) rather than
# eclipse-temurin:JRE because eclipse-temurin floating tags now resolve to
# Ubuntu 26.04 which ships GStreamer 1.28.x. GStreamer 1.28 introduced a
# breaking change in webrtcbin's pad API (request_pad_simple("sink_%u")
# returns None) that crashes the conference on browser join. Trixie ships
# GStreamer 1.26.2 and OpenJDK 21, gets ~5 years of security support, and
# produces a smaller image than Ubuntu.
FROM debian:trixie AS runtime
ENV DEBIAN_FRONTEND=noninteractive
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ENV PATH=${JAVA_HOME}/bin:${PATH}
RUN apt-get update && apt-get install -y --no-install-recommends \
      openjdk-21-jre-headless \
      libgstreamer1.0-0 \
      gstreamer1.0-plugins-base \
      gstreamer1.0-plugins-good \
      gstreamer1.0-plugins-bad \
      gstreamer1.0-nice \
      ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# Bake the ONNX models into a known location. NativeLoader (inside JSyn)
# extracts libsynauson_jni.so and libonnxruntime.so from the classpath at
# JVM startup, but the .onnx model FILES still need to live on disk.
# Build context is the repo root so models/ is reachable here.
COPY models/ /opt/synauson/models/

COPY --from=patcher /opt/testbed/app.jar /opt/testbed/app.jar

# Environment defaults. Override at runtime with -e or docker-compose env_file.
ENV TESTBED_SERVER_PORT=8080
ENV TESTBED_MODELS_DIR=/opt/synauson/models
ENV TESTBED_CONFERENCE_ID=testbed
ENV TESTBED_STUN_SERVER=stun://stun.l.google.com:19302

EXPOSE 8080

# Run with host networking on Linux (--network host) so webrtcbin's ICE
# candidates advertise the host's real IP and media reaches the container.
ENTRYPOINT ["java", "-jar", "/opt/testbed/app.jar"]
