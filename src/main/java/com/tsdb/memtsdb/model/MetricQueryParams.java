package com.tsdb.memtsdb.model;

import java.util.Map;
import java.util.Objects;

/**
 * Bound parameters for GET {@code /api/v1/query} after controller parsing.
 * <p>
 * {@code tag} query strings are normalized into {@code tagFilters} before the engine runs.
 * Immutable snapshot via {@link Map#copyOf} for safe hand-off across threads if ever dispatched async.
 * </p>
 */
public record MetricQueryParams(
        String metric,
        long fromInclusive,
        long toInclusive,
        Map<String, String> tagFilters,
        int pageIndex,
        int pageSize
) {
    public MetricQueryParams {
        Objects.requireNonNull(metric, "metric");
        tagFilters = tagFilters != null ? Map.copyOf(tagFilters) : Map.of();
    }
}
