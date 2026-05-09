package com.tsdb.memtsdb.storage;

import com.tsdb.memtsdb.model.DataPoint;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Consumer;

/**
 * Primary in-memory series storage required by the assignment.
 * <p>
 * Structure:
 * {@code ConcurrentHashMap<metricId, ConcurrentSkipListMap<timestamp, List<DataPoint>>>}
 *
 * <ul>
 *   <li><b>ConcurrentHashMap (outer):</b> shards series by metric ID so unrelated metrics mutate disjoint map stripes,
 *       reducing contention versus one giant map keyed by composite tuples.</li>
 *   <li><b>ConcurrentSkipListMap (inner):</b> maintains timestamps sorted for cheap range scans ({@code subMap}).
 *       Complexity: O(log N) insert/navigate per timestamp bucket; similar role as SortedDictionary in .NET but concurrent.</li>
 *   <li><b>List&lt;DataPoint&gt; per timestamp:</b> multiple samples may share the same timestamp with different tag sets
 *       (or duplicates); list holds the cohort. Wrapped with {@code Collections.synchronizedList} plus synchronized blocks
 *       on the same list during iteration to avoid ConcurrentModificationException.</li>
 * </ul>
 *
 * <h2>Why {@link ConcurrentSkipListMap} is ideal for time-range scans</h2>
 * <ul>
 *   <li><b>Log-time access to the window.</b> You can {@code subMap(from, true, to, true)} in O(log T) to obtain a
 *       <i>view</i> of the key subrange without copying all keys (unlike sorting a list for every query).</li>
 *   <li><b>Ordered traversal = streaming friendly.</b> Iterating {@code subMap} is ordered by timestamp, which matches
 *       how operators, aggregators, and paginators want to read TSDB data (time windows, merge-joins, LSM-style compactions).</li>
 *   <li><b>Concurrent readers + writers.</b> {@code TreeMap} would need external locking for cross-thread writes;
 *       {@code ConcurrentSkipListMap} provides lock-free or fine-grained synchronization suitable for ingestion races.</li>
 *   <li><b>Tradeoff vs arrays:</b> higher constant factors than a dense array index, but arrays cannot cheaply insert
 *       out-of-order timestamps at scale; skip lists + maps mirror production TSDB chunk layouts.</li>
 * </ul>
 *
 * <h2>Memory implications</h2>
 * {@link #rangeScan(int, long, long)} materializes every {@link DataPoint} reference into a new {@link List} — useful for
 * tests/small windows but O(P) extra heap for P points in range. {@link #forEachInTimeOrder(int, long, long, Consumer)}
 * avoids that allocation by applying an action per point while iterating buckets.
 *
 * <h2>Concurrency model (JVM)</h2>
 * Writer threads (HTTP handlers) append concurrently; readers scan ranges. SkipList + CHM are thread-safe structures,
 * but synchronized lists require external locking for compound read/write consistency per bucket.
 *
 * <h2>Scalability notes</h2>
 * Vertical scaling only — all state is heap-local. Horizontal scaling would shard metrics by consistent hashing,
 * replicate WAL streams, and merge query fan-outs (typical TSDB architecture). This class intentionally stays single-node.
 *
 * <h2>Time complexity</h2>
 * Append: O(log T) for T distinct timestamps per metric (skip-list insertion) + O(1) list append amortized.
 * Range scan / forEach: O(log T + P) where P is the number of datapoints whose timestamps fall in the window
 * (must touch each point once). Tag filtering happens above this layer but still scales with P for selective predicates
 * unless inverted indexes exist.
 */
@Repository
public class TimeSeriesStore implements TimeSeriesRepository {

    /**
     * Main store: metric id → time-ordered buckets.
     * ConcurrentSkipListMap chosen over TreeMap for thread-safe mutations without external global lock.
     */
    private final ConcurrentHashMap<Integer, ConcurrentSkipListMap<Long, List<DataPoint>>> store =
            new ConcurrentHashMap<>();

    @Override
    public void append(DataPoint point) {
        ConcurrentSkipListMap<Long, List<DataPoint>> series =
                store.computeIfAbsent(point.metricId(), id -> new ConcurrentSkipListMap<>());

        List<DataPoint> bucket = series.computeIfAbsent(point.timestamp(),
                ts -> Collections.synchronizedList(new ArrayList<>()));

        synchronized (bucket) {
            bucket.add(point);
        }
    }

    @Override
    public List<DataPoint> rangeScan(int metricId, long startInclusive, long endInclusive) {
        ConcurrentSkipListMap<Long, List<DataPoint>> series = store.get(metricId);
        if (series == null || series.isEmpty()) {
            return List.of();
        }

        var sub = series.subMap(startInclusive, true, endInclusive, true);
        List<DataPoint> out = new ArrayList<>();
        for (List<DataPoint> bucket : sub.values()) {
            synchronized (bucket) {
                out.addAll(bucket);
            }
        }
        return out;
    }

    @Override
    public void forEachInTimeOrder(int metricId, long startInclusive, long endInclusive, Consumer<DataPoint> consumer) {
        ConcurrentSkipListMap<Long, List<DataPoint>> series = store.get(metricId);
        if (series == null || series.isEmpty()) {
            return;
        }
        var sub = series.subMap(startInclusive, true, endInclusive, true);
        for (List<DataPoint> bucket : sub.values()) {
            synchronized (bucket) {
                for (DataPoint dp : bucket) {
                    consumer.accept(dp);
                }
            }
        }
    }
}
