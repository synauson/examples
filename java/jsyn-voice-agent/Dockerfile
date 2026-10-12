# syntax=docker/dockerfile:1.6
# =============================================================================
# jsyn voice agent example, in a container: JDK 21 and GStreamer 1.26 without
# installing either on the host.
#
# Build context: this directory.
#
#   docker build -t jsyn-voice-agent .
#   docker run --rm -e SYNAUSON_LICENSE_KEY -v jsyn-voice-agent:/data jsyn-voice-agent
#   docker run --rm -e SYNAUSON_LICENSE_KEY -v jsyn-voice-agent:/data -v "$PWD:/work" -w /work \
#       jsyn-voice-agent speak "Hello there." hello.wav
#
# The model store and the cached license live under /data; mount a volume there
# so the models download once.
# =============================================================================

# ---- build -------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /work
COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src/ src/
RUN ./gradlew --no-daemon installDist

# ---- runtime -----------------------------------------------------------------
# debian:trixie ships GStreamer 1.26 and OpenJDK 21.
FROM debian:trixie
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
      openjdk-21-jre-headless \
      libgstreamer1.0-0 \
      gstreamer1.0-plugins-base \
      gstreamer1.0-plugins-good \
      gstreamer1.0-plugins-bad \
      ca-certificates \
 && rm -rf /var/lib/apt/lists/*

COPY --from=build /work/build/install/jsyn-voice-agent/ /opt/jsyn-voice-agent/
ENV JAVA_OPTS=-Dvoiceagent.workDir=/data
WORKDIR /data
ENTRYPOINT ["/opt/jsyn-voice-agent/bin/jsyn-voice-agent"]
