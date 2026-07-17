package com.streamlens.analyzer;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/** Immutable analysis configuration. */
public record Config(
        Instant from,
        Instant to,
        Set<String> allowedTypes,
        Duration window,
        int topK) {

    public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(1);
    public static final int DEFAULT_TOP_K = 3;

    public Config {
        allowedTypes = allowedTypes == null ? Set.of() : Set.copyOf(allowedTypes);
        window = window == null ? DEFAULT_WINDOW : window;
        topK = topK == 0 ? DEFAULT_TOP_K : topK;

        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
        BigInteger totalWindowNanos = BigInteger.valueOf(window.getSeconds())
                .multiply(BigInteger.valueOf(1_000_000_000L))
                .add(BigInteger.valueOf(window.getNano()));
        if (totalWindowNanos.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("window exceeds the supported nanosecond range");
        }
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be positive");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
    }

    public Config() {
        this(null, null, Set.of(), DEFAULT_WINDOW, DEFAULT_TOP_K);
    }
}
