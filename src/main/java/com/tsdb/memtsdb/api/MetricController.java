package com.tsdb.memtsdb.api;

import com.tsdb.memtsdb.model.MetricQueryParams;
import com.tsdb.memtsdb.model.MetricRequest;
import com.tsdb.memtsdb.model.PagedQueryResponse;
import com.tsdb.memtsdb.query.QueryEngine;
import com.tsdb.memtsdb.service.IngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MVC REST controller (Spring MVC variant of MVC).
 * <p>
 * {@code @RestController} = {@code @Controller} + {@code @ResponseBody} on methods — return values serialize directly
 * to HTTP bodies as JSON (Jackson). Analogous to ASP.NET Core controllers returning POCOs serialized by System.Text.Json.
 * </p>
 *
 * <p>
 * Routing uses {@code @RequestMapping} + HTTP verb annotations; central dispatcher ({@code DispatcherServlet}) selects
 * handlers like ASP.NET Core endpoint routing / IRouter.
 * </p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Metrics", description = "Ingestion and querying of time-series samples")
public class MetricController {

    private final IngestionService ingestionService;
    private final QueryEngine queryEngine;

    /**
     * Constructor injection keeps dependencies explicit and enables testing without reflection frameworks.
     */
    public MetricController(IngestionService ingestionService, QueryEngine queryEngine) {
        this.ingestionService = ingestionService;
        this.queryEngine = queryEngine;
    }

    /**
     * POST ingestion endpoint — validates body via {@code @Valid} (Java Bean Validation).
     */
    @Operation(summary = "Ingest a datapoint")
    @PostMapping("/metrics")
    public ResponseEntity<Void> ingest(@Valid @RequestBody MetricRequest request) {
        ingestionService.ingest(request);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    /**
     * Advanced range query: {@code ConcurrentSkipListMap} backs ordered time scans; tag equality filters;
     * offset pagination with rich {@link PagedQueryResponse} DTOs (meta + {@link com.tsdb.memtsdb.model.PageInfo} + items).
     */
    @Operation(summary = "Query datapoints by metric, time range, optional tags; paginated with execution metadata")
    @GetMapping("/query")
    public PagedQueryResponse query(
            @Parameter(description = "Metric name", required = true, example = "cpu_usage")
            @RequestParam String metric,
            @Parameter(description = "Start timestamp (epoch seconds, inclusive)", example = "1710000000")
            @RequestParam long from,
            @Parameter(description = "End timestamp (epoch seconds, inclusive)", example = "1710003600")
            @RequestParam long to,
            @Parameter(description = "Optional tag filters as key:value pairs (repeat param)", example = "host:server-1")
            @RequestParam(required = false) List<String> tag,
            @Parameter(description = "Zero-based page index")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (capped server-side)")
            @RequestParam(defaultValue = "100") int size) {

        Map<String, String> tagFilters = parseTags(tag);
        var queryParams = new MetricQueryParams(metric, from, to, tagFilters, page, size);
        return queryEngine.query(queryParams);
    }

    /**
     * Liveness/readiness style endpoint — lightweight JSON suitable for orchestrators (Kubernetes probes).
     */
    @Operation(summary = "Health check")
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "component", "memtsdb",
                "timestampMs", System.currentTimeMillis()
        );
    }

    /**
     * Parses repeated {@code tag} params formatted {@code key:value} (first colon separates key from value).
     */
    private static Map<String, String> parseTags(List<String> tagParams) {
        if (tagParams == null || tagParams.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        for (String raw : tagParams) {
            int idx = raw.indexOf(':');
            if (idx <= 0 || idx == raw.length() - 1) {
                throw new IllegalArgumentException("Invalid tag parameter (expected key:value): " + raw);
            }
            String key = raw.substring(0, idx);
            String value = raw.substring(idx + 1);
            out.put(key, value);
        }
        return out;
    }
}
