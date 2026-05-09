package com.tsdb.memtsdb.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tsdb.memtsdb.model.MetricRequest;
import com.tsdb.memtsdb.persistence.WALService;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Facade over dictionaries, repository, and WAL (Facade pattern).
 * <p>
 * Controllers depend on this narrow surface instead of orchestrating five collaborators — mirrors a Mediator/Facade in
 * .NET where an ApplicationService coordinates repositories + messaging + domain rules behind one interface.
 * </p>
 *
 * <p><b>Dependency Injection:</b> Spring resolves this singleton at startup and injects constructor parameters based on
 * types (like constructor injection in ASP.NET Core {@code services.AddSingleton&lt;IngestionService&gt;} with DI graph).
 * </p>
 */
@Service
public class IngestionService {

    private final RecordAppender recordAppender;
    private final WALService walService;
    private final ObjectMapper objectMapper;
    /**
     * Virtual-thread executor (Java 21) for fire-and-forget WAL writes — lightweight compared to platform threads.
     */
    private final ExecutorService walExecutor;

    public IngestionService(
            RecordAppender recordAppender,
            WALService walService,
            ObjectMapper objectMapper) {
        this.recordAppender = Objects.requireNonNull(recordAppender);
        this.walService = Objects.requireNonNull(walService);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.walExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @PreDestroy
    void shutdownWalExecutor() {
        walExecutor.shutdown();
        try {
            if (!walExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                walExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            walExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Ingests one datapoint: intern strings → immutable {@link DataPoint} → memory append → async WAL JSON line.
     */
    public void ingest(MetricRequest request) {
        recordAppender.appendToMemory(request);

        final String jsonLine;
        try {
            // WAL stores the external JSON shape for human replay / disaster tooling (not interned IDs).
            jsonLine = objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize WAL payload", e);
        }

        walExecutor.execute(() -> {
            try {
                walService.enqueue(jsonLine);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
