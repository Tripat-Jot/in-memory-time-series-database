# Evolving MemTSDB Toward Production-Grade TSDB Architecture

MemTSDB today is a **single JVM**, **heap-resident** store with **ordered maps per metric**, **interned tags**, an **async WAL**, and **HTTP ingestion**. Moving to production scale means separating concerns that are fused in the prototype: durability, query latency at billions of points, multi-tenant isolation, and operational lifecycle (retention, compaction, replication).

This document maps classic TSDB building blocks to how you would layer them **on top of** or **instead of** the current design — suitable for architecture reviews and senior backend interviews.

---

## 1. Sharding

**Problem:** One `ConcurrentHashMap<metricId, …>` eventually exceeds RAM, GC time, and recovery blast radius.

**Approach:**

- **Shard key:** Typically `(tenant?, metric_name)` hashed to a **shard id**, or **time-based** shards (day/hour buckets) combined with metric hashing.
- **Routing:** A **router/gateway** (or smart client) maps `(metric, timestamp)` → shard. Queries spanning shards **fan out** and **merge-sort** by time (Scatter-Gather).
- **Assignment:** Static hash rings (consistent hashing) let you **add nodes** with bounded data movement.

**Relation to MemTSDB:** Replace the single `TimeSeriesStore` with **N independent stores** (processes or pods), each owning a shard range. WAL becomes **per shard**; replay stays the same pattern locally.

---

## 2. Compression

**Problem:** Raw `double` timestamps + values + tag IDs still dominate bytes at scale.

**Approach:**

- **Time:** Delta-of-delta or XOR encoding on timestamps within a block (Gorilla-style family).
- **Values:** Float/double XOR, scaled integers, or model-specific codecs (zstd on blocks).
- **Tags:** Dictionary encoding is already a form of compression; add **sorted tag columns + RLE** per block.

**Relation to MemTSDB:** Stop storing loose `DataPoint` lists; batch into **immutable compressed segments** (files or off-heap buffers) written by a background flusher. Query reads **decompress only needed ranges**.

---

## 3. Bloom filters

**Problem:** On disk, finding whether a **metric + tag set** exists in a segment without scanning everything.

**Approach:**

- Per **segment file** (or time partition), maintain a **Bloom filter** over composite keys (e.g. hashed series key = metric + sorted tags).
- Queries negative-filter: “this series definitely not in this segment” → **skip IO**.
- Pair with **min/max timestamp** per segment (zonemap) for time pruning.

**Relation to MemTSDB:** In-memory you rarely need Bloom filters (maps are explicit). For **columnar segments on disk**, Bloom + statistics are standard to avoid cold reads.

---

## 4. Retention policies

**Problem:** Infinite append fills disks and violates compliance (delete-after-N-days).

**Approach:**

- **Tiered retention:** Raw high-resolution for **short TTL**, aggregated tiers for longer (ties into rollups).
- **Implementation:** Per shard/partition, **drop whole files** or truncate **time partitions** older than cutoff (cheap vs row deletes).
- **Metadata catalog:** Track segment time ranges so deletes are **metadata + unlink**, not table scans.

**Relation to MemTSDB:** Today everything lives in heap — retention means **eviction policies** + WAL rotation + optional snapshots. Production moves toward **partitioned storage** where TTL is a **scheduler job**.

---

## 5. Rollups / downsampling

**Problem:** Clients rarely need sub-second resolution for last year; storing raw forever is wasteful.

**Approach:**

- **Continuous / scheduled jobs:** For each `(metric, tags)`, compute **avg/min/max/count/sum** over windows (1m, 5m, 1h, 1d) into **derived series** or **separate measurement names**.
- **Idempotent rules:** Window boundaries aligned to UTC; late data triggers **recomputation** or **partial updates** depending on consistency model.
- **Query path:** Prefer reading **pre-aggregated** tiers when `step` in query is coarse.

**Relation to MemTSDB:** Add an **async compaction/rollup worker** that reads WAL or segments and writes summary segments — analogous to **materialized views** in relational systems.

---

## 6. Columnar storage

**Problem:** Row-oriented `List<DataPoint>` is cache-unfriendly for analytics (“all CPU across all hosts for 24h”).

**Approach:**

- **Column layout:** Separate arrays for `timestamp[]`, `value[]`, `tag_columns…` per block — enables **vectorized scan**, SIMD-friendly loops, and **better compression** (same-type runs).
- **Formats:** Apache Parquet/ORC on object storage for cold tiers; custom TSDB segment formats for hot paths.

**Relation to MemTSDB:** The current structure is **row-ish per timestamp bucket**. Evolution: flush buckets to **columnar blocks** on disk/off-heap; keep a **small hot mutable layer** in RAM (memtable) + **immutable frozen segments**.

---

## 7. Replication

**Problem:** Single node + single WAL → single fault domain.

**Approach:**

- **Leader-follower:** Append pipeline replicated WAL or segment files; followers serve **read replicas** with lag bounded by replication.
- **Quorum (stronger):** Raft/Paxos on a **metadata/control plane** + replicated log for ingest assignments (Kafka can play this role — see below).
- **Multi-region:** Async replication + **last-writer-wins** or **vector clocks** only where conflicts exist — most TSDBs avoid conflicting writes per series.

**Relation to MemTSDB:** Externalize the WAL to **durable replicated log** or replicate **segment objects** to object storage with versioning.

---

## 8. Kafka ingestion

**Problem:** HTTP ingest doesn’t scale for millions of agents; need **backpressure**, **replay**, and **fan-out** to multiple consumers.

**Approach:**

- **Producers:** Agents emit to **Kafka topics** partitioned by `(tenant, metric)` or hash(series key).
- **Consumers:** TSDB **ingest workers** pull batches, **idempotently** apply (use offsets + segment ids), write **memtable → segments**.
- **Benefits:** Natural **replay** after bugs, **elastic consumer groups**, **multi-subscriber** (alerting + TSDB from same stream).

**Relation to MemTSDB:** Replace or augment `POST /metrics` with **Kafka → RecordAppender** pipeline; HTTP remains for ad hoc tools. WAL can shrink to **local buffer** because Kafka is the **system of record**, or you keep WAL as **exactly-once** bridge — architecture choice.

---

## Suggested phased roadmap

| Phase | Focus | Outcome |
|-------|--------|---------|
| **1** | Persisted segments + retention + compression | Stop losing history on restart without holding everything in heap |
| **2** | Kafka ingest + horizontal ingest workers | Scale writers; decouple agents from DB uptime |
| **3** | Sharding + query scatter/gather | Scale past one machine |
| **4** | Rollups + columnar cold storage | Affordable long-range analytics |
| **5** | Replication + multi-AZ | HA and read scaling |

---

## Mental model

Think of MemTSDB as the **hot mutable layer** (memtable) in a classic LSM-style TSDB. Production adds **immutable compressed segments**, **catalog metadata**, **distributed routing**, **stream ingestion**, and **policy-driven lifecycle** — not a bigger HashMap.
