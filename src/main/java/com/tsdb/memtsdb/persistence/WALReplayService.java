package com.tsdb.memtsdb.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tsdb.memtsdb.model.MetricRequest;
import com.tsdb.memtsdb.service.RecordAppender;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Replays newline-delimited JSON WAL records on startup to rebuild in-memory series + intern dictionaries.
 * <p>
 * {@link WALService} declares {@code @DependsOn("walReplayService")} so this bean finishes {@link PostConstruct}
 * <i>before</i> the asynchronous WAL writer thread opens the log for append — avoiding read/write races on the same file.
 * </p>
 *
 * <h2>Append-only logging</h2>
 * The WAL is an <b>append-only</b> sequence of immutable records (JSON lines). New writes always extend the tail;
 * recovery replays from the beginning to reconstruct state. This matches LSM / segment logs in production databases:
 * sequential writes are cheap on HDD/SSD, truncation/compaction is a separate offline concern.
 *
 * <h2>Crash recovery</h2>
 * On unclean shutdown, the last line might be partially written (torn write). Strategies:
 * <ul>
 *   <li><b>Length-prefixed frames</b> or CRCs per record (not implemented here — JSON lines only).</li>
 *   <li><b>Truncate last partial line</b> before replay (optional enhancement).</li>
 *   <li><b>Checkpoint + WAL sequence numbers</b> to skip verified prefixes faster.</li>
 * </ul>
 * This implementation skips blank lines, ignores {@code #} comment lines for manual tooling, logs and skips malformed JSON,
 * and continues — <i>best-effort</i> recovery suitable for demos; strict systems fail-fast or snapshot+binary WAL.
 *
 * <h2>Durability guarantees</h2>
 * MemTSDB persists asynchronously: accept → RAM → bounded queue → disk. Guarantees are:
 * <ul>
 *   <li><b>No fsync per record</b> here — OS buffer cache may lose the last milliseconds of WAL on power loss.</li>
 *   <li><b>Bounded loss window</b> between enqueue and {@link java.io.BufferedWriter#flush}: crash may drop in-flight queue items.</li>
 *   <li><b>Replay restores whatever reached stable bytes on disk</b> from previous runs; in-flight async writes not visible until flushed.</li>
 * </ul>
 * Stronger durability would batch + {@code FileChannel#force} on an interval, or use replicated quorum writes.
 */
@Component("walReplayService")
public class WALReplayService {

    private static final Logger log = LoggerFactory.getLogger(WALReplayService.class);

    private final WALProperties walProperties;
    private final ObjectMapper objectMapper;
    private final RecordAppender recordAppender;

    public WALReplayService(WALProperties walProperties, ObjectMapper objectMapper, RecordAppender recordAppender) {
        this.walProperties = walProperties;
        this.objectMapper = objectMapper;
        this.recordAppender = recordAppender;
    }

    @PostConstruct
    void replayWalOnStartup() {
        if (!walProperties.isReplayEnabled()) {
            log.info("WAL replay disabled (memtsdb.wal.replay-enabled=false)");
            return;
        }

        Path walPath = Path.of(walProperties.getPath());
        if (!Files.exists(walPath) || !Files.isReadable(walPath)) {
            log.info("No WAL file to replay at {}", walPath.toAbsolutePath());
            return;
        }

        long replayed = 0;
        long skipped = 0;

        try (BufferedReader reader = Files.newBufferedReader(walPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                try {
                    MetricRequest record = objectMapper.readValue(line, MetricRequest.class);
                    recordAppender.appendToMemory(record);
                    replayed++;
                } catch (Exception ex) {
                    skipped++;
                    log.warn("Skipping malformed WAL line ({}): {}", ex.getMessage(), truncate(line, 200));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read WAL for replay: " + walPath.toAbsolutePath(), e);
        }

        log.info("WAL replay complete: path={}, recordsReplayed={}, linesSkipped={}", walPath.toAbsolutePath(), replayed, skipped);
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...";
    }
}
