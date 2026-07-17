package com.streamlens.analyzer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class AnalyzerTest {
    private static final AnalyzerConfig DEFAULTS = AnalyzerConfig.defaults();

    @AfterEach
    void clearInterruptStatus() {
        Thread.interrupted();
    }

    @Test
    void aggregatesSequentiallyAndOrdersGroupsAndTopUsers() throws Exception {
        String input = String.join("\n",
                event("2026-01-15T12:34:58Z", "z", "u2", "purchase", "10.0"),
                event("2026-01-15T12:34:01Z", "z", "u1", "purchase", "10.0"),
                event("2026-01-15T12:34:20Z", "z", "u3", "purchase", "11.0"),
                event("2026-01-15T12:34:30Z", "z", "u1", "purchase", "1.0"),
                event("2026-01-15T12:33:59Z", "a", "u", "view", "2.0"));

        List<Group> groups = analyze(input, new AnalyzerConfig(null, null, null, Duration.ZERO, 2));

        assertEquals(2, groups.size());
        assertEquals(Instant.parse("2026-01-15T12:33:00Z"), groups.get(0).windowStart());
        Group group = groups.get(1);
        assertEquals(4, group.count());
        assertEquals(32.0, group.sum());
        assertEquals(3, group.uniqueUsers());
        assertEquals(List.of(new TopUser("u1", 11.0), new TopUser("u3", 11.0)), group.topUsers());
    }

    @Test
    void preservesInputOrderDoubleAddition() throws Exception {
        String input = String.join("\n",
                event("2026-01-01T00:00:00Z", "t", "u", "x", "1e16"),
                event("2026-01-01T00:00:01Z", "t", "u", "x", "1"),
                event("2026-01-01T00:00:02Z", "t", "u", "x", "1"));
        Group group = analyze(input, DEFAULTS).getFirst();
        assertEquals((1e16 + 1.0) + 1.0, group.sum());
        assertEquals((1e16 + 1.0) + 1.0, group.topUsers().getFirst().value());
    }

    @Test
    void acceptsNegativeZeroAsARealDoubleValue() throws Exception {
        Group group = analyze(
                event("2026-01-01T00:00:00Z", "t", "u", "x", "-0"), DEFAULTS).getFirst();
        assertEquals(Double.doubleToRawLongBits(-0.0),
                Double.doubleToRawLongBits(group.topUsers().getFirst().value()));
    }

    @Test
    void appliesInclusiveFromExclusiveToAndTypeFilterAfterValidation() throws Exception {
        String input = String.join("\n",
                event("2026-01-01T00:00:00+02:00", "t", "u0", "keep", "1"),
                event("2025-12-31T22:00:01Z", "t", "u1", "drop", "2"),
                event("2025-12-31T22:00:02Z", "t", "u2", "keep", "3"));
        AnalyzerConfig config = new AnalyzerConfig(
                Instant.parse("2025-12-31T22:00:00Z"),
                Instant.parse("2025-12-31T22:00:02Z"),
                List.of("keep"), Duration.ofSeconds(1), 3);
        List<Group> groups = analyze(input, config);
        assertEquals(1, groups.size());
        assertEquals(1.0, groups.getFirst().sum());
    }

    @Test
    void validatesFilteredEventsBeforeDiscardingThem() {
        String input = "{\"timestamp\":\"2020-01-01T00:00:00Z\","
                + "\"tenant_id\":\"t\",\"user_id\":\"u\",\"type\":\"drop\",\"value\":1e400}";
        AnalysisException error = assertThrows(AnalysisException.class,
                () -> analyze(input, new AnalyzerConfig(null, null, List.of("keep"), Duration.ZERO, 0)));
        assertTrue(error.getMessage().contains("line 1"));
        assertTrue(error.getMessage().contains("finite"));
    }

    @Test
    void ignoresAllContractWhitespaceLinesButCountsThem() {
        String input = " \t\r\n\u0085\n{bad}";
        AnalysisException error = assertThrows(AnalysisException.class, () -> analyze(input, DEFAULTS));
        assertTrue(error.getMessage().contains("line 3"), error.getMessage());
    }

    @Test
    void trimsContractWhitespaceAroundAJsonEvent() throws Exception {
        String input = "\u00a0"
                + event("2026-01-01T00:00:00Z", "t", "u", "x", "1")
                + "\u3000";
        assertEquals(1.0, analyze(input, DEFAULTS).getFirst().sum());
    }

    @Test
    void handlesCrLfAndLinesLargerThanCommonScannerLimits() throws Exception {
        String huge = "x".repeat(256 * 1024);
        String input = "{\"ignored\":\"" + huge + "\","
                + fields("2026-01-01T00:00:00Z", "t", "u", "x", "1") + "}\r\n";
        List<Group> groups = analyze(input, DEFAULTS);
        assertEquals(1, groups.size());
        assertEquals(1.0, groups.getFirst().sum());
    }

    @Test
    void rejectsMalformedUtf8WithPhysicalLine() {
        byte[] prefix = "\n".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[] {prefix[0], (byte) 0xc3, (byte) 0x28, '\n'};
        AnalysisException error = assertThrows(AnalysisException.class,
                () -> Analyzer.analyze(new ByteArrayInputStream(bytes), DEFAULTS));
        assertTrue(error.getMessage().contains("line 2"), error.getMessage());
        assertTrue(error.getMessage().contains("UTF-8"));
    }

    @Test
    void usesLastDecodedDuplicateAndDoesNotConvertEarlierValue() throws Exception {
        String input = "{\"timestamp\":false,\"\\u0074imestamp\":\"2026-01-01T00:00:00Z\","
                + "\"tenant_id\":1,\"tenant_id\":\"t\","
                + "\"user_id\":null,\"user_id\":\"u\","
                + "\"type\":[],\"type\":\"x\",\"value\":1e400,\"value\":2}";
        assertEquals(2.0, analyze(input, DEFAULTS).getFirst().sum());
    }

    @Test
    void applicationValidatesTheLastDuplicateEvenWhenEarlierValueWasValid() {
        String input = "{\"timestamp\":\"2026-01-01T00:00:00Z\","
                + "\"tenant_id\":\"t\",\"user_id\":\"u\",\"type\":\"x\","
                + "\"value\":1,\"value\":false}";
        assertLineError(input, 1, "value must be a number");
    }

    @Test
    void stillSyntaxChecksEarlierDuplicatesAndUnknownNestedValues() {
        String malformed = "{\"timestamp\":{,},"
                + fields("2026-01-01T00:00:00Z", "t", "u", "x", "1") + "}";
        assertLineError(malformed, 1, "invalid JSON");

        String unknown = "{\"ignored\":[1,{\"x\":[true,false,null,1e999999]}],"
                + fields("2026-01-01T00:00:00Z", "t", "u", "x", "1") + "}";
        try {
            assertEquals(1.0, analyze(unknown, DEFAULTS).getFirst().sum());
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    @Test
    void fieldNamesAreDecodedCaseSensitiveAndUnpairedSurrogatesBecomeReplacement() throws Exception {
        String input = "{\"timestamp\":\"2026-01-01T00:00:00Z\","
                + "\"tenant_id\":\"\\ud800\",\"user_id\":\"\\udc00\","
                + "\"type\":\"x\",\"value\":1,\"Timestamp\":false}";
        Group group = analyze(input, DEFAULTS).getFirst();
        assertEquals("�", group.tenantId());
        assertEquals("�", group.topUsers().getFirst().userId());
    }

    @Test
    void acceptsValidEscapesAndRejectsInvalidOrTrailingJsonContent() throws Exception {
        String escaped = "{\"timestamp\":\"2026-01-01T00:00:00Z\","
                + "\"tenant_id\":\"\\\\\\\"\\/\\b\\f\\n\\r\\t\\ud83d\\ude00\","
                + "\"user_id\":\"u\",\"type\":\"x\",\"value\":1}";
        Group group = analyze(escaped, DEFAULTS).getFirst();
        assertEquals("\\\"/\b\f\n\r\t😀", group.tenantId());

        assertLineError(event("2026-01-01T00:00:00Z", "t", "u", "x", "1") + " true", 1,
                "unexpected content");
        assertLineError("{\"timestamp\":\"2026-01-01T00:00:00Z\","
                        + "\"tenant_id\":\"\\x\",\"user_id\":\"u\",\"type\":\"x\",\"value\":1}",
                1, "invalid string escape");
        assertLineError("{\"timestamp\":\"2026-01-01T00:00:00Z\","
                        + "\"tenant_id\":\"a\u0001b\",\"user_id\":\"u\",\"type\":\"x\",\"value\":1}",
                1, "unescaped control");
    }

    @Test
    void rejectsMissingWrongTypedAndEmptyRequiredFields() {
        assertLineError("{}", 1, "timestamp is required");
        assertLineError("{\"timestamp\":1,\"tenant_id\":\"t\",\"user_id\":\"u\",\"type\":\"x\",\"value\":1}",
                1, "timestamp must be a string");
        assertLineError(event("2026-01-01T00:00:00Z", "", "u", "x", "1"),
                1, "tenant_id must not be empty");
        assertLineError(event("2026-01-01T00:00:00Z", "t", "u", "x", "true"),
                1, "value must be a number");
    }

    @Test
    void enforcesJsonNumberGrammarAndFiniteRequiredValues() {
        for (String number : List.of("01", "1.", ".1", "1e", "NaN", "Infinity")) {
            assertLineError(event("2026-01-01T00:00:00Z", "t", "u", "x", number), 1,
                    "invalid JSON");
        }
        assertLineError(event("2026-01-01T00:00:00Z", "t", "u", "x", "1e400"), 1, "finite");
        assertLineError(event("2026-01-01T00:00:00Z", "t", "u", "x", "-1"), 1,
                "greater than or equal");
    }

    @Test
    void parsesStrictTimestampsAndEquivalentOffsets() throws Exception {
        String input = String.join("\n",
                event("2026-01-01T23:59:59.123456789+23:59", "t", "u1", "x", "1"),
                event("2026-01-01T00:00:59.123456789Z", "t", "u2", "x", "2"));
        Group group = analyze(input, DEFAULTS).getFirst();
        assertEquals(2, group.count());
        assertEquals(3.0, group.sum());

        for (String timestamp : List.of(
                "2026-01-01 00:00:00Z", "2026-01-01T00:00:00", "2026-01-01T00:00Z",
                "2026-01-01T00:00:00.1234567890Z", "2026-01-01T00:00:00+24:00")) {
            assertLineError(event(timestamp, "t", "u", "x", "1"), 1, "timestamp");
        }
    }

    @Test
    void alignsArbitraryNanosecondWindowsFromYearOneAnchor() throws Exception {
        AnalyzerConfig nanos = new AnalyzerConfig(
                null, null, List.of(), Duration.ofNanos(7), 1);
        Group group = analyze(event("1970-01-01T00:00:00.000000001Z", "t", "u", "x", "1"), nanos)
                .getFirst();
        assertEquals(Instant.parse("1969-12-31T23:59:59.999999997Z"), group.windowStart());

        Group preAnchor = analyze(event("0000-12-31T23:59:59.999999999Z", "t", "u", "x", "1"), nanos)
                .getFirst();
        assertFalse(preAnchor.windowStart().isAfter(Instant.parse("0000-12-31T23:59:59.999999999Z")));
    }

    @Test
    void acceptsExactFourDigitOutputRangeBoundaries() throws Exception {
        AnalyzerConfig nanos = new AnalyzerConfig(null, null, List.of(), Duration.ofNanos(1), 1);
        assertEquals(Instant.parse("0000-01-01T00:00:00Z"),
                analyze(event("0000-01-01T00:00:00Z", "t", "u", "x", "1"), nanos)
                        .getFirst().windowStart());
        assertEquals(Instant.parse("9999-12-31T23:59:59.999999999Z"),
                analyze(event("9999-12-31T23:59:59.999999999Z", "t", "u", "x", "1"), nanos)
                        .getFirst().windowStart());
    }

    @Test
    void ordersStringsByUtf16CodeUnitsNotCodePointsOrLocale() throws Exception {
        String bmp = "\ue000";
        String supplementary = "\ud800\udc00";
        String input = String.join("\n",
                event("2026-01-01T00:00:00Z", bmp, "u", "x", "1"),
                event("2026-01-01T00:00:00Z", supplementary, "u", "x", "1"));
        List<Group> groups = analyze(input, DEFAULTS);
        assertEquals(supplementary, groups.getFirst().tenantId());
        assertEquals(bmp, groups.get(1).tenantId());
    }

    @Test
    void groupingKeyCannotCollideOnEmbeddedSeparatorsOrLengths() throws Exception {
        String input = String.join("\n",
                event("2026-01-01T00:00:00Z", "a:1:b", "u1", "c", "1"),
                event("2026-01-01T00:00:00Z", "a", "u2", "b:1:c", "2"),
                "{\"timestamp\":\"2026-01-01T00:00:00Z\","
                        + "\"tenant_id\":\"a\\u0000b\",\"user_id\":\"u3\","
                        + "\"type\":\":\",\"value\":3}");
        List<Group> groups = analyze(input, DEFAULTS);
        assertEquals(3, groups.size());
        assertEquals(6.0, groups.stream().mapToDouble(Group::sum).sum());
    }

    @Test
    void ordersGroupsByWindowThenTenantThenType() throws Exception {
        String input = String.join("\n",
                event("2026-01-01T00:00:00Z", "b", "u", "a", "1"),
                event("2026-01-01T00:00:00Z", "a", "u", "z", "1"),
                event("2026-01-01T00:00:00Z", "a", "u", "a", "1"));
        List<Group> groups = analyze(input, DEFAULTS);
        assertEquals(List.of("a/a", "a/z", "b/a"), groups.stream()
                .map(group -> group.tenantId() + "/" + group.type()).toList());
    }

    @Test
    void topUserNumericEqualityIncludingSignedZeroUsesUserIdTieBreak() throws Exception {
        String input = String.join("\n",
                event("2026-01-01T00:00:00Z", "t", "b", "x", "0"),
                event("2026-01-01T00:00:01Z", "t", "a", "x", "-0"));
        List<TopUser> users = analyze(input, DEFAULTS).getFirst().topUsers();
        assertEquals(List.of("a", "b"), users.stream().map(TopUser::userId).toList());
    }

    @Test
    void reportsEarliestSequentialOverflowBeforeLaterMalformedLine() {
        String input = String.join("\n",
                event("2026-01-01T00:00:00Z", "t", "u", "x", "1e308"),
                event("2026-01-01T00:00:01Z", "t", "u", "x", "1e308"),
                "{bad}");
        AnalysisException error = assertThrows(AnalysisException.class, () -> analyze(input, DEFAULTS));
        assertTrue(error.getMessage().contains("line 2"), error.getMessage());
        assertTrue(error.getMessage().contains("overflow"));
    }

    @Test
    void distinguishesGroupOverflowAcrossUsersFromPerUserOverflow() {
        String input = String.join("\n",
                event("2026-01-01T00:00:00Z", "t", "u1", "x", "1e308"),
                event("2026-01-01T00:00:01Z", "t", "u2", "x", "1e308"));
        AnalysisException error = assertThrows(AnalysisException.class, () -> analyze(input, DEFAULTS));
        assertTrue(error.getMessage().contains("line 2"), error.getMessage());
        assertTrue(error.getMessage().contains("group sum overflow"), error.getMessage());
    }

    @Test
    void rejectsWindowStartsOutsideFourDigitOutputRangeWithLineNumber() {
        String input = event("0000-01-01T00:00:00Z", "t", "u", "x", "1");
        AnalyzerConfig config = new AnalyzerConfig(
                null, null, List.of(), Duration.ofDays(1_000), 1);
        AnalysisException error = assertThrows(AnalysisException.class, () -> analyze(input, config));
        assertTrue(error.getMessage().contains("line 1"), error.getMessage());
        assertTrue(error.getMessage().contains("window"), error.getMessage());
    }

    @Test
    void validatesConfigBeforeReadingAndAppliesApiDefaults() throws Exception {
        CountingInputStream input = new CountingInputStream(new byte[0]);
        assertThrows(AnalysisException.class,
                () -> Analyzer.analyze(input, new AnalyzerConfig(null, null, null, Duration.ofNanos(-1), 1)));
        assertEquals(0, input.reads);
        assertThrows(AnalysisException.class,
                () -> Analyzer.analyze(input, new AnalyzerConfig(null, null, null, Duration.ZERO, -1)));
        assertEquals(0, input.reads);
        assertThrows(AnalysisException.class, () -> Analyzer.analyze(null, DEFAULTS));
        assertThrows(AnalysisException.class,
                () -> Analyzer.analyze(new ByteArrayInputStream(new byte[0]), null));

        String events = String.join("\n",
                event("2026-01-01T00:00:00Z", "t", "a", "x", "4"),
                event("2026-01-01T00:00:01Z", "t", "b", "x", "3"),
                event("2026-01-01T00:00:02Z", "t", "c", "x", "2"),
                event("2026-01-01T00:00:03Z", "t", "d", "x", "1"));
        Group group = analyze(events, new AnalyzerConfig(null, null, null, null, 0)).getFirst();
        assertEquals(3, group.topUsers().size());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), group.windowStart());
    }

    @Test
    void observesInterruptBeforeReadWithoutClearingStatus() {
        CountingInputStream input = new CountingInputStream(new byte[0]);
        Thread.currentThread().interrupt();
        assertThrows(InterruptedException.class, () -> Analyzer.analyze(input, DEFAULTS));
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(0, input.reads);
    }

    @Test
    void observesInterruptAfterCompletedReadWithoutClearingStatus() {
        byte[] event = event("2026-01-01T00:00:00Z", "t", "u", "x", "1")
                .getBytes(StandardCharsets.UTF_8);
        InputStream input = new ByteArrayInputStream(event) {
            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
                int count = super.read(bytes, offset, length);
                if (count > 0) Thread.currentThread().interrupt();
                return count;
            }

            @Override
            public synchronized int read() {
                int value = super.read();
                if (value >= 0) Thread.currentThread().interrupt();
                return value;
            }
        };
        assertThrows(InterruptedException.class, () -> Analyzer.analyze(input, DEFAULTS));
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void observesInterruptBeforeResultFinalizationWithoutClearingStatus() {
        byte[] bytes = (event("2026-01-01T00:00:00Z", "t", "u", "x", "1") + "\n")
                .getBytes(StandardCharsets.UTF_8);
        InputStream input = new ByteArrayInputStream(bytes) {
            private boolean interruptedAtEof;

            @Override
            public synchronized int read(byte[] output, int offset, int length) {
                int count = super.read(output, offset, length);
                if (count < 0 && !interruptedAtEof) {
                    interruptedAtEof = true;
                    Thread.currentThread().interrupt();
                }
                return count;
            }

            @Override
            public synchronized int read() {
                int value = super.read();
                if (value < 0 && !interruptedAtEof) {
                    interruptedAtEof = true;
                    Thread.currentThread().interrupt();
                }
                return value;
            }
        };
        assertThrows(InterruptedException.class, () -> Analyzer.analyze(input, DEFAULTS));
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void doesNotCloseCallerStreamAndReportsReadFailureAtNextLine() throws Exception {
        TrackingInputStream tracking = new TrackingInputStream(
                (event("2026-01-01T00:00:00Z", "t", "u", "x", "1") + "\n")
                        .getBytes(StandardCharsets.UTF_8));
        List<Group> groups = Analyzer.analyze(tracking, DEFAULTS);
        assertEquals(1, groups.size());
        assertFalse(tracking.closed);

        InputStream failure = new InputStream() {
            int reads;
            @Override public int read() throws IOException {
                if (reads++ == 0) return '\n';
                throw new IOException("boom");
            }
        };
        AnalysisException error = assertThrows(AnalysisException.class,
                () -> Analyzer.analyze(failure, DEFAULTS));
        assertTrue(error.getMessage().contains("line 2"), error.getMessage());
        assertTrue(error.getMessage().contains("boom"));
    }

    @Test
    void detectsMalformedUtf8SplitAcrossUnderlyingReads() {
        byte[] bytes = new byte[] {'\n', (byte) 0xe2, (byte) 0x82, 'x', '\n'};
        InputStream oneByteReads = new ByteArrayInputStream(bytes) {
            @Override
            public synchronized int read(byte[] output, int offset, int length) {
                return super.read(output, offset, Math.min(length, 1));
            }
        };
        AnalysisException error = assertThrows(AnalysisException.class,
                () -> Analyzer.analyze(oneByteReads, DEFAULTS));
        assertTrue(error.getMessage().contains("line 2"), error.getMessage());
        assertTrue(error.getMessage().contains("UTF-8"), error.getMessage());
    }

    @Test
    void repeatedAnalysisHasNoStateLeakageAndIsDeterministic() throws Exception {
        String input = String.join("\n",
                event("2026-01-01T00:00:00Z", "t", "a", "x", "1"),
                event("2026-01-01T00:00:01Z", "t", "b", "x", "2"));
        List<Group> expected = analyze(input, DEFAULTS);
        for (int run = 0; run < 5; run++) {
            assertEquals(expected, analyze(input, DEFAULTS));
        }
    }

    @Test
    void emptyInputReturnsNonNullEmptyList() throws Exception {
        List<Group> result = analyze("", DEFAULTS);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    private static List<Group> analyze(String input, AnalyzerConfig config)
            throws AnalysisException, InterruptedException {
        return Analyzer.analyze(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), config);
    }

    private static String event(
            String timestamp, String tenant, String user, String type, String rawValue) {
        return "{" + fields(timestamp, tenant, user, type, rawValue) + "}";
    }

    private static String fields(
            String timestamp, String tenant, String user, String type, String rawValue) {
        return "\"timestamp\":\"" + timestamp + "\","
                + "\"tenant_id\":\"" + tenant + "\","
                + "\"user_id\":\"" + user + "\","
                + "\"type\":\"" + type + "\",\"value\":" + rawValue;
    }

    private static void assertLineError(String input, int line, String message) {
        AnalysisException error = assertThrows(AnalysisException.class,
                () -> analyze(input, DEFAULTS));
        assertTrue(error.getMessage().contains("line " + line), error.getMessage());
        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    private static final class CountingInputStream extends ByteArrayInputStream {
        int reads;
        CountingInputStream(byte[] bytes) { super(bytes); }
        @Override public synchronized int read() { reads++; return super.read(); }
        @Override public synchronized int read(byte[] bytes, int offset, int length) {
            reads++;
            return super.read(bytes, offset, length);
        }
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        boolean closed;
        TrackingInputStream(byte[] bytes) { super(bytes); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
}
