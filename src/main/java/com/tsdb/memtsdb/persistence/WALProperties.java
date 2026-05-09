package com.tsdb.memtsdb.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe binding for {@code memtsdb.wal.*} keys from {@code application.properties}.
 * <p>
 * Analogous to IOptions&lt;T&gt; / configuration POCOs in ASP.NET Core.
 * {@code @ConfigurationProperties} maps dotted keys to fields (prefix strips {@code memtsdb.wal}).
 * </p>
 */
@ConfigurationProperties(prefix = "memtsdb.wal")
public class WALProperties {

    /**
     * Filesystem path for append-only log ({@code metrics.wal} by default).
     */
    private String path = "./data/wal/metrics.wal";

    /**
     * When true, scan {@link #path} on startup and rebuild in-memory indexes before accepting WAL appends.
     */
    private boolean replayEnabled = true;

    /**
     * Back-pressure buffer size for producer-consumer queue.
     */
    private int queueCapacity = 65_536;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public boolean isReplayEnabled() {
        return replayEnabled;
    }

    public void setReplayEnabled(boolean replayEnabled) {
        this.replayEnabled = replayEnabled;
    }
}
