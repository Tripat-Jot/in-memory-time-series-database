# MemTSDB

Production-style **in-memory time-series database** demo on **Java 21** + **Spring Boot** + **Maven**, with interned tags, concurrent structures, repository abstraction, facade ingestion, and asynchronous WAL persistence.

## Build and run (local)

Requires JDK 21 and Maven 3.9+.

```bash
cd memtsdb
mvn -q -DskipTests package
java -jar target/memtsdb-1.0.0-SNAPSHOT.jar
```

- API base URL: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

Override WAL path (defaults to `./data/wal/metrics.wal`):

```bash
export MEMTSDB_WAL_PATH=/tmp/metrics.wal
java -jar target/memtsdb-1.0.0-SNAPSHOT.jar
```

(`application.properties` maps `MEMTSDB_WAL_PATH` → `memtsdb.wal.path` via relaxed binding.)

### WAL replay recovery

On startup, `WALReplayService` reads the append-only **`metrics.wal`** file (newline-delimited JSON, same shape as POST `/api/v1/metrics`), deserializes each line with Jackson, and calls the same memory path as live ingestion (`RecordAppender` → intern dictionaries + `TimeSeriesStore`). The WAL writer thread starts **after** replay (`WALService` uses `@DependsOn("walReplayService")`) so the file is not appended concurrently during recovery.

- **Crash recovery:** Anything flushed to disk in a prior run is replayed; whatever was only in RAM or still in the async WAL queue may be lost on kill/power loss.
- **Durability:** There is no per-record `fsync`; durability matches buffered WAL + OS flush behavior (see class Javadoc on `WALReplayService` / `WALService`).
- **Append-only:** New commits extend the tail of `metrics.wal`; recovery replays from the beginning (redo log). Disable replay with `MEMTSDB_WAL_REPLAY_ENABLED=false` when needed.

## Docker

```bash
docker build -t memtsdb:local .
docker run --rm -p 8080:8080 -e PORT=8080 -v memtsdb-wal:/data/wal memtsdb:local
```

The root **`Dockerfile`** is production-oriented (multi-stage build, non-root user, JVM defaults, health check). See **`docs/deployment-render.md`** for Render.com, **`deploy/env.example`** for environment variables, and **`render.yaml`** for a Blueprint skeleton.

---

## Example API payloads

### Ingest (POST `/api/v1/metrics`)

```http
POST /api/v1/metrics HTTP/1.1
Content-Type: application/json

{
  "metric": "cpu_usage",
  "timestamp": 1710000000,
  "value": 75.5,
  "tags": {
    "host": "server-1",
    "region": "us-east"
  }
}
```

### Query (GET `/api/v1/query`)

**Request**

```http
GET /api/v1/query?metric=cpu_usage&from=1710000000&to=1710003600&tag=host:server-1&page=0&size=50 HTTP/1.1
```

- `from` / `to`: epoch **seconds**, **inclusive** (time-range scan on a `ConcurrentSkipListMap` per metric).
- Repeat `tag=key:value` for **AND** equality filters on interned tag keys/values.
- `page` is zero-based; `size` is capped server-side (max 5000).

**Response** (`PagedQueryResponse`: `meta` + `page` + `items`)

```json
{
  "meta": {
    "metric": "cpu_usage",
    "fromInclusive": 1710000000,
    "toInclusive": 1710003600,
    "tagFiltersApplied": { "host": "server-1" },
    "pointsExaminedInTimeRange": 1280,
    "pointsMatchedTagPredicate": 42,
    "rangeScanStructureNote": "ConcurrentSkipListMap keyed by epoch-second timestamp; subMap(from,to) for ordered range iteration"
  },
  "page": {
    "totalMatched": 42,
    "pageIndex": 0,
    "pageSize": 50,
    "totalPages": 1,
    "hasNextPage": false,
    "hasPreviousPage": false
  },
  "items": [
    {
      "metric": "cpu_usage",
      "timestamp": 1710000001,
      "value": 75.5,
      "tags": { "host": "server-1", "region": "us-east" }
    }
  ]
}
```

`pointsExaminedInTimeRange` counts raw points visited in the time window; `pointsMatchedTagPredicate` counts those passing all tag filters (equals `page.totalMatched`).

### Health (GET `/api/v1/health`)

```http
GET /api/v1/health HTTP/1.1
```

## Benchmark (JMeter load test)

Load-testing assets live under **`benchmark/`**. The plan drives **concurrent HTTP POST** ingestion of JSON datapoints (same shape as `POST /api/v1/metrics`), targets **5 million samples** by default (`threads × loops`), and produces an **HTML dashboard** with throughput and **latency percentiles** (including p90 / p95 / p99).

### Prerequisites

- MemTSDB running locally on port **8080** (or override `HOST` / `PORT`).
- [Apache JMeter](https://jmeter.apache.org/download_jmeter.cgi) **5.x** on `PATH` as `jmeter`, or set **`JMETER_HOME`** to the install directory.

Install examples:

```bash
brew install jmeter
# or unpack binary distribution and export JMETER_HOME=/path/to/apache-jmeter-5.x
```

### Quick smoke test (~1,000 samples)

```bash
SMOKE=1 ./benchmark/run-load-test.sh
```

### Full run — 5 million datapoints

Defaults: **`THREADS=100`**, **`TARGET_SAMPLES=5000000`** → **50,000 loops** per thread. Ramp-up **30s**.

```bash
# From repo root; MemTSDB must already be listening
./benchmark/run-load-test.sh
```

A full run appends **~5M JSON lines** to `metrics.wal` (one line per accepted sample). Ensure sufficient disk space, or point **`MEMTSDB_WAL_PATH`** at tmpfs for an ingestion-only throughput experiment.

Outputs a timestamped directory under `benchmark/results/run-*/` with:

- **`results.jtl`** — raw samples (latencies, status codes).
- **`html-report/index.html`** — open in a browser for **Throughput**, **Response Times Over Time**, **Response Time Percentiles**, and errors.

Customize:

```bash
THREADS=125 TARGET_SAMPLES=5000000 RAMP_SEC=60 HOST=127.0.0.1 PORT=8080 ./benchmark/run-load-test.sh
```

`TARGET_SAMPLES` **must be divisible** by `THREADS` (the script sets `LOOPS = TARGET_SAMPLES / THREADS`).

### Throughput expectations (order-of-magnitude)

These are **not SLAs** — use them to sanity-check that your run is in a plausible range before tuning.

| Environment | Rough ingest throughput |
|-------------|-------------------------|
| Laptop / dev machine (localhost, HTTP + JVM + async WAL to SSD) | Often **~5k–40k requests/s** depending on cores, GC, and WAL flush behaviour |
| Same with WAL on **tmpfs** / ramdisk (isolate RAM + CPU effects) | Usually **higher** — disk append stops being the bottleneck |
| CI / small VM | Often **lower** — noisy neighbours and fewer cores |

Factors that dominate MemTSDB ingest throughput:

- **WAL:** Append-heavy workload; slow disks or `flush()` cadence cap sustained ops/sec.
- **Concurrency:** More threads help until you saturate CPU, GC, or the WAL queue.
- **Payload:** Small JSON bodies (as in the JMeter plan) maximize ops/sec vs heavy tagging.

If throughput collapses while latency spikes, check **`benchmark/results/.../jmeter.log`**, error rate in the HTML report, and JVM heap (`JAVA_TOOL_OPTIONS=-Xmx4g` for long runs).

### Package layout

See `com.tsdb.memtsdb` — `api`, `config`, `dictionary`, `model`, `persistence`, `query`, `service`, `storage`.

Benchmark layout: `benchmark/jmeter/memtsdb-ingest.jmx`, `benchmark/run-load-test.sh`, `benchmark/results/` (gitignored).

### Architecture evolution (interview notes)

For how this prototype could grow toward **sharding, compression, Kafka, replication, retention, rollups, columnar storage**, see **`docs/production-tsdb-evolution.md`**.
