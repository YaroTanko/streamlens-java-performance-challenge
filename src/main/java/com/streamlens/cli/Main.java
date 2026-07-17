package com.streamlens.cli;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.streamlens.analyzer.AnalysisException;
import com.streamlens.analyzer.Analyzer;
import com.streamlens.analyzer.Config;
import com.streamlens.analyzer.Group;
import com.streamlens.analyzer.TopUser;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Command-line entry point for StreamLens. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        int status = run(args, System.in, System.out, System.err);
        if (status != 0) {
            System.exit(status);
        }
    }

    public static int run(String[] args, InputStream stdin, PrintStream stdout, PrintStream stderr) {
        try {
            Arguments arguments = parseArguments(args);
            if (arguments.help) {
                stdout.print(usage());
                return 0;
            }
            Config config = new Config(
                    arguments.from,
                    arguments.to,
                    arguments.types,
                    arguments.window,
                    arguments.topK);
            if (arguments.inputPath == null || "-".equals(arguments.inputPath)) {
                return analyzeAndWrite(new InputStreamReader(stdin, StandardCharsets.UTF_8), config, stdout);
            }
            try (InputStream file = new FileInputStream(arguments.inputPath);
                    Reader reader = new InputStreamReader(file, StandardCharsets.UTF_8)) {
                return analyzeAndWrite(reader, config, stdout);
            }
        } catch (IllegalArgumentException | IOException | AnalysisException exception) {
            stderr.println("streamlens: " + exception.getMessage());
            return 1;
        }
    }

    private static int analyzeAndWrite(Reader input, Config config, PrintStream stdout)
            throws IOException, AnalysisException {
        List<Group> groups = Analyzer.analyze(input, config);
        JsonFactory factory = new JsonFactory();
        try (JsonGenerator json = factory.createGenerator(stdout)) {
            json.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            json.writeStartArray();
            for (Group group : groups) {
                json.writeStartObject();
                json.writeStringField("window_start", group.windowStart().toString());
                json.writeStringField("tenant_id", group.tenantId());
                json.writeStringField("type", group.type());
                json.writeNumberField("count", group.count());
                json.writeNumberField("sum", group.sum());
                json.writeNumberField("unique_users", group.uniqueUsers());
                json.writeArrayFieldStart("top_users");
                for (TopUser user : group.topUsers()) {
                    json.writeStartObject();
                    json.writeStringField("user_id", user.userId());
                    json.writeNumberField("value", user.value());
                    json.writeEndObject();
                }
                json.writeEndArray();
                json.writeEndObject();
            }
            json.writeEndArray();
            json.flush();
        }
        stdout.println();
        return 0;
    }

    private static Arguments parseArguments(String[] args) {
        Arguments parsed = new Arguments();
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            switch (argument) {
                case "--help", "-h" -> parsed.help = true;
                case "--input" -> parsed.inputPath = requireValue(args, ++i, argument);
                case "--from" -> parsed.from = parseTimestamp(requireValue(args, ++i, argument), argument);
                case "--to" -> parsed.to = parseTimestamp(requireValue(args, ++i, argument), argument);
                case "--type" -> parsed.types.add(requireValue(args, ++i, argument));
                case "--window" -> parsed.window = parseDuration(requireValue(args, ++i, argument));
                case "--top-k" -> parsed.topK = parsePositiveInt(requireValue(args, ++i, argument), argument);
                default -> throw new IllegalArgumentException("unknown argument: " + argument);
            }
        }
        return parsed;
    }

    private static String requireValue(String[] args, int index, String argument) {
        if (index >= args.length) {
            throw new IllegalArgumentException(argument + " requires a value");
        }
        return args[index];
    }

    private static Instant parseTimestamp(String value, String argument) {
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(argument + " must be an RFC3339 timestamp", exception);
        }
    }

    private static Duration parseDuration(String value) {
        try {
            return Duration.parse(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("--window must be an ISO-8601 duration such as PT1M", exception);
        }
    }

    private static int parsePositiveInt(String value, String argument) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) {
                throw new NumberFormatException("not positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(argument + " must be a positive integer", exception);
        }
    }

    private static String usage() {
        return """
                Usage: streamlens [options]
                  --input PATH   NDJSON file, or - for standard input (default: -)
                  --from TIME    inclusive RFC3339 lower bound
                  --to TIME      exclusive RFC3339 upper bound
                  --type TYPE    allow one event type; repeat for more types
                  --window ISO   positive ISO-8601 duration (default: PT1M)
                  --top-k N      number of top users (default: 3)
                  --help         show this help
                """;
    }

    private static final class Arguments {
        private String inputPath;
        private Instant from;
        private Instant to;
        private final Set<String> types = new LinkedHashSet<>();
        private Duration window;
        private int topK;
        private boolean help;
    }
}
