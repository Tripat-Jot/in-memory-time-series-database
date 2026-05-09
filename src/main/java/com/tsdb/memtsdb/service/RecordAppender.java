package com.tsdb.memtsdb.service;

import com.tsdb.memtsdb.dictionary.MetricDictionary;
import com.tsdb.memtsdb.dictionary.TagDictionary;
import com.tsdb.memtsdb.model.DataPoint;
import com.tsdb.memtsdb.model.MetricRequest;
import com.tsdb.memtsdb.storage.TimeSeriesRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Shared ingestion path into {@link TimeSeriesRepository} (interning + immutable {@link DataPoint}).
 * <p>
 * Extracted so WAL replay can rebuild indexes without depending on {@link IngestionService}, which would create a
 * circular bean graph with {@link com.tsdb.memtsdb.persistence.WALService} startup ordering.
 * </p>
 */
@Component
public class RecordAppender {

    private final MetricDictionary metricDictionary;
    private final TagDictionary tagDictionary;
    private final TimeSeriesRepository timeSeriesRepository;

    public RecordAppender(
            MetricDictionary metricDictionary,
            TagDictionary tagDictionary,
            TimeSeriesRepository timeSeriesRepository) {
        this.metricDictionary = Objects.requireNonNull(metricDictionary);
        this.tagDictionary = Objects.requireNonNull(tagDictionary);
        this.timeSeriesRepository = Objects.requireNonNull(timeSeriesRepository);
    }

    /**
     * Append one logical datapoint to in-memory structures (no WAL side effects).
     */
    public void appendToMemory(MetricRequest request) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.getMetric(), "metric");

        int metricId = metricDictionary.internMetric(request.getMetric());

        Map<Integer, Integer> tagIds = new HashMap<>();
        Map<String, String> tags = request.getTags();
        if (tags != null) {
            for (Map.Entry<String, String> e : tags.entrySet()) {
                int keyId = tagDictionary.internKey(e.getKey());
                int valId = tagDictionary.internValue(e.getValue());
                tagIds.put(keyId, valId);
            }
        }

        DataPoint point = new DataPoint(metricId, request.getTimestamp(), request.getValue(), tagIds);
        timeSeriesRepository.append(point);
    }
}
