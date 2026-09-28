# syntax=docker/dockerfile:1.6
# =============================================================================
# jsyn licensing tour, in a container: JDK 21 and GStreamer 1.26 without
# installing either on the host.
#
# Build context: this directory.
#
#   podman build -t jsyn-licensing-tour .
#   podman run --rm -e SYNAUSON_LICENSE_KEY jsyn-licensing-tour            # every chapter
#   podman run --rm -e SYNAUSON_LICENSE_KEY jsyn-licensing-tour limits     # some of them
#
# The tour writes everything (model store, state directories, license files)
# under /tour; mount a volume there to keep the downloaded models between runs.
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
# debian:trixie ships GStreamer 1.26 and OpenJDK 21. (The eclipse-temurin
# runtime images moved to Ubuntu 26.04 and GStreamer 1.28; see the testbed's
# Dockerfile for why that matters to synauson.)
FROM debian:trixie
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
      openjdk-21-jre-headless \
      libgstreamer1.0-0 \
      gstreamer1.0-plugins-base \
      gstreamer1.0-plugins-good \
      gstreamer1.0-plugins-bad \
      gstreamer1.0-nice \
      ca-certificates \
 && rm -rf /var/lib/apt/lists/*

COPY --from=build /work/build/install/jsyn-licensing/ /opt/jsyn-licensing/
# UTF-8 so the tour's ✓ and ▸ survive; debian's default locale is POSIX.
ENV LANG=C.UTF-8
ENV JAVA_OPTS=-Dtour.workDir=/tour
WORKDIR /tour
ENTRYPOINT ["/opt/jsyn-licensing/bin/jsyn-licensing"]
