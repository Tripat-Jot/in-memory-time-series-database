package com.tsdb.memtsdb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "memtsdb.cors")
public class CorsProperties {

    /**
     * Patterns passed to {@link org.springframework.web.servlet.config.annotation.CorsRegistration#allowedOriginPatterns}.
     * Override via {@code memtsdb.cors.allowed-origin-patterns} (indexed keys or YAML list).
     */
    private List<String> allowedOriginPatterns = new ArrayList<>(List.of(
            "http://localhost:*",
            "http://127.0.0.1:*",
            "https://memtsdb-ui.onrender.com"));

    public List<String> getAllowedOriginPatterns() {
        return allowedOriginPatterns;
    }

    public void setAllowedOriginPatterns(List<String> allowedOriginPatterns) {
        this.allowedOriginPatterns = allowedOriginPatterns;
    }
}
