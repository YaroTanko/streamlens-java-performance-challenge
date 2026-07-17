package com.streamlens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StreamLensTest {
    @TempDir Path temporaryDirectory;

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void writesDeterministicJsonFromStandardInput() {
        String input = String.join("\n",
                event("2026-01-15T12:34:56.123Z", "a\tb", "u\"2", "purchase", "19.95"),
                event("2026-01-15T12:34:57Z", "a\tb", "u1", "purchase", "10.05"));
        Result result = run(new String[0], input);
        assertEquals(0, result.code);
        assertEquals("[{\"window_start\":\"2026-01-15T12:34:00Z\","
                + "\"tenant_id\":\"a\\tb\",\"type\":\"purchase\",\"count\":2,"
                + "\"sum\":30.0,\"unique_users\":2,\"top_users\":["
                + "{\"user_id\":\"u\\\"2\",\"value\":19.95},"
                + "{\"user_id\":\"u1\",\"value\":10.05}]}]"
                + "\n", result.stdout);
        assertEquals("", result.stderr);
    }

    @Test
    void writesCanonicalEmptyArray() {
        Result result = run(new String[0], "\n\t\n");
        assertEquals(0, result.code);
        assertEquals("[]\n", result.stdout);
        assertEquals("", result.stderr);
    }

    @Test
    void appliesEveryFlagIncludingEqualsAndSingleDashForms() {
        String input = String.join("\n",
                event("2026-01-01T00:00:00Z", "t", "a", "keep", "1"),
                event("2026-01-01T00:00:00.000000006Z", "t", "b", "keep", "2"),
                event("2026-01-01T00:00:00.000000007Z", "t", "c", "drop", "9"));
        Result result = run(new String[] {
                "-from=2026-01-01T00:00:00Z",
                "--to", "2026-01-01T00:00:00.000000007Z",
                "--types= drop , keep ", "--window", "10ns", "--top-k=1"
        }, input);
        assertEquals(0, result.code, result.stderr);
        assertTrue(result.stdout.contains("\"count\":2"));
        assertTrue(result.stdout.contains("\"user_id\":\"b\""));
        assertFalse(result.stdout.contains("\"user_id\":\"a\""));
    }

    @Test
    void readsNamedInputFileAndLeavesStdoutClean() throws IOException {
        Path input = temporaryDirectory.resolve("events.ndjson");
        Files.writeString(input,
                event("2026-01-01T00:00:00Z", "t", "u", "x", "1"),
                StandardCharsets.UTF_8);
        Result result = run(new String[] {"--input", input.toString()}, "ignored");
        assertEquals(0, result.code, result.stderr);
        assertTrue(result.stdout.startsWith("[{"));
        assertEquals("", result.stderr);
    }

    @Test
    void helpUsesStdoutAndDoesNotReadInput() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = StreamLens.run(new String[] {"--help"}, new ByteArrayInputStream(new byte[0]) {
            @Override public synchronized int read() { throw new AssertionError("input read"); }
        }, stdout, stderr);
        assertEquals(0, code);
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("Usage: streamlens"));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsInvalidOptionsWithoutWritingJson() {
        String[][] arguments = {
                {"--unknown", "x"}, {"positional"}, {"--from"},
                {"--from", "2026-01-01T00:00:00"},
                {"--window", "0"}, {"--window", "0s"}, {"--window", "1.1ns"},
                {"--window", "nonsense"},
                {"--window", "P999999999999999999999999999999999999999999D"},
                {"--top-k", "0"}, {"--top-k", "-1"},
                {"--top-k", "nope"}
        };
        for (String[] args : arguments) {
            Result result = run(args, "");
            assertEquals(2, result.code, String.join(" ", args));
            assertEquals("", result.stdout, String.join(" ", args));
            assertTrue(result.stderr.startsWith("streamlens: "), String.join(" ", args));
        }
    }

    @Test
    void acceptsIsoAndCompositeNanosecondExactDurations() {
        String input = event("2026-01-01T00:00:00Z", "t", "u", "x", "1");
        for (String duration : new String[] {
                "PT1M", "+PT1S", "1h2m3.004005006s", "1us", "1µs", "1μs"
        }) {
            Result result = run(new String[] {"--window", duration}, input);
            assertEquals(0, result.code, duration + ": " + result.stderr);
        }
    }

    @Test
    void analysisAndOpenFailuresUseStderrAndExitOne() {
        Result malformed = run(new String[0], "{bad}");
        assertEquals(1, malformed.code);
        assertEquals("", malformed.stdout);
        assertTrue(malformed.stderr.contains("line 1"));

        Result missing = run(new String[] {"--input", temporaryDirectory.resolve("missing").toString()}, "");
        assertEquals(1, missing.code);
        assertEquals("", missing.stdout);
        assertTrue(missing.stderr.contains("open input"));
    }

    @Test
    void outputFailureReturnsOneWithoutWritingDiagnosticToStdout() {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        OutputStream broken = new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("closed"); }
        };
        int code = StreamLens.run(new String[0], new ByteArrayInputStream(new byte[0]), broken, stderr);
        assertEquals(1, code);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("write output"));
    }

    @Test
    void interruptionReturnsOneAndPreservesStatus() {
        Thread.currentThread().interrupt();
        Result result = run(new String[0], "");
        assertEquals(1, result.code);
        assertEquals("", result.stdout);
        assertTrue(result.stderr.contains("interrupted"));
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void nullArgumentArraySelectsDefaults() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = StreamLens.run(null, new ByteArrayInputStream(new byte[0]), stdout, stderr);
        assertEquals(0, code);
        assertEquals("[]\n", stdout.toString(StandardCharsets.UTF_8));
    }

    private static Result run(String[] args, String input) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = StreamLens.run(
                args,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                stdout,
                stderr);
        return new Result(code, stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private static String event(
            String timestamp, String tenant, String user, String type, String value) {
        return "{\"timestamp\":\"" + timestamp + "\","
                + "\"tenant_id\":\"" + json(tenant) + "\","
                + "\"user_id\":\"" + json(user) + "\","
                + "\"type\":\"" + json(type) + "\",\"value\":" + value + "}";
    }

    private static String json(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\t", "\\t");
    }

    private record Result(int code, String stdout, String stderr) {}
}
