package com.streamlens.analyzer;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.JsonToken;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** StreamLens event analyzer. */
public final class Analyzer {
    // End-to-end implementation-only workflow canary; behavior is unchanged.
    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);
    private static final long ZERO_TIME_EPOCH_SECONDS = -62_135_596_800L;

    private Analyzer() {
    }

    public static List<Group> analyze(Reader input, Config config)
            throws IOException, AnalysisException {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        checkInterrupted();

        BufferedReader reader = input instanceof BufferedReader buffered
                ? buffered
                : new BufferedReader(input);
        List<GroupAccumulator> groups = new ArrayList<>();
        List<String> allowedTypes = new ArrayList<>(config.allowedTypes());
        long lineNumber = 0;
        String sourceLine;
        while ((sourceLine = reader.readLine()) != null) {
            lineNumber++;
            checkInterrupted();
            if (sourceLine.isBlank()) {
                continue;
            }

            Event event = parseEvent(sourceLine, lineNumber);
            if (config.from() != null && event.timestamp().isBefore(config.from())) {
                continue;
            }
            if (config.to() != null && !event.timestamp().isBefore(config.to())) {
                continue;
            }
            if (!allowedTypes.isEmpty() && !allowedTypes.contains(event.type())) {
                continue;
            }

            checkInterrupted();
            Instant windowStart = truncate(event.timestamp(), config.window());
            GroupAccumulator group = null;
            for (GroupAccumulator candidate : groups) {
                if (candidate.windowStart.equals(windowStart)
                        && candidate.tenantId.equals(event.tenantId())
                        && candidate.type.equals(event.type())) {
                    group = candidate;
                    break;
                }
            }
            if (group == null) {
                group = new GroupAccumulator(windowStart, event.tenantId(), event.type());
                groups.add(group);
            }

            double nextGroupSum = group.sum + event.value();
            if (!Double.isFinite(nextGroupSum)) {
                throw new AnalysisException(lineNumber, "group sum overflow");
            }
            group.sum = nextGroupSum;
            group.count++;

            UserAccumulator user = null;
            for (UserAccumulator candidate : group.users) {
                if (candidate.userId.equals(event.userId())) {
                    user = candidate;
                    break;
                }
            }
            if (user == null) {
                user = new UserAccumulator(event.userId());
                group.users.add(user);
            }
            double nextUserSum = user.value + event.value();
            if (!Double.isFinite(nextUserSum)) {
                throw new AnalysisException(lineNumber, "user sum overflow");
            }
            user.value = nextUserSum;
        }

        groups.sort(Comparator
                .comparing((GroupAccumulator group) -> group.windowStart)
                .thenComparing(group -> group.tenantId)
                .thenComparing(group -> group.type));

        List<Group> result = new ArrayList<>(groups.size());
        for (GroupAccumulator group : groups) {
            group.users.sort(Comparator
                    .comparingDouble((UserAccumulator user) -> user.value)
                    .reversed()
                    .thenComparing(user -> user.userId));
            int topCount = Math.min(config.topK(), group.users.size());
            List<TopUser> topUsers = new ArrayList<>(topCount);
            for (int i = 0; i < topCount; i++) {
                UserAccumulator user = group.users.get(i);
                topUsers.add(new TopUser(user.userId, user.value));
            }
            result.add(new Group(
                    group.windowStart,
                    group.tenantId,
                    group.type,
                    group.count,
                    group.sum,
                    group.users.size(),
                    topUsers));
        }
        return result;
    }

    private static Event parseEvent(String line, long lineNumber) throws AnalysisException {
        RawValue timestampField = null;
        RawValue tenantField = null;
        RawValue userField = null;
        RawValue typeField = null;
        RawValue valueField = null;

        JsonFactory factory = new JsonFactory();
        factory.setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNumberLength(Integer.MAX_VALUE)
                .build());
        try (JsonParser parser = factory.createParser(line)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new AnalysisException(lineNumber, "event must be a JSON object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    throw new AnalysisException(lineNumber, "invalid JSON object");
                }
                String field = parser.currentName();
                JsonToken token = parser.nextToken();
                if (token == null) {
                    throw new AnalysisException(lineNumber, "missing field value");
                }
                switch (field) {
                    case "timestamp" -> timestampField = capture(parser, token);
                    case "tenant_id" -> tenantField = capture(parser, token);
                    case "user_id" -> userField = capture(parser, token);
                    case "type" -> typeField = capture(parser, token);
                    case "value" -> valueField = capture(parser, token);
                    default -> parser.skipChildren();
                }
            }
            if (parser.nextToken() != null) {
                throw new AnalysisException(lineNumber, "unexpected data after event");
            }
        } catch (AnalysisException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new AnalysisException(lineNumber, "invalid JSON", exception);
        }

        if (timestampField == null || tenantField == null || userField == null
                || typeField == null || valueField == null) {
            throw new AnalysisException(lineNumber, "missing required field");
        }
        String timestamp = requireString(timestampField, "timestamp", lineNumber);
        String tenantId = requireString(tenantField, "tenant_id", lineNumber);
        String userId = requireString(userField, "user_id", lineNumber);
        String type = requireString(typeField, "type", lineNumber);
        if (!valueField.token().isNumeric()) {
            throw new AnalysisException(lineNumber, "value must be a number");
        }
        double value;
        try {
            value = Double.parseDouble(valueField.text());
        } catch (NumberFormatException exception) {
            throw new AnalysisException(lineNumber, "value must be a representable number", exception);
        }
        if (tenantId.isEmpty() || userId.isEmpty() || type.isEmpty()) {
            throw new AnalysisException(lineNumber, "tenant_id, user_id, and type must be non-empty");
        }
        if (!Double.isFinite(value) || value < 0) {
            throw new AnalysisException(lineNumber, "value must be finite and non-negative");
        }

        Instant parsedTimestamp;
        if (!timestamp.matches(
                "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"
                        + "(?:\\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})")) {
            throw new AnalysisException(lineNumber, "timestamp must be RFC3339 with an explicit offset");
        }
        try {
            parsedTimestamp = OffsetDateTime.parse(timestamp, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeException exception) {
            throw new AnalysisException(lineNumber, "timestamp must be RFC3339 with an explicit offset", exception);
        }
        return new Event(parsedTimestamp, tenantId, userId, type, value);
    }

    private static RawValue capture(JsonParser parser, JsonToken token) throws IOException {
        if (token == JsonToken.START_ARRAY || token == JsonToken.START_OBJECT) {
            parser.skipChildren();
            return new RawValue(token, null);
        }
        return new RawValue(token, parser.getText());
    }

    private static String requireString(
            RawValue value,
            String field,
            long lineNumber) throws AnalysisException {
        if (value.token() != JsonToken.VALUE_STRING) {
            throw new AnalysisException(lineNumber, field + " must be a string");
        }
        return value.text();
    }

    private static Instant truncate(Instant timestamp, Duration window) {
        BigInteger windowNanos = BigInteger.valueOf(window.getSeconds())
                .multiply(NANOS_PER_SECOND)
                .add(BigInteger.valueOf(window.getNano()));
        BigInteger sinceZeroNanos = BigInteger.valueOf(timestamp.getEpochSecond() - ZERO_TIME_EPOCH_SECONDS)
                .multiply(NANOS_PER_SECOND)
                .add(BigInteger.valueOf(timestamp.getNano()));
        BigInteger startSinceZero = sinceZeroNanos.subtract(sinceZeroNanos.mod(windowNanos));
        BigInteger[] secondsAndNanos = startSinceZero.divideAndRemainder(NANOS_PER_SECOND);
        long epochSeconds = secondsAndNanos[0].longValueExact() + ZERO_TIME_EPOCH_SECONDS;
        return Instant.ofEpochSecond(epochSeconds, secondsAndNanos[1].longValueExact());
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("analysis interrupted");
        }
    }

    private record Event(
            Instant timestamp,
            String tenantId,
            String userId,
            String type,
            double value) {
    }

    private record RawValue(JsonToken token, String text) {
    }

    private static final class GroupAccumulator {
        private final Instant windowStart;
        private final String tenantId;
        private final String type;
        private final List<UserAccumulator> users = new ArrayList<>();
        private long count;
        private double sum;

        private GroupAccumulator(Instant windowStart, String tenantId, String type) {
            this.windowStart = windowStart;
            this.tenantId = tenantId;
            this.type = type;
        }
    }

    private static final class UserAccumulator {
        private final String userId;
        private double value;

        private UserAccumulator(String userId) {
            this.userId = userId;
        }
    }
}
