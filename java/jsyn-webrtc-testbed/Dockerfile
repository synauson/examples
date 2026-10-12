# syntax=docker/dockerfile:1.6
# =============================================================================
# Synauson WebRTC Testbed: multi-stage Docker build
#
# Build context: the root of this repository.
#
#   docker build -f java/jsyn-webrtc-testbed/Dockerfile .
#
# The image runs the released jsyn and jsyn-natives-linux from
# maven.synauson.com, pinned in build.gradle.kts.
#
# Stages:
#   frontend      Node 22 builds the Vite + React + TS bundle into /work/frontend/dist
#   backend       JDK 21 compiles the Spring Boot app; copies the frontend dist
#                 into src/main/resources/static before running bootJar
#   runtime       JRE 21 + GStreamer 1.26 from apt, and the app jar
#
# The license key comes from SYNAUSON_LICENSE_KEY at run time. The runtime downloads
# the licensed models into /var/lib/synauson/models and caches the license file in
# /var/lib/synauson/state; mount volumes there so restarts reuse them.
# =============================================================================
ARG NODE_VERSION=22
ARG JDK_VERSION=21

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

# ---- runtime -----------------------------------------------------------------
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

# NativeLoader (inside JSyn) extracts libsynauson_jni.so and libonnxruntime.so
# from the classpath at JVM startup; the models are downloaded at run time.
RUN mkdir -p /var/lib/synauson/models /var/lib/synauson/state
COPY --from=backend /work/build/libs/synauson-webrtc-testbed-*.jar /opt/testbed/app.jar

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

