package com.tsdb.memtsdb.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable value object representing one measurement (Immutable Object pattern).
 * <p>
 * .NET analogy: similar to a C# {@code record} with {@code init}-only properties and defensive copies,
 * except Java uses a builder-less constructor pattern with {@code Collections.unmodifiableMap}.
 * Immutability guarantees safe publication across threads without locks on the object itself
 * (points can be read concurrently once constructed).
 * </p>
 *
 * <h2>Tag representation: Flyweight / interning</h2>
 * Instead of storing repeated strings like {@code "server-1"} on every point, we store compact integers that index into
 * {@link com.tsdb.memtsdb.dictionary.TagDictionary}. This is the Flyweight pattern: share immutable intrinsic state
 * (the canonical string lives once in the dictionary), extrinsic per-point state is just IDs.
 *
 * <p><b>Memory savings:</b> JVM strings are objects with headers + char arrays; deduping tags removes repeated char[]
 * allocations and reduces GC churn under bursty ingestion.</p>
 *
 * <p><b>JVM heap optimization:</b> smaller objects improve cache locality and Young GC efficiency; integer keys/values
 * are cheaper than {@code String} references spread across the heap.</p>
 */
public final class DataPoint {

    private final int metricId;
    private final long timestamp;
    private final double value;
    /**
     * Tag map at ingestion time: tagKeyId -> tagValueId (both interned integers).
     * Unmodifiable view — callers cannot mutate internal state after construction.
     */
    private final Map<Integer, Integer> tagKeyToValueId;

    /**
     * @param metricId      interned metric identifier (see MetricDictionary)
     * @param timestamp     epoch seconds (project payload uses integer epoch seconds like Influx-style samples)
     * @param value         measurement value
     * @param tagKeyToValue defensive copy is performed; stored map is unmodifiable
     */
    public DataPoint(int metricId, long timestamp, double value, Map<Integer, Integer> tagKeyToValueId) {
        this.metricId = metricId;
        this.timestamp = timestamp;
        this.value = value;
        Objects.requireNonNull(tagKeyToValueId, "tagKeyToValueId");
        // Copy-on-insert guards against external mutation of the caller's Map after construction.
        this.tagKeyToValueId = Collections.unmodifiableMap(new HashMap<>(tagKeyToValueId));
    }

    public int metricId() {
        return metricId;
    }

    public long timestamp() {
        return timestamp;
    }

    public double value() {
        return value;
    }

    public Map<Integer, Integer> tagKeyToValueId() {
        return tagKeyToValueId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DataPoint other)) {
            return false;
        }
        return metricId == other.metricId
                && timestamp == other.timestamp
                && Double.compare(other.value, value) == 0
                && tagKeyToValueId.equals(other.tagKeyToValueId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(metricId, timestamp, value, tagKeyToValueId);
    }

    @Override
    public String toString() {
        return "DataPoint{metricId=%d, timestamp=%d, value=%s, tags=%s}"
                .formatted(metricId, timestamp, Double.toString(value), tagKeyToValueId);
    }
}
