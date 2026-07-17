package com.streamlens.analyzer;

import java.time.Instant;
import java.util.List;

/** Deterministic aggregate emitted by the analyzer. */
public record Group(
        Instant windowStart,
        String tenantId,
        String type,
        long count,
        double sum,
        int uniqueUsers,
        List<TopUser> topUsers) {

    public Group {
        topUsers = List.copyOf(topUsers);
    }
}
