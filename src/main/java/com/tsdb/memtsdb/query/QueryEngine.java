package com.tsdb.memtsdb.query;

import com.tsdb.memtsdb.dictionary.MetricDictionary;
import com.tsdb.memtsdb.dictionary.TagDictionary;
import com.tsdb.memtsdb.model.DataPoint;
import com.tsdb.memtsdb.model.MetricPointResponse;
import com.tsdb.memtsdb.model.MetricQueryParams;
import com.tsdb.memtsdb.model.PageInfo;
import com.tsdb.memtsdb.model.PagedQueryResponse;
import com.tsdb.memtsdb.model.QueryExecutionMeta;
import com.tsdb.memtsdb.storage.TimeSeriesRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Advanced query orchestration: <b>time-range scan</b> + <b>tag equality filters</b> + <b>offset pagination</b>.
 * <p>
 * Execution uses {@link TimeSeriesRepository#forEachInTimeOrder(int, long, long, java.util.function.Consumer)} so we
 * <b>do not</b> allocate a giant {@link java.util.List} holding every point in the window before pagination — peak heap
 * scales with <i>page size</i> for returned DTOs plus O(1) counters, not O(P) for P points in range (see memory notes below).
 * </p>
 *
 * <h2>Query complexity</h2>
 * Let P = number of datapoints in {@code [from, to]} for the metric, F = number of tag predicates.
 * <ul>
 *   <li><b>Time window navigation:</b> {@code ConcurrentSkipListMap#subMap} + iteration costs O(log T + P) — logarithmic
 *       anchor to the window, then linear in points inside the window (unavoidable without extra indexes).</li>
 *   <li><b>Tag filtering:</b> each candidate point costs O(F) map lookups on interned IDs (constants if F is tiny).</li>
 *   <li><b>Pagination:</b> single pass over the filtered logical stream — O(P · F) overall for this implementation class.</li>
 * </ul>
 * Production TSDBs add inverted tag indexes or bitmap posting lists so filtering is not linear in full series cardinality.
 *
 * <h2>Memory implications</h2>
 * <ul>
 *   <li><b>Streaming scan:</b> only the current page’s {@link MetricPointResponse} objects are retained (plus tag strings
 *       resolved from dictionaries — Flyweight tables amortize repeated strings).</li>
 *   <li><b>No full intermediate list</b> of all matching points — avoiding “fetch everything then slice” spikes under
 *       wide time ranges.</li>
 *   <li><b>Still O(P) CPU</b> to compute exact {@link PageInfo#totalMatched()}: we must observe every match in range for
 *       precise totals unless we introduce probabilistic sketches or secondary indexes.</li>
 * </ul>
 *
 * <h2>Why ordered timestamps matter</h2>
 * TSDB queries are dominated by “what happened between T₁ and T₂?”. Keeping storage time-ordered lets the engine stop
 * considering irrelevant buckets quickly and stream results in merge-friendly order for downsampling and joins.
 */
@Component
public class QueryEngine {

    private static final int MAX_PAGE_SIZE = 5_000;

    /**
     * Documents the on-disk / in-memory index strategy for operators (maps to {@link com.tsdb.memtsdb.storage.TimeSeriesStore}).
     */
    public static final String RANGE_SCAN_NOTE =
            "ConcurrentSkipListMap keyed by epoch-second timestamp; subMap(from,to) for ordered range iteration";

    private final TimeSeriesRepository repository;
    private final MetricDictionary metricDictionary;
    private final TagDictionary tagDictionary;

    public QueryEngine(
            TimeSeriesRepository repository,
            MetricDictionary metricDictionary,
            TagDictionary tagDictionary) {
        this.repository = Objects.requireNonNull(repository);
        this.metricDictionary = Objects.requireNonNull(metricDictionary);
        this.tagDictionary = Objects.requireNonNull(tagDictionary);
    }

    /**
     * Executes query from validated {@link MetricQueryParams}.
     */
    public PagedQueryResponse query(MetricQueryParams params) {
        Objects.requireNonNull(params);

        if (params.fromInclusive() > params.toInclusive()) {
            throw new IllegalArgumentException("from must be <= to (epoch seconds)");
        }
        int page = params.pageIndex();
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        int safeSize = Math.min(Math.max(params.pageSize(), 1), MAX_PAGE_SIZE);

        String metricName = params.metric();
        Map<String, String> tagFilters = params.tagFilters();

        Integer metricId = metricDictionary.findIdByName(metricName);
        if (metricId == null) {
            return emptyResponse(params, safeSize, tagFilters);
        }

        long skip = (long) page * safeSize;
        AtomicLong examined = new AtomicLong();
        AtomicLong matchedIndex = new AtomicLong();

        List<MetricPointResponse> pageItems = new ArrayList<>();

        repository.forEachInTimeOrder(metricId, params.fromInclusive(), params.toInclusive(), dp -> {
            examined.incrementAndGet();
            if (!matchesTags(dp, tagFilters)) {
                return;
            }
            long idx = matchedIndex.getAndIncrement();
            if (idx >= skip && idx < skip + safeSize) {
                pageItems.add(toResponse(dp, metricName));
            }
        });

        long totalMatched = matchedIndex.get();

        var meta = new QueryExecutionMeta(
                metricName,
                params.fromInclusive(),
                params.toInclusive(),
                Map.copyOf(tagFilters),
                examined.get(),
                totalMatched,
                RANGE_SCAN_NOTE
        );

        int totalPages = totalMatched == 0 ? 0 : (int) Math.ceil((double) totalMatched / safeSize);
        boolean hasNext = skip + safeSize < totalMatched;
        boolean hasPrev = page > 0;

        var pageInfo = new PageInfo(totalMatched, page, safeSize, totalPages, hasNext, hasPrev);

        return new PagedQueryResponse(meta, pageInfo, List.copyOf(pageItems));
    }

    private PagedQueryResponse emptyResponse(MetricQueryParams params, int safeSize, Map<String, String> tagFilters) {
        var meta = new QueryExecutionMeta(
                params.metric(),
                params.fromInclusive(),
                params.toInclusive(),
                Map.copyOf(tagFilters),
                0,
                0,
                RANGE_SCAN_NOTE
        );
        var pageInfo = new PageInfo(0, params.pageIndex(), safeSize, 0, false, false);
        return new PagedQueryResponse(meta, pageInfo, List.of());
    }

    /**
     * Equality semantics on interned IDs — filters must reference dictionary-known keys/values (query path uses
     * {@link TagDictionary#findKeyId(String)} so typos yield zero matches rather than accidental intern growth).
     */
    private boolean matchesTags(DataPoint dp, Map<String, String> tagFilters) {
        if (tagFilters == null || tagFilters.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, String> f : tagFilters.entrySet()) {
            Integer keyId = tagDictionary.findKeyId(f.getKey());
            Integer wantValId = tagDictionary.findValueId(f.getValue());
            if (keyId == null || wantValId == null) {
                return false;
            }
            Integer actual = dp.tagKeyToValueId().get(keyId);
            if (actual == null || !actual.equals(wantValId)) {
                return false;
            }
        }
        return true;
    }

    private MetricPointResponse toResponse(DataPoint dp, String metricName) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> e : dp.tagKeyToValueId().entrySet()) {
            String k = tagDictionary.resolveKey(e.getKey());
            String v = tagDictionary.resolveValue(e.getValue());
            tags.put(k, v);
        }
        return new MetricPointResponse(metricName, dp.timestamp(), dp.value(), Map.copyOf(tags));
    }
}
