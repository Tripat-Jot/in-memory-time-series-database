package com.tsdb.memtsdb.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * HTTP POST body DTO for ingestion (MVC model binding).
 * <p>
 * Jackson maps JSON fields to Java beans by getters/setters or constructor parameters (similar to
 * System.Text.Json deserialization into POCOs). {@code jakarta.validation} annotations trigger automatic
 * validation when the controller parameter is annotated with {@code @Valid} — akin to FluentValidation or
 * DataAnnotations on ASP.NET Core models.
 * </p>
 */
public class MetricRequest {

    @NotBlank
    private String metric;

    /**
     * Epoch seconds (matches sample payload). Stored as Long for JSON numeric compatibility.
     */
    @NotNull
    private Long timestamp;

    @NotNull
    private Double value;

    /**
     * Optional tags; empty map if omitted in JSON.
     */
    private Map<String, String> tags = new HashMap<>();

    public String getMetric() {
        return metric;
    }

    public void setMetric(String metric) {
        this.metric = metric;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    public Double getValue() {
        return value;
    }

    public void setValue(Double value) {
        this.value = value;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new HashMap<>();
    }
}
