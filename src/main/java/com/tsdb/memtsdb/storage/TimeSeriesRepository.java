package com.tsdb.memtsdb.storage;

import com.tsdb.memtsdb.model.DataPoint;

import java.util.List;
import java.util.function.Consumer;

/**
 * Repository abstraction (Repository pattern) over the in-memory series store.
 * <p>
 * This mirrors EF Core's {@code DbSet<T>} / repository interfaces in .NET: controllers and facades depend on the
 * abstraction, enabling tests to substitute fakes and keeping persistence rules centralized.
 * </p>
 */
public interface TimeSeriesRepository {

    void append(DataPoint point);

    /**
     * Ordered scan of all points for {@code metricId} with timestamps in the inclusive range.
     * Ordering follows {@link java.util.concurrent.ConcurrentSkipListMap} key order (timestamp ascending).
     * <p>
     * Prefer {@link #forEachInTimeOrder(int, long, long, Consumer)} for large windows to avoid materializing
     * a full in-memory list of every point in the range.
     * </p>
     */
    List<DataPoint> rangeScan(int metricId, long startInclusive, long endInclusive);

    /**
     * Visits every {@link DataPoint} in {@code [startInclusive, endInclusive]} ordered by {@code timestamp}
     * ascending, then stable iteration order within a timestamp bucket. Invoked for query engines that need
     * streaming scans (pagination, single-pass aggregations) without an intermediate {@link java.util.ArrayList}
     * holding the entire time window.
     */
    void forEachInTimeOrder(int metricId, long startInclusive, long endInclusive, Consumer<DataPoint> consumer);
}
