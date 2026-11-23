# syntax=docker/dockerfile:1
# Placeholder image for Ledgerline (SpecShield Labs internal demonstration, synthetic data only).
# Base images are pinned by multi-arch index digest; Dependabot (docker ecosystem) keeps them current.

# ---- Build stage -------------------------------------------------------------
FROM eclipse-temurin:25-jdk-noble@sha256:4ddc1a8bdaf283421df5487411aff10b797ccf1e06a339041005114c8f489b5c AS build
WORKDIR /workspace

# mvnw falls back to the .tar.gz distribution when unzip is missing, which would not match the pinned
# distributionSha256Sum (computed for the -bin.zip). Install unzip so every platform verifies the same file.
RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip \
 && rm -rf /var/lib/apt/lists/*

# Copy build descriptors first so dependency resolution is cached between source changes.
COPY mvnw ./
COPY .mvn/ .mvn/
COPY pom.xml ./
COPY ledgerline-api/pom.xml ledgerline-api/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -pl ledgerline-api dependency:go-offline

COPY ledgerline-api/src ledgerline-api/src
# The operations console is built by the same Maven run (Node and npm are downloaded, pinned, into ops-console/node)
# and packaged into the jar's static/ folder, so one container serves both.
COPY ops-console/ ops-console/
# Tests run in CI (`./mvnw verify`), not inside the image build.
RUN --mount=type=cache,target=/root/.m2 --mount=type=cache,target=/root/.npm \
    ./mvnw -B -q -pl ledgerline-api package -DskipTests \
 && cp ledgerline-api/target/ledgerline-api-*.jar /workspace/app.jar \
 && java -Djarmode=tools -jar /workspace/app.jar extract --layers --launcher --destination /workspace/extracted

# ---- Runtime stage -----------------------------------------------------------
FROM eclipse-temurin:25-jre-noble@sha256:f3dd0838e69b881cc382237d5901e74ae3705b1056ad964b2e0e523b174fef99
LABEL org.opencontainers.image.title="ledgerline-api" \
      org.opencontainers.image.description="SpecShield Labs internal demonstration. Synthetic data only." \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/specshield-io/ledgerline"

RUN groupadd --system --gid 10001 ledgerline \
 && useradd --system --uid 10001 --gid ledgerline --no-create-home --shell /usr/sbin/nologin ledgerline

WORKDIR /app
# Spring Boot layers, least to most frequently changing.
COPY --from=build --chown=ledgerline:ledgerline /workspace/extracted/dependencies/ ./
COPY --from=build --chown=ledgerline:ledgerline /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=ledgerline:ledgerline /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=ledgerline:ledgerline /workspace/extracted/application/ ./

USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# Reported by GET /api/v1/meta. Pass it at build time: docker build --build-arg GIT_SHA=$(git rev-parse --short HEAD)
ARG GIT_SHA=unknown
ENV LEDGERLINE_GIT_SHA=${GIT_SHA}
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
