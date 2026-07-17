package com.streamlens.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

final class AnalyzerTest {
    @Test
    void aggregatesAndOrdersGroupsAndTopUsers() throws Exception {
        String input = """
                {"timestamp":"2026-01-15T12:34:56.123Z","tenant_id":"beta","user_id":"z","type":"purchase","value":1}
                {"timestamp":"2026-01-15T14:34:20+02:00","tenant_id":"acme","user_id":"user-7","type":"purchase","value":10.05}
                {"timestamp":"2026-01-15T12:34:10Z","tenant_id":"acme","user_id":"user-42","type":"purchase","value":19.95}
                {"timestamp":"2026-01-15T12:34:40Z","tenant_id":"acme","user_id":"user-7","type":"purchase","value":9.95}
                """;

        List<Group> result = Analyzer.analyze(new StringReader(input), new Config());

        assertEquals(2, result.size());
        Group acme = result.get(0);
        assertEquals(Instant.parse("2026-01-15T12:34:00Z"), acme.windowStart());
        assertEquals("acme", acme.tenantId());
        assertEquals(3, acme.count());
        assertEquals(39.95, acme.sum());
        assertEquals(2, acme.uniqueUsers());
        assertEquals(List.of(
                new TopUser("user-7", 20.0),
                new TopUser("user-42", 19.95)), acme.topUsers());
        assertEquals("beta", result.get(1).tenantId());
    }

    @Test
    void ignoresUnknownFieldsWithoutConvertingThemAndUsesLastExactDuplicate() throws Exception {
        String input = """
                {"timestamp":"invalid","timestamp":"2026-01-15T12:34:00Z","tenant_id":"","tenant_id":"acme","user_id":"u","type":"x","value":"bad","value":2,"VALUE":1e999999,"nested":{"ignored":[1,true,null]}}
                """;

        List<Group> result = Analyzer.analyze(new StringReader(input), new Config());

        assertEquals(1, result.size());
        assertEquals(2.0, result.getFirst().sum());
    }

    @Test
    void ignoresUnknownIntegerBeyondJacksonDefaultNumericRange() throws Exception {
        String input = "{\"timestamp\":\"2026-01-15T12:34:00Z\",\"tenant_id\":\"acme\","
                + "\"user_id\":\"u\",\"type\":\"x\",\"value\":2,\"ignored\":"
                + "9".repeat(2_000) + "}\n";

        List<Group> result = Analyzer.analyze(new StringReader(input), new Config());

        assertEquals(2.0, result.getFirst().sum());
    }

    @Test
    void ignoresBlankLinesAndReturnsNonNullEmptyList() throws Exception {
        List<Group> result = Analyzer.analyze(new StringReader("\n  \t\n"), new Config());
        assertTrue(result.isEmpty());
    }

    @Test
    void reportsEarliestInvalidLine() {
        String input = """

                {"timestamp":"bad","tenant_id":"a","user_id":"u","type":"x","value":1}
                not-json
                """;

        AnalysisException error = assertThrows(
                AnalysisException.class,
                () -> Analyzer.analyze(new StringReader(input), new Config()));
        assertEquals(2, error.lineNumber());
        assertTrue(error.getMessage().startsWith("line 2:"));
    }

    @Test
    void filtersFromInclusivelyToExclusivelyAndByType() throws Exception {
        String input = """
                {"timestamp":"2026-01-15T12:00:00Z","tenant_id":"a","user_id":"u","type":"keep","value":1}
                {"timestamp":"2026-01-15T12:00:01Z","tenant_id":"a","user_id":"u","type":"drop","value":2}
                {"timestamp":"2026-01-15T12:00:02Z","tenant_id":"a","user_id":"u","type":"keep","value":4}
                """;
        Config config = new Config(
                Instant.parse("2026-01-15T12:00:00Z"),
                Instant.parse("2026-01-15T12:00:02Z"),
                Set.of("keep"),
                Duration.ofMinutes(1),
                3);

        List<Group> result = Analyzer.analyze(new StringReader(input), config);

        assertEquals(1, result.size());
        assertEquals(1, result.getFirst().count());
        assertEquals(1.0, result.getFirst().sum());
    }

    @Test
    void alignsWindowsToUtcEpochForNonUtcInput() throws Exception {
        String input = """
                {"timestamp":"2026-01-15T14:34:56+02:00","tenant_id":"a","user_id":"u","type":"x","value":1}
                """;
        Config config = new Config(null, null, Set.of(), Duration.ofMinutes(5), 3);

        Group group = Analyzer.analyze(new StringReader(input), config).getFirst();

        assertEquals(Instant.parse("2026-01-15T12:30:00Z"), group.windowStart());
    }

    @Test
    void alignsNonDivisorWindowsToJavaZeroTimeAnchor() throws Exception {
        String input = """
                {"timestamp":"2026-01-15T12:34:56Z","tenant_id":"a","user_id":"u","type":"x","value":1}
                """;
        Config config = new Config(null, null, Set.of(), Duration.ofMinutes(7), 3);

        Group group = Analyzer.analyze(new StringReader(input), config).getFirst();

        assertEquals(Instant.parse("2026-01-15T12:28:00Z"), group.windowStart());
    }

    @Test
    void reportsEarlierLineErrorBeforeLaterReaderFailure() {
        Reader input = new Reader() {
            private boolean supplied;

            @Override
            public int read(char[] target, int offset, int length) throws IOException {
                if (supplied) {
                    throw new IOException("later reader failure");
                }
                supplied = true;
                String firstLine = "not-json\n";
                firstLine.getChars(0, firstLine.length(), target, offset);
                return firstLine.length();
            }

            @Override
            public void close() {
            }
        };

        AnalysisException error = assertThrows(
                AnalysisException.class,
                () -> Analyzer.analyze(input, new Config()));

        assertEquals(1, error.lineNumber());
    }

    @Test
    void acceptsEmptyTimeInterval() throws Exception {
        Instant boundary = Instant.parse("2026-01-15T12:00:00Z");
        Config config = new Config(boundary, boundary, Set.of(), Duration.ofMinutes(1), 3);
        String input = """
                {"timestamp":"2026-01-15T12:00:00Z","tenant_id":"a","user_id":"u","type":"x","value":1}
                """;

        assertTrue(Analyzer.analyze(new StringReader(input), config).isEmpty());
    }

    @Test
    void rejectsExpandedYearTimestamp() {
        String input = """
                {"timestamp":"+10000-01-15T12:00:00Z","tenant_id":"a","user_id":"u","type":"x","value":1}
                """;

        AnalysisException error = assertThrows(
                AnalysisException.class,
                () -> Analyzer.analyze(new StringReader(input), new Config()));

        assertEquals(1, error.lineNumber());
    }

    @Test
    void preservesSequentialDoubleAddition() throws Exception {
        String input = """
                {"timestamp":"2026-01-15T12:00:00Z","tenant_id":"a","user_id":"u","type":"x","value":10000000000000000}
                {"timestamp":"2026-01-15T12:00:01Z","tenant_id":"a","user_id":"u","type":"x","value":1}
                {"timestamp":"2026-01-15T12:00:02Z","tenant_id":"a","user_id":"u","type":"x","value":1}
                """;

        Group group = Analyzer.analyze(new StringReader(input), new Config()).getFirst();

        double expected = 10_000_000_000_000_000.0;
        expected += 1.0;
        expected += 1.0;
        assertEquals(expected, group.sum());
    }

    @Test
    void rejectsGroupOverflowAtTheCorrectLine() {
        String input = """
                {"timestamp":"2026-01-15T12:00:00Z","tenant_id":"a","user_id":"u","type":"x","value":1.7976931348623157e308}
                {"timestamp":"2026-01-15T12:00:01Z","tenant_id":"a","user_id":"v","type":"x","value":1.7976931348623157e308}
                """;

        AnalysisException error = assertThrows(
                AnalysisException.class,
                () -> Analyzer.analyze(new StringReader(input), new Config()));
        assertEquals(2, error.lineNumber());
        assertTrue(error.getMessage().contains("overflow"));
    }

    @Test
    void supportsLinesLargerThanScannerDefaults() throws Exception {
        String padding = "x".repeat(256 * 1024);
        String input = "{\"timestamp\":\"2026-01-15T12:00:00Z\",\"tenant_id\":\"a\","
                + "\"user_id\":\"u\",\"type\":\"x\",\"value\":1,\"padding\":\""
                + padding + "\"}\n";

        List<Group> result = Analyzer.analyze(new StringReader(input), new Config());

        assertEquals(1, result.size());
    }

    @Test
    void observesThreadInterruptionBeforeReading() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    InterruptedIOException.class,
                    () -> Analyzer.analyze(new StringReader(""), new Config()));
        } finally {
            assertTrue(Thread.interrupted());
            assertFalse(Thread.currentThread().isInterrupted());
        }
    }

    @Test
    void rejectsNullsAndInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> Analyzer.analyze(null, new Config()));
        assertThrows(IllegalArgumentException.class, () -> Analyzer.analyze(new StringReader(""), null));
        assertThrows(IllegalArgumentException.class,
                () -> new Config(null, null, Set.of(), Duration.ZERO, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new Config(null, null, Set.of(), Duration.ofMinutes(1), -1));
        assertThrows(IllegalArgumentException.class,
                () -> new Config(null, null, Set.of(), Duration.ofSeconds(Long.MAX_VALUE), 3));
    }
}
