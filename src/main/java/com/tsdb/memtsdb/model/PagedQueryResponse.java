package com.tsdb.memtsdb.model;

import java.util.List;

/**
 * Advanced query envelope: execution metadata + pagination + datapoint projections.
 * <p>
 * Keeps HTTP JSON stable and documented while allowing {@link QueryExecutionMeta} to evolve
 * (e.g. tracing ids, rewrite notes) without breaking {@link MetricPointResponse} items.
 * </p>
 *
 * @param meta  what was scanned and how (see field docs on {@link QueryExecutionMeta})
 * @param page  pagination slice over the tag-filtered stream (ordered by timestamp, then stable bucket order)
 * @param items datapoints for this page only (resolved tag strings for API ergonomics)
 */
public record PagedQueryResponse(
        QueryExecutionMeta meta,
        PageInfo page,
        List<MetricPointResponse> items
) {
}
