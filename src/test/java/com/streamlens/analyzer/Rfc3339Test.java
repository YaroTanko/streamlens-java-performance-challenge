package com.streamlens.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.junit.jupiter.api.Test;

final class Rfc3339Test {
    @Test
    void parsesFourDigitYearsOffsetsAndFractions() {
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), Rfc3339.parse("2026-01-01T23:59:00+23:59"));
        assertEquals(Instant.parse("2026-01-01T00:00:00.1Z"), Rfc3339.parse("2026-01-01T00:00:00.100000000Z"));
        assertEquals(Instant.parse("0000-01-01T00:00:00Z"), Rfc3339.parse("0000-01-01T00:00:00Z"));
        assertEquals(Instant.parse("9999-12-31T23:59:59.999999999Z"),
                Rfc3339.parse("9999-12-31T23:59:59.999999999Z"));
    }

    @Test
    void rejectsNonContractForms() {
        for (String text : List.of(
                "2026-1-01T00:00:00Z", "2026-01-01t00:00:00Z", "2026-01-01T00:00:00z",
                "2026-01-01T00:00:00+24:00", "2026-01-01T00:00:00+00:60",
                "2026-02-29T00:00:00Z", "+2026-01-01T00:00:00Z")) {
            assertThrows(DateTimeParseException.class, () -> Rfc3339.parse(text), text);
        }
    }

    @Test
    void formatsCanonicalUtcText() {
        assertEquals("2026-01-01T00:00:00Z", Rfc3339.format(Instant.parse("2026-01-01T00:00:00Z")));
        assertEquals("2026-01-01T00:00:00.1234Z",
                Rfc3339.format(Instant.parse("2026-01-01T00:00:00.123400000Z")));
        assertThrows(DateTimeException.class, () -> Rfc3339.format(Instant.MAX));
    }
}
