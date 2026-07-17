package com.streamlens.analyzer;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict parsing and canonical UTC formatting for the assessment timestamp contract. */
public final class Rfc3339 {
    private static final Pattern TIMESTAMP = Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})"
                    + "(?:\\.(\\d{1,9}))?(Z|([+-])(\\d{2}):(\\d{2}))$");

    private Rfc3339() {}

    /**
     * Parses {@code YYYY-MM-DDTHH:mm:ss[.fffffffff](Z|+HH:mm|-HH:mm)}.
     * Offset hours through 23 are accepted as required by the RFC 3339 grammar.
     */
    public static Instant parse(String text) {
        if (text == null) {
            throw new DateTimeParseException("timestamp must not be null", "null", 0);
        }

        Matcher matcher = TIMESTAMP.matcher(text);
        if (!matcher.matches()) {
            throw new DateTimeParseException(
                    "timestamp must be RFC3339 with an explicit offset", text, 0);
        }

        try {
            int year = integer(matcher, 1);
            int month = integer(matcher, 2);
            int day = integer(matcher, 3);
            int hour = integer(matcher, 4);
            int minute = integer(matcher, 5);
            int second = integer(matcher, 6);
            int nanos = fractionNanos(matcher.group(7));

            LocalDateTime local = LocalDateTime.of(
                    year, month, day, hour, minute, second, nanos);
            long epochSecond = local.toEpochSecond(ZoneOffset.UTC);
            if (!"Z".equals(matcher.group(8))) {
                int offsetHour = integer(matcher, 10);
                int offsetMinute = integer(matcher, 11);
                if (offsetHour > 23 || offsetMinute > 59) {
                    throw new DateTimeException("offset is outside the RFC3339 range");
                }
                long offsetSeconds = offsetHour * 3_600L + offsetMinute * 60L;
                if ("-".equals(matcher.group(9))) {
                    offsetSeconds = -offsetSeconds;
                }
                epochSecond = Math.subtractExact(epochSecond, offsetSeconds);
            }
            return Instant.ofEpochSecond(epochSecond, nanos);
        } catch (DateTimeException | ArithmeticException failure) {
            throw new DateTimeParseException(
                    "invalid RFC3339 timestamp: " + failure.getMessage(), text, 0, failure);
        }
    }

    /** Formats an instant in UTC with seconds and only significant fractional digits. */
    public static String format(Instant instant) {
        LocalDateTime utc = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        int year = utc.getYear();
        if (year < 0 || year > 9_999) {
            throw new DateTimeException("timestamp year is outside the four-digit RFC3339 range");
        }

        StringBuilder output = new StringBuilder(30);
        appendPadded(output, year, 4);
        output.append('-');
        appendPadded(output, utc.getMonthValue(), 2);
        output.append('-');
        appendPadded(output, utc.getDayOfMonth(), 2);
        output.append('T');
        appendPadded(output, utc.getHour(), 2);
        output.append(':');
        appendPadded(output, utc.getMinute(), 2);
        output.append(':');
        appendPadded(output, utc.getSecond(), 2);

        int nanos = utc.getNano();
        if (nanos != 0) {
            String fraction = String.format(java.util.Locale.ROOT, "%09d", nanos);
            int end = fraction.length();
            while (end > 0 && fraction.charAt(end - 1) == '0') {
                end--;
            }
            output.append('.').append(fraction, 0, end);
        }
        return output.append('Z').toString();
    }

    private static int integer(Matcher matcher, int group) {
        return Integer.parseInt(matcher.group(group));
    }

    private static int fractionNanos(String fraction) {
        if (fraction == null) {
            return 0;
        }
        int value = Integer.parseInt(fraction);
        for (int index = fraction.length(); index < 9; index++) {
            value *= 10;
        }
        return value;
    }

    private static void appendPadded(StringBuilder output, int value, int width) {
        String text = Integer.toString(value);
        output.append("0".repeat(width - text.length())).append(text);
    }
}
