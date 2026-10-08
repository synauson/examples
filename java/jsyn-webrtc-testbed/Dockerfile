# syntax=docker/dockerfile:1.6
# =============================================================================
# Synauson WebRTC Testbed — Multi-stage Docker build
#
# Build context: the repo root of synauson/examples.
#
#   docker build -f java/jsyn-webrtc-testbed/Dockerfile .
#
# The default target runs the published jsyn-natives-linux. The local-natives
# target instead compiles libsynauson_jni.so from a synauson source tree placed at
# _synauson/ in the build context (synauson's own development loop uses it):
#
#   docker build --target local-natives -f java/jsyn-webrtc-testbed/Dockerfile .
#
# Stages:
#   frontend      — Node 22 builds the Vite + React + TS bundle into /work/frontend/dist
#   backend       — JDK 21 compiles the Spring Boot app; copies the frontend dist
#                   into src/main/resources/static before running bootJar
#   native        — (local-natives only) compiles libsynauson_jni.so from _synauson/
#   patcher       — (local-natives only) swaps it into the nested jsyn-natives-linux JAR
#   runtime-base  — JRE 21 + GStreamer 1.26 from apt
#   local-natives — runtime-base + the patched jar
#   runtime       — runtime-base + the jar as built (default)
#
# The license key comes from SYNAUSON_LICENSE_KEY at run time. The runtime downloads
# the licensed models into /var/lib/synauson/models and caches the license file in
# /var/lib/synauson/state; mount volumes there so restarts reuse them.
# =============================================================================
ARG NODE_VERSION=22
ARG JDK_VERSION=21
ARG RUST_VERSION=1.88.0
ARG ORT_VERSION=1.24.4

# ---- frontend ----------------------------------------------------------------
FROM node:${NODE_VERSION}-bookworm-slim AS frontend
WORKDIR /work
# Cache the npm install layer separately so code changes don't re-trigger it.
COPY java/jsyn-webrtc-testbed/frontend/package*.json frontend/
RUN cd frontend && npm ci --no-audit --no-fund
COPY java/jsyn-webrtc-testbed/frontend/ frontend/
# Override outDir so Vite writes to /work/frontend/dist (the Docker build uses
# the VITE_OUT_DIR env var; local dev uses the default ../src/main/resources/static).
ENV VITE_OUT_DIR=/work/frontend/dist
RUN cd frontend && npm run build

# ---- backend -----------------------------------------------------------------
FROM eclipse-temurin:${JDK_VERSION}-jdk AS backend
WORKDIR /work
# Cache Gradle dependency resolution by copying build files first.
COPY java/jsyn-webrtc-testbed/gradle/ gradle/
COPY java/jsyn-webrtc-testbed/gradlew \
     java/jsyn-webrtc-testbed/settings.gradle.kts \
     java/jsyn-webrtc-testbed/build.gradle.kts \
     java/jsyn-webrtc-testbed/gradle.properties ./
RUN chmod +x gradlew
RUN ./gradlew --no-daemon -PskipFrontend dependencies > /dev/null 2>&1 || true
COPY java/jsyn-webrtc-testbed/src/ src/
# Copy the Vite-built SPA from the frontend stage into Spring Boot's classpath.
COPY --from=frontend /work/frontend/dist/ src/main/resources/static/
RUN ./gradlew --no-daemon -PskipFrontend bootJar

# ---- native (local-natives only) --------------------------------------------
# Builds libsynauson_jni.so from _synauson/ on debian:trixie so the binary links
# against the runtime stage's glibc (2.41) rather than the dev machine's. ORT and
# GStreamer dev headers are required by synauson-core and synauson-onnx, which
# synauson-jni pulls in transitively.
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
COPY _synauson/ .
RUN cargo build --release -p synauson-jni

# ---- patcher (local-natives only) -------------------------------------------
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
    # (e.g. com/synauson/jsyn/natives/linux-x86_64/libsynauson_jni.so).
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

# ---- runtime-base --------------------------------------------------------------
# Use debian:trixie (Debian 13, stable since June 2025) rather than
# eclipse-temurin:JRE because eclipse-temurin floating tags now resolve to
# Ubuntu 26.04 which ships GStreamer 1.28.x. GStreamer 1.28 introduced a
# breaking change in webrtcbin's pad API (request_pad_simple("sink_%u")
# returns None) that crashes the conference on browser join. Trixie ships
# GStreamer 1.26.2 and OpenJDK 21, gets ~5 years of security support, and
# produces a smaller image than Ubuntu.
FROM debian:trixie AS runtime-base
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

# NativeLoader (inside JSyn) extracts libsynauson_jni.so and libonnxruntime.so
# from the classpath at JVM startup; the models are downloaded at run time.
RUN mkdir -p /var/lib/synauson/models /var/lib/synauson/state

# Environment defaults. Override at runtime with -e or docker-compose env_file.
ENV TESTBED_SERVER_PORT=8080
ENV TESTBED_MODEL_STORE=/var/lib/synauson/models
ENV TESTBED_STATE_DIR=/var/lib/synauson/state
ENV TESTBED_CONFERENCE_ID=testbed
ENV TESTBED_STUN_SERVER=stun://stun.l.google.com:19302

EXPOSE 8080

# Run with host networking on Linux (--network host) so webrtcbin's ICE
# candidates advertise the host's real IP and media reaches the container.
ENTRYPOINT ["java", "-jar", "/opt/testbed/app.jar"]

# ---- local-natives -------------------------------------------------------------
FROM runtime-base AS local-natives
COPY --from=patcher /opt/testbed/app.jar /opt/testbed/app.jar

# ---- runtime (default) ---------------------------------------------------------
FROM runtime-base AS runtime
COPY --from=backend /work/build/libs/synauson-webrtc-testbed-*.jar /opt/testbed/app.jar
