# The caller must pass a complete digest-pinned Java 21 base, for example:
# --build-arg TEMURIN_BASE=eclipse-temurin:21-jdk-jammy@sha256:<64-hex>
ARG TEMURIN_BASE
FROM ${TEMURIN_BASE}

USER root
WORKDIR /opt/streamlens-prewarm

COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties gradle.lockfile ./
COPY src/main src/main
COPY src/test src/test
COPY src/jmh src/jmh

# Network is intentionally needed only while constructing the trusted image.
# Runtime assessment always uses Gradle offline with a private writable copy of
# this prewarmed cache and never mounts candidate-writable output to the host.
RUN chmod 0755 gradlew \
    && ./gradlew --no-daemon --console=plain test jmhJar \
    && mkdir -p /opt/streamlens-gradle-cache \
    && cp -a /root/.gradle/. /opt/streamlens-gradle-cache/ \
    && rm -rf /root/.gradle /opt/streamlens-prewarm \
    && chown -R 65532:65532 /opt/streamlens-gradle-cache \
    && find /opt/streamlens-gradle-cache -type d -exec chmod 0555 {} + \
    && find /opt/streamlens-gradle-cache -type f -exec chmod 0444 {} +

WORKDIR /tmp
USER 65532:65532

LABEL org.opencontainers.image.title="StreamLens Java assessment runtime" \
      org.opencontainers.image.description="Pinned offline JDK/Gradle/JUnit/JMH runtime; digest pin required"
