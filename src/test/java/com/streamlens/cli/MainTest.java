package com.streamlens.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

final class MainTest {
    @Test
    void writesCanonicalJson() {
        String input = """
                {"timestamp":"2026-01-15T12:34:56Z","tenant_id":"acme","user_id":"u","type":"purchase","value":2}
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int status = Main.run(
                new String[0],
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8));

        assertEquals(0, status);
        assertEquals("[{\"window_start\":\"2026-01-15T12:34:00Z\",\"tenant_id\":\"acme\","
                + "\"type\":\"purchase\",\"count\":1,\"sum\":2.0,\"unique_users\":1,"
                + "\"top_users\":[{\"user_id\":\"u\",\"value\":2.0}]}]\n", output.toString(StandardCharsets.UTF_8));
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
    }

    @Test
    void reportsLineNumberAndReturnsFailure() {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int status = Main.run(
                new String[0],
                new ByteArrayInputStream("bad\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(errors, true, StandardCharsets.UTF_8));

        assertEquals(1, status);
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("line 1:"));
    }
}
