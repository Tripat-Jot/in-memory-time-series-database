package com.tsdb.memtsdb.persistence;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Write-Ahead Log persistence using an asynchronous producer-consumer pipeline.
 * <p>
 * <b>Producer-consumer pattern:</b> HTTP threads (producers) enqueue newline-delimited JSON quickly; a dedicated
 * consumer thread batches writes to disk so ingestion latency is decoupled from fsync policy (here: OS page cache
 * flush cadence — production would add explicit fsync intervals or segment rotation).
 * </p>
 *
 * <p><b>Why LinkedBlockingQueue:</b> bounded buffer ({@link WALProperties#getQueueCapacity}) provides back-pressure:
 * if disk stalls, producers block on {@code put} rather than allocating unbounded heap (mirrors Channel&lt;T&gt;
 * with BoundedChannelFullMode.Wait in .NET).
 * </p>
 *
 * <p><b>Concurrency:</b> Multiple producers, single consumer — classic MP-SC queue usage; LinkedBlockingQueue is
 * separately lockable for head/tail in many JDK implementations, offering decent throughput for bursty WAL writes.
 * </p>
 *
 * <p><b>Tradeoffs:</b> Async WAL means a crash window where RAM accepted data not yet on disk — MemTSDB is explicitly
 * in-memory-first; pairing with periodic snapshots or replicated consensus would close that gap in production.</p>
 *
 * <p><b>Startup order:</b> {@code @DependsOn("walReplayService")} ensures {@link WALReplayService} reads and replays
 * {@code metrics.wal} (or configured path) <i>before</i> this class starts the append consumer, so recovery sees a
 * consistent snapshot of the file before new lines are written.</p>
 */
@Service
@DependsOn("walReplayService")
public class WALService {

    private final WALProperties properties;
    private LinkedBlockingQueue<String> queue;
    private ExecutorService consumer;
    private volatile boolean running;

    /**
     * Constructor injection — preferred in Spring (dependencies explicit, fields {@code final}, trivial testing).
     * Same motivation as primary-constructor DI in ASP.NET Core when registering singleton services.
     */
    public WALService(WALProperties properties) {
        this.properties = Objects.requireNonNull(properties);
    }

    @PostConstruct
    void startConsumer() {
        this.queue = new LinkedBlockingQueue<>(Math.max(256, properties.getQueueCapacity()));
        this.running = true;
        this.consumer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "memtsdb-wal-writer");
            t.setDaemon(true);
            return t;
        });
        this.consumer.submit(this::consumeLoop);
    }

    /**
     * Enqueues one WAL record (already serialized JSON line without newline characters).
     *
     * @throws InterruptedException if blocked waiting for queue capacity and interrupted
     */
    public void enqueue(String jsonLine) throws InterruptedException {
        // put blocks — producers slow down under sustained disk pressure (deliberate back-pressure).
        queue.put(jsonLine);
    }

    /**
     * Non-blocking attempt — callers may drop or retry if overwhelmed (policy hook).
     */
    public boolean offer(String jsonLine, long timeout, TimeUnit unit) throws InterruptedException {
        return queue.offer(jsonLine, timeout, unit);
    }

    private void consumeLoop() {
        Path walPath = Path.of(properties.getPath());
        try {
            Path parent = walPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(walPath)) {
                Files.createFile(walPath);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot initialize WAL path: " + properties.getPath(), e);
        }

        try (BufferedWriter writer = Files.newBufferedWriter(
                walPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {
            while (running || !queue.isEmpty()) {
                String line = queue.poll(200, TimeUnit.MILLISECONDS);
                if (line == null) {
                    continue;
                }
                writer.write(line);
                writer.newLine();
                // Note: flush per line trades throughput for durability visibility; batch + timed flush is typical.
                writer.flush();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (IOException ioe) {
            throw new IllegalStateException("WAL writer failure", ioe);
        }
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        running = false;
        consumer.shutdownNow();
        consumer.awaitTermination(5, TimeUnit.SECONDS);
    }
}
