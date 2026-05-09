# Deploying MemTSDB on Render

This guide covers running the **Docker** image as a **Render Web Service**, wiring **environment variables**, attaching **persistent disk** for the WAL, and **JVM tuning** for container memory limits.

---

## Why containerization helps

A **container image** bundles your application JAR, the Java runtime, and minimal OS libraries into a single immutable unit. The host only needs a container runtime (Docker-compatible). That yields:

- **Same artifact everywhere** — build once in CI, deploy the digest to Render (or any registry).
- **Isolation** — process boundaries and filesystem namespaces reduce “works on my machine” drift.
- **Operational symmetry** — health checks, signals (`SIGTERM`), and resource limits apply uniformly.

MemTSDB’s `Dockerfile` uses a **multi-stage build**: the Maven/JDK toolchain stays in the build stage; the runtime stage ships only the **JRE** and the fat JAR, shrinking size and attack surface.

---

## Portability

- **CPU architecture**: Build images for `linux/amd64` (Render’s default) unless you explicitly enable ARM builders.
- **Port binding**: Render injects **`PORT`**. This project sets `server.port=${PORT:8080}` in `application.properties`, so the Spring Boot embedded Tomcat listens on Render’s assigned port without code changes.
- **Stateful data**: The in-memory store is ephemeral; **durability across restarts** depends on the **WAL file** on disk. Mount a **persistent disk** at `/data/wal` and set `MEMTSDB_WAL_PATH=/data/wal/metrics.wal` (Dockerfile default).

---

## JVM tuning in containers

The JVM historically assumed it owned the whole machine. **Container-aware flags** matter when cgroup memory limits apply:

| Flag | Role |
|------|------|
| `-XX:+UseContainerSupport` | Read cgroup memory/CPU limits (JDK 10+; default on modern JDKs). |
| `-XX:MaxRAMPercentage=75.0` | Cap heap as a fraction of **container** memory — avoids OOM killing the pod when heap plus metaspace plus native memory exceeds the limit. |
| `-XX:+ExitOnOutOfMemoryError` | Fast-fail so orchestrators restart unhealthy instances instead of limping. |

Override via Render **environment variable** `JAVA_OPTS` (the `Dockerfile` `ENTRYPOINT` expands `java ${JAVA_OPTS} -jar …`). Example for a **512 MB** service:

```text
JAVA_OPTS=-XX:+UseContainerSupport -XX:MaxRAMPercentage=70.0 -XX:+ExitOnOutOfMemoryError
```

Tune **MaxRAMPercentage** down if you see native OOMs or heavy direct buffers; tune up only after measuring GC logs.

---

## Crash recovery & durability (short)

On startup, **WAL replay** rebuilds memory from `metrics.wal`. If the disk is **empty** on first deploy, replay is a no-op. After restarts, replay restores whatever was **flushed** to that file. Async WAL + buffered IO still imply a **small loss window** on hard failure — see main README WAL section.

---

## One-click blueprint (`render.yaml`)

Commit [`render.yaml`](../render.yaml) at the repo root and use **Blueprint** in the Render dashboard to create/update services. Adjust `plan`, region, and disk size for your project.

After deploy, open `https://<service-name>.onrender.com/swagger-ui.html` (or your custom domain).

---

## Manual setup (dashboard)

1. **New → Web Service** → connect the Git repository.
2. **Runtime**: Docker.
3. **Dockerfile path**: `Dockerfile` (root).
4. **Instance type**: choose RAM based on expected series cardinality (in-memory store grows with data).
5. **Environment** (minimum):

   | Key | Value |
   |-----|--------|
   | `SPRING_PROFILES_ACTIVE` | `prod` |
   | `JAVA_OPTS` | `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError` |

   Render sets **`PORT`** automatically — do not hardcode `8080` in production Start Command.

6. **Persistent disk**: mount path **`/data/wal`**, size ≥ 1 GB (adjust for WAL growth).
7. **Health check path**: `/api/v1/health` (HTTP).

---

## Build & run locally (parity with Render)

```bash
docker build -t memtsdb:prod .
docker run --rm -p 8080:8080 \
  -e PORT=8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -v memtsdb-wal:/data/wal \
  memtsdb:prod
```

---

## Troubleshooting

| Symptom | Check |
|---------|--------|
| Service exits immediately | Render logs; OOM → lower `MaxRAMPercentage` or raise plan RAM. |
| 502 / connection refused | `PORT` mismatch — ensure no custom start command overrides Spring’s `server.port`. |
| Empty data after restart | Disk not mounted at `/data/wal`, or `MEMTSDB_WAL_PATH` points elsewhere. |
| Slow ingest | WAL on slow disk; consider larger instance or tmpfs only if you accept volatility. |

---

## Related files

- [`Dockerfile`](../Dockerfile) — multi-stage production image, non-root user, health check.
- [`deploy/env.example`](../deploy/env.example) — environment variable reference.
- [`application-prod.properties`](../src/main/resources/application-prod.properties) — prod Spring profile.
