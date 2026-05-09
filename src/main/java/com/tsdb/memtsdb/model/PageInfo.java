package com.tsdb.memtsdb.model;

/**
 * Cursor-free offset pagination metadata (common REST shape).
 *
 * @param totalMatched    rows matching metric + time range + tag filters (full result set size)
 * @param pageIndex       zero-based page index
 * @param pageSize        max items per page (server-capped)
 * @param totalPages      ceil(totalMatched / pageSize), or 0 when totalMatched is 0
 * @param hasNextPage     whether another page exists after this one
 * @param hasPreviousPage whether a previous page exists
 */
public record PageInfo(
        long totalMatched,
        int pageIndex,
        int pageSize,
        int totalPages,
        boolean hasNextPage,
        boolean hasPreviousPage
) {
}
