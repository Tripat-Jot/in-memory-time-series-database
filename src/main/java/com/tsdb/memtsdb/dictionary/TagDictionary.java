package com.tsdb.memtsdb.dictionary;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Two-namespace interning: tag keys and tag values each get their own ID space.
 * <p>
 * Storing {@code Map<Integer, Integer>} on {@link com.tsdb.memtsdb.model.DataPoint} references these namespaces:
 * left int is always a key-ID, right int is always a value-ID. This avoids confusing a host string ID with a region
 * string ID even if numeric collisions occurred — separating spaces keeps semantics crisp.
 * </p>
 *
 * <h2>Flyweight recap</h2>
 * Canonical strings live here once; {@link com.tsdb.memtsdb.model.DataPoint} instances only carry ints — comparable to
 * string.Intern for metrics systems, but explicit and controllable (JVM string intern is global and not ideal for
 * unbounded cardinality labels).
 *
 * <p><b>Tradeoff:</b> intern tables grow with cardinality of distinct tags — production systems enforce cardinality
 * limits per metric for this reason.</p>
 */
@Component
public class TagDictionary {

    private final AtomicInteger nextKeyId = new AtomicInteger(1);
    private final AtomicInteger nextValueId = new AtomicInteger(1);

    private final ConcurrentHashMap<String, Integer> keyToId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> valueToId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> idToKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> idToValue = new ConcurrentHashMap<>();

    public int internKey(String key) {
        return keyToId.computeIfAbsent(key, k -> {
            int id = nextKeyId.getAndIncrement();
            idToKey.put(id, k);
            return id;
        });
    }

    public int internValue(String value) {
        return valueToId.computeIfAbsent(value, v -> {
            int id = nextValueId.getAndIncrement();
            idToValue.put(id, v);
            return id;
        });
    }

    public String resolveKey(int keyId) {
        String k = idToKey.get(keyId);
        if (k == null) {
            throw new IllegalArgumentException("Unknown tag key id: " + keyId);
        }
        return k;
    }

    public String resolveValue(int valueId) {
        String v = idToValue.get(valueId);
        if (v == null) {
            throw new IllegalArgumentException("Unknown tag value id: " + valueId);
        }
        return v;
    }

    /**
     * Lookup without inserting — required for query filtering so we do not pollute dictionaries with typos.
     */
    public Integer findKeyId(String key) {
        return keyToId.get(key);
    }

    /**
     * Lookup without inserting for filter values.
     */
    public Integer findValueId(String value) {
        return valueToId.get(value);
    }
}
