package com.streamlens.analyzer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Filtering and aggregation settings for {@link Analyzer}. */
public record AnalyzerConfig(
        Instant from,
        Instant to,
        List<String> types,
        Duration window,
        int topK) {
    public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(1);
    public static final int DEFAULT_TOP_K = 3;

    public AnalyzerConfig {
        types = types == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(types));
        window = window == null ? Duration.ZERO : window;
    }

    /** Returns a configuration whose zero values select all documented defaults. */
    public AnalyzerConfig() {
        this(null, null, List.of(), Duration.ZERO, 0);
    }

    public static AnalyzerConfig defaults() {
        return new AnalyzerConfig();
    }
}
