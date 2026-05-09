package com.tsdb.memtsdb.model;

import java.util.Map;

/**
 * Query API projection with human-readable tags (resolved from interned IDs).
 * Separate from {@link DataPoint} so internal storage stays compact while REST stays ergonomic.
 */
public record MetricPointResponse(
        String metric,
        long timestamp,
        double value,
        Map<String, String> tags
) {
}
