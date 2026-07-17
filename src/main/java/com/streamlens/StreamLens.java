package com.streamlens;

import com.streamlens.analyzer.AnalysisException;
import com.streamlens.analyzer.Analyzer;
import com.streamlens.analyzer.AnalyzerConfig;
import com.streamlens.analyzer.Group;
import com.streamlens.analyzer.Rfc3339;
import com.streamlens.analyzer.TopUser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Command-line entry point for StreamLens. */
public final class StreamLens {
    private static final Pattern DURATION_PART = Pattern.compile(
            "((?:(?:\\d+(?:\\.\\d*)?)|(?:\\.\\d+)))"
                    + "(ns|us|µs|μs|ms|s|m|h)");
    private static final BigInteger BILLION = BigInteger.valueOf(1_000_000_000L);

    private StreamLens() {}

    public static void main(String[] args) {
        int exitCode = run(args, System.in, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /** Runs the CLI without terminating the current JVM. */
    public static int run(
            String[] args,
            InputStream stdin,
            OutputStream stdout,
            OutputStream stderr) {
        Options options;
        try {
            options = parseOptions(args == null ? new String[0] : args);
        } catch (CliException failure) {
            diagnostic(stderr, "streamlens: " + failure.getMessage());
            return 2;
        }
        if (options.help) {
            try {
                writeText(stdout, usage());
                return 0;
            } catch (IOException failure) {
                diagnostic(stderr, "streamlens: write output: " + failure.getMessage());
                return 1;
            }
        }

        InputStream input = stdin;
        boolean closeInput = false;
        if (options.inputPath != null
                && !options.inputPath.isEmpty()
                && !"-".equals(options.inputPath)) {
            try {
                input = Files.newInputStream(Path.of(options.inputPath));
                closeInput = true;
            } catch (IOException failure) {
                diagnostic(stderr, "streamlens: open input: " + failure.getMessage());
                return 1;
            }
        }

        try {
            List<Group> groups = Analyzer.analyze(input, options.config);
            writeText(stdout, ResultJson.encode(groups) + "\n");
            return 0;
        } catch (AnalysisException failure) {
            diagnostic(stderr, "streamlens: analyze input: " + failure.getMessage());
            return 1;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            diagnostic(stderr, "streamlens: analyze input: interrupted");
            return 1;
        } catch (IOException failure) {
            diagnostic(stderr, "streamlens: write output: " + failure.getMessage());
            return 1;
        } catch (DateTimeException failure) {
            diagnostic(stderr, "streamlens: write output: " + failure.getMessage());
            return 1;
        } finally {
            if (closeInput) {
                try {
                    input.close();
                } catch (IOException ignored) {
                    // Input has already been consumed; match the CLI contract's best-effort close.
                }
            }
        }
    }

    private static Options parseOptions(String[] args) throws CliException {
        String inputPath = null;
        String fromText = null;
        String toText = null;
        String typesText = null;
        String windowText = null;
        String topKText = null;

        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if ("--help".equals(argument) || "-h".equals(argument)) {
                return new Options(true, null, new AnalyzerConfig());
            }
            if (!argument.startsWith("-")) {
                throw new CliException("unexpected positional argument: " + argument);
            }

            int equals = argument.indexOf('=');
            String name = equals < 0 ? argument : argument.substring(0, equals);
            String value;
            if (equals >= 0) {
                value = argument.substring(equals + 1);
            } else {
                if (index + 1 >= args.length) {
                    throw new CliException("missing value for " + name);
                }
                value = args[++index];
            }

            switch (normalizeFlag(name)) {
                case "input" -> inputPath = value;
                case "from" -> fromText = value;
                case "to" -> toText = value;
                case "types" -> typesText = value;
                case "window" -> windowText = value;
                case "top-k" -> topKText = value;
                default -> throw new CliException("unknown option: " + name);
            }
        }

        Instant from = parseOptionalTimestamp("from", fromText);
        Instant to = parseOptionalTimestamp("to", toText);
        List<String> types = parseTypes(typesText);

        Duration window = Duration.ZERO;
        if (windowText != null) {
            window = parseDuration(windowText);
            if (window.isZero() || window.isNegative()) {
                throw new CliException("window must be positive");
            }
        }

        int topK = 0;
        if (topKText != null) {
            try {
                topK = Integer.parseInt(topKText);
            } catch (NumberFormatException failure) {
                throw new CliException("top-k must be a positive integer", failure);
            }
            if (topK <= 0) {
                throw new CliException("top-k must be positive");
            }
        }

        return new Options(
                false,
                inputPath,
                new AnalyzerConfig(from, to, types, window, topK));
    }

    private static String normalizeFlag(String flag) {
        if (flag.startsWith("--")) {
            return flag.substring(2);
        }
        return flag.startsWith("-") ? flag.substring(1) : flag;
    }

    private static Instant parseOptionalTimestamp(String name, String text) throws CliException {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return Rfc3339.parse(text);
        } catch (DateTimeParseException failure) {
            throw new CliException(
                    name + " must be an RFC3339 timestamp with an explicit offset", failure);
        }
    }

    private static List<String> parseTypes(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : text.split(",", -1)) {
            String type = stripContractWhitespace(part);
            if (!type.isEmpty()) {
                result.add(type);
            }
        }
        return result;
    }

    private static Duration parseDuration(String text) throws CliException {
        if ("0".equals(text) || "+0".equals(text) || "-0".equals(text)) {
            return Duration.ZERO;
        }
        if (text.startsWith("P") || text.startsWith("+P") || text.startsWith("-P")) {
            try {
                return Duration.parse(text);
            } catch (DateTimeException | ArithmeticException failure) {
                throw new CliException("window must be a valid duration", failure);
            }
        }

        int sign = 1;
        String magnitude = text;
        if (!text.isEmpty() && (text.charAt(0) == '+' || text.charAt(0) == '-')) {
            if (text.charAt(0) == '-') {
                sign = -1;
            }
            magnitude = text.substring(1);
        }

        Matcher matcher = DURATION_PART.matcher(magnitude);
        BigInteger totalNanos = BigInteger.ZERO;
        int position = 0;
        try {
            while (position < magnitude.length()) {
                matcher.region(position, magnitude.length());
                if (!matcher.lookingAt()) {
                    throw new CliException("window must be a valid duration");
                }
                BigDecimal amount = new BigDecimal(matcher.group(1));
                BigDecimal unit = new BigDecimal(unitNanos(matcher.group(2)));
                totalNanos = totalNanos.add(amount.multiply(unit).toBigIntegerExact());
                position = matcher.end();
            }
            if (position == 0) {
                throw new CliException("window must be a valid duration");
            }
            if (sign < 0) {
                totalNanos = totalNanos.negate();
            }

            BigInteger[] secondsAndNanos = totalNanos.divideAndRemainder(BILLION);
            BigInteger seconds = secondsAndNanos[0];
            BigInteger nanos = secondsAndNanos[1];
            if (nanos.signum() < 0) {
                seconds = seconds.subtract(BigInteger.ONE);
                nanos = nanos.add(BILLION);
            }
            return Duration.ofSeconds(seconds.longValueExact(), nanos.longValueExact());
        } catch (ArithmeticException | NumberFormatException failure) {
            throw new CliException("window must be a valid nanosecond-precision duration", failure);
        }
    }

    private static long unitNanos(String unit) {
        return switch (unit) {
            case "ns" -> 1L;
            case "us", "µs", "μs" -> 1_000L;
            case "ms" -> 1_000_000L;
            case "s" -> 1_000_000_000L;
            case "m" -> 60_000_000_000L;
            case "h" -> 3_600_000_000_000L;
            default -> throw new IllegalArgumentException("unknown duration unit");
        };
    }

    private static String stripContractWhitespace(String value) {
        int start = 0;
        while (start < value.length()) {
            int codePoint = value.codePointAt(start);
            if (!isContractWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        int end = value.length();
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!isContractWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private static boolean isContractWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)
                || codePoint == 0x0085;
    }

    private static void diagnostic(OutputStream output, String text) {
        try {
            writeText(output, text + "\n");
        } catch (IOException ignored) {
            // A diagnostic write failure cannot be reported through the same stream.
        }
    }

    private static void writeText(OutputStream output, String text) throws IOException {
        output.write(text.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static String usage() {
        return """
                Usage: streamlens [options]
                  --input PATH       NDJSON file; omit or use - for standard input
                  --from TIMESTAMP   inclusive RFC3339 lower bound
                  --to TIMESTAMP     exclusive RFC3339 upper bound
                  --types A,B        comma-separated event-type allow-list
                  --window DURATION  positive fixed window (default: 1m)
                  --top-k N          positive top-user count (default: 3)
                  -h, --help         show this help
                """;
    }

    private record Options(boolean help, String inputPath, AnalyzerConfig config) {}

    private static final class CliException extends Exception {
        private static final long serialVersionUID = 1L;

        private CliException(String message) {
            super(message);
        }

        private CliException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class ResultJson {
        private ResultJson() {}

        private static String encode(List<Group> groups) {
            StringBuilder output = new StringBuilder(Math.max(2, groups.size() * 160));
            output.append('[');
            for (int index = 0; index < groups.size(); index++) {
                if (index != 0) {
                    output.append(',');
                }
                appendGroup(output, groups.get(index));
            }
            return output.append(']').toString();
        }

        private static void appendGroup(StringBuilder output, Group group) {
            output.append("{\"window_start\":");
            appendString(output, Rfc3339.format(group.windowStart()));
            output.append(",\"tenant_id\":");
            appendString(output, group.tenantId());
            output.append(",\"type\":");
            appendString(output, group.type());
            output.append(",\"count\":").append(group.count());
            output.append(",\"sum\":").append(Double.toString(group.sum()));
            output.append(",\"unique_users\":").append(group.uniqueUsers());
            output.append(",\"top_users\":[");
            for (int index = 0; index < group.topUsers().size(); index++) {
                if (index != 0) {
                    output.append(',');
                }
                TopUser user = group.topUsers().get(index);
                output.append("{\"user_id\":");
                appendString(output, user.userId());
                output.append(",\"value\":").append(Double.toString(user.value())).append('}');
            }
            output.append("]}");
        }

        private static void appendString(StringBuilder output, String value) {
            output.append('"');
            for (int offset = 0; offset < value.length(); offset++) {
                char character = value.charAt(offset);
                switch (character) {
                    case '"' -> output.append("\\\"");
                    case '\\' -> output.append("\\\\");
                    case '\b' -> output.append("\\b");
                    case '\f' -> output.append("\\f");
                    case '\n' -> output.append("\\n");
                    case '\r' -> output.append("\\r");
                    case '\t' -> output.append("\\t");
                    default -> {
                        if (character < 0x20) {
                            output.append(String.format(
                                    java.util.Locale.ROOT, "\\u%04x", (int) character));
                        } else {
                            output.append(character);
                        }
                    }
                }
            }
            output.append('"');
        }
    }
}
