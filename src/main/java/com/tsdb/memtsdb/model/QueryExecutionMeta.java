package com.tsdb.memtsdb.model;

import java.util.Map;

/**
 * Describes what the server evaluated for this query (auditability + interview/debug clarity).
 * <p>
 * Separate from {@link PageInfo} so pagination stays reusable across future endpoints (e.g. cardinality queries).
 * </p>
 *
 * @param metric                     metric name as requested by the client
 * @param fromInclusive              start of time window (epoch seconds, inclusive)
 * @param toInclusive                end of time window (epoch seconds, inclusive)
 * @param tagFiltersApplied          equality filters after parsing (empty = no tag predicate)
 * @param pointsExaminedInTimeRange   raw datapoints visited while scanning the time window (before tag filter)
 * @param pointsMatchedTagPredicate   datapoints passing all tag equality predicates (equals {@link PageInfo#totalMatched})
 * @param rangeScanStructureNote      documents index choice for operators reading logs/Swagger
 */
public record QueryExecutionMeta(
        String metric,
        long fromInclusive,
        long toInclusive,
        Map<String, String> tagFiltersApplied,
        long pointsExaminedInTimeRange,
        long pointsMatchedTagPredicate,
        String rangeScanStructureNote
) {
}
