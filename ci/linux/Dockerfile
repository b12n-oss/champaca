# Linux for champaca: OpenJDK 25.0.2 with its jmods (the build this repo's
# .expected files come from), babashka, and what building jolt from source
# needs (jolt's own make provisions its Chez Scheme). Every download is pinned
# by SHA-256. Build it with `bb linux:image [arm64|amd64]`, not plain `docker
# build`: that task fetches the JDK and passes its file name and checksum.
#
# The JDK comes from the build context (ci/linux/cache/, which `bb linux:image`
# fills) because Temurin's builds of 25 ship no jmods and the proxy some
# machines sit behind refuses download.java.net from inside a container.
# Archives are unpacked with bsdtar: GNU tar on Ubuntu 26.04 fails under x86_64
# emulation on Apple silicon ("Cannot open: Function not implemented").
FROM ubuntu:26.04
# TARGETARCH comes from BuildKit (the classic builder leaves it empty and the
# JDK step refuses). JDK_FILE and JDK_SHA256 come from `bb linux:image`, which
# pins the JDK for each architecture in one place.
ARG TARGETARCH
ARG JDK_FILE
ARG JDK_SHA256

RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential git curl ca-certificates libncurses-dev uuid-dev \
      zlib1g-dev xz-utils unzip libarchive-tools \
 && rm -rf /var/lib/apt/lists/*

# bind-mounted, so the tarball never becomes a layer of the image
RUN --mount=type=bind,source=cache,target=/tmp/jdk \
    test -n "$JDK_FILE" && test -n "$JDK_SHA256" \
 && echo "$JDK_SHA256  /tmp/jdk/$JDK_FILE" | sha256sum -c - \
 && mkdir -p /opt/jdk && bsdtar -xzf "/tmp/jdk/$JDK_FILE" -C /opt/jdk --strip-components 1 \
 && test -f /opt/jdk/jmods/java.base.jmod
ENV JAVA_HOME=/opt/jdk
ENV PATH=/opt/jdk/bin:$PATH

# babashka 1.13.220, static
RUN case "$TARGETARCH" in \
      arm64) a=aarch64; s=0efd6ef36b93ea2f0ae6ebf9d1bcd1a66167c2f41c17e990659aa067654437e8 ;; \
      amd64) a=amd64; s=eb3edd128276f0b6fbdefcb18dc7d42652a95ea409a81b34f08e36b8ac3cbc3c ;; \
    esac \
 && curl -fsSL -o /tmp/bb.tgz "https://github.com/babashka/babashka/releases/download/v1.13.220/babashka-1.13.220-linux-${a}-static.tar.gz" \
 && echo "$s  /tmp/bb.tgz" | sha256sum -c - \
 && bsdtar -xzf /tmp/bb.tgz -C /usr/local/bin bb && rm /tmp/bb.tgz

WORKDIR /champaca
