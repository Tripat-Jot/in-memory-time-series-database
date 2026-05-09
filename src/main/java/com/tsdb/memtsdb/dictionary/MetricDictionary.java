package com.tsdb.memtsdb.dictionary;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Intern table for metric names → dense integer IDs.
 * <p>
 * <b>Why ConcurrentHashMap:</b> ingestion threads resolve metric strings concurrently; CHM gives lock-striped
 * reads/writes with good throughput under contention (similar spirit to ConcurrentDictionary in .NET, but Java's map
 * does not preserve enumeration thread-safety unless you snapshot — we only need single-key get/putIfAbsent).
 * </p>
 *
 * <p><b>Complexity:</b> average O(1) lookup/insert for interning; memory proportional to distinct metric names.</p>
 *
 * <p><b>Concurrency benefit:</b> {@code computeIfAbsent} is atomic for the mapping operation — avoids classic
 * “check-then-act” races when two threads intern the same new metric simultaneously.</p>
 */
@Component
public class MetricDictionary {

    /**
     * Monotonic ID allocator (starts at 1; 0 reserved as sentinel if needed elsewhere).
     * AtomicInteger is a lock-free counter suitable for hot ingestion paths.
     */
    private final AtomicInteger nextId = new AtomicInteger(1);

    private final ConcurrentHashMap<String, Integer> nameToId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> idToName = new ConcurrentHashMap<>();

    /**
     * Returns stable integer ID for the given metric name, allocating if absent.
     */
    public int internMetric(String metricName) {
        return nameToId.computeIfAbsent(metricName, key -> {
            int id = nextId.getAndIncrement();
            idToName.put(id, key);
            return id;
        });
    }

    public String resolveMetric(int metricId) {
        String name = idToName.get(metricId);
        if (name == null) {
            throw new IllegalArgumentException("Unknown metric id: " + metricId);
        }
        return name;
    }

    public Integer findIdByName(String metricName) {
        return nameToId.get(metricName);
    }
}
