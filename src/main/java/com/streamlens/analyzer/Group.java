package com.streamlens.analyzer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The aggregate for one UTC window, tenant, and event type. */
public record Group(
        Instant windowStart,
        String tenantId,
        String type,
        long count,
        double sum,
        int uniqueUsers,
        List<TopUser> topUsers) {
    public Group {
        topUsers = Collections.unmodifiableList(new ArrayList<>(topUsers));
    }
}
