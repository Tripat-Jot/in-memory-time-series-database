# syntax=docker/dockerfile:1
#
# Production-oriented image for MemTSDB (Java 21 + Spring Boot fat JAR).
#
# Containerization packages the JVM, bytecode, and minimal OS libs into one immutable artifact so every environment
# runs the same bits — analogous to publishing a self-contained .NET runtime bundle + app.
#
# Build:
#   docker build -t memtsdb:latest .
# Run:
#   docker run --rm -p 8080:8080 -e PORT=8080 -v memtsdb-wal:/data/wal memtsdb:latest
#
# JVM tuning is applied via JAVA_OPTS (see docs/deployment-render.md); base image respects cgroup memory limits when
# using -XX:+UseContainerSupport -XX:MaxRAMPercentage=….

ARG MAVEN_IMAGE=maven:3.9.9-eclipse-temurin-21
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre-alpine

# ─── Stage 1: compile (JDK + Maven; not shipped to production) ─────────────────
FROM ${MAVEN_IMAGE} AS build
WORKDIR /build

# Dependency layer — improves rebuild times when only sources change.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline -DskipTests || true

COPY src ./src
RUN mvn -q -B -DskipTests package \
    && ls -la target/*.jar

# ─── Stage 2: runtime (JRE only — smaller attack surface & image size) ─────────
FROM ${RUNTIME_IMAGE} AS runtime

ARG APP_USER=memtsdb
ARG APP_UID=10001
ARG APP_GID=10001

LABEL org.opencontainers.image.title="MemTSDB" \
      org.opencontainers.image.description="In-memory TSDB (Spring Boot / Java 21)" \
      org.opencontainers.image.vendor="memtsdb"

RUN apk add --no-cache curl ca-certificates \
    && addgroup -g "${APP_GID}" -S "${APP_USER}" \
    && adduser -u "${APP_UID}" -S -G "${APP_USER}" -h /opt/memtsdb "${APP_USER}"

WORKDIR /opt/memtsdb

# Render & many PaaS assign HTTP port via PORT; Spring reads it from application.properties (${PORT:8080}).
ENV PORT=8080 \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom" \
    MEMTSDB_WAL_PATH=/data/wal/metrics.wal \
    SPRING_PROFILES_ACTIVE=prod

# Writable WAL directory (mount a volume in production for persistence across restarts).
RUN mkdir -p /data/wal && chown -R "${APP_USER}:${APP_USER}" /data/wal

COPY --from=build --chown="${APP_USER}:${APP_USER}" /build/target/memtsdb-1.0.0-SNAPSHOT.jar /opt/memtsdb/app.jar

USER ${APP_USER}

EXPOSE 8080

# Graceful shutdown: Spring Boot reacts to SIGTERM; exec ensures PID 1 is java for signal delivery.
ENTRYPOINT exec java ${JAVA_OPTS} -jar /opt/memtsdb/app.jar

# Uses PORT at runtime (matches Render). curl fallback if wget absent on future base images.
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${PORT}/api/v1/health" >/dev/null || exit 1
