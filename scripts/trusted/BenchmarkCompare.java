import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Trusted comparator for framed JMH JSON samples. */
public final class BenchmarkCompare {
    private static final List<String> SCENARIOS = List.of(
            "Balanced", "HighCardinality", "MostlyFiltered");
    private static final List<String> METRICS = List.of("ns/op", "B/op");
    private static final Set<String> FIXTURE_PARAMETERS = Set.of(
            "fixtureSeed", "fixtureExpected");
    private static final Pattern ENTROPY = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SAMPLE = Pattern.compile(
            "^@@STREAMLENS_JAVA_SAMPLE (BEGIN|END) ([1-9][0-9]*) ([0-9a-f]{64})$");
    private static final Pattern RESULT = Pattern.compile(
            "^@@STREAMLENS_JAVA_BENCHMARK_RESULT ([0-9a-f]{64})$");
    private static final double EPSILON = 1e-9;
    private static final long MAX_INPUT_BYTES = 16L * 1024L * 1024L;

    private BenchmarkCompare() {}

    public static void main(String[] args) {
        try {
            Arguments parsed = Arguments.parse(args);
            Comparison comparison = compare(
                    parseSamples(parsed.baseline), parseSamples(parsed.candidate), parsed.minimumSamples);
            String report = comparison.markdown();
            System.out.print(report);
            if (parsed.output != null) {
                Files.writeString(parsed.output, report, StandardCharsets.UTF_8);
            }
            System.exit(comparison.passed ? 0 : 1);
        } catch (Exception error) {
            String report = "# Benchmark comparison\n\n❌ Comparison failed: " + error.getMessage() + "\n";
            System.err.print(report);
            try {
                Arguments parsed = Arguments.parse(args);
                if (parsed.output != null) {
                    Files.writeString(parsed.output, report, StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {
                // The command-line error was already reported.
            }
            System.exit(2);
        }
    }

    static Samples parseSamples(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("sample input must be a regular non-symbolic-link file");
        }
        long size = Files.size(path);
        if (size <= 0 || size > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("sample input must be 1-" + MAX_INPUT_BYTES + " bytes");
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        Samples samples = new Samples();
        boolean inside = false;
        int expected = 1;
        int active = 0;
        String framingToken = null;
        String resultToken = null;
        StringBuilder json = new StringBuilder();
        for (String line : lines) {
            Matcher marker = SAMPLE.matcher(line);
            if (marker.matches()) {
                int number = Integer.parseInt(marker.group(2));
                if (framingToken == null) {
                    framingToken = marker.group(3);
                } else if (!framingToken.equals(marker.group(3))) {
                    throw new IllegalArgumentException("sample framing token mismatch");
                }
                if (marker.group(1).equals("BEGIN")) {
                    if (inside || number != expected) {
                        throw new IllegalArgumentException("out-of-order sample " + number);
                    }
                    inside = true;
                    active = number;
                    resultToken = null;
                    json.setLength(0);
                } else {
                    if (!inside || number != active || resultToken == null || json.length() == 0) {
                        throw new IllegalArgumentException("incomplete sample " + number);
                    }
                    if (!framingToken.equals(resultToken)) {
                        throw new IllegalArgumentException("benchmark result token does not match sample framing");
                    }
                    samples.add(parseJmhJson(json.toString(), number));
                    inside = false;
                    expected++;
                }
                continue;
            }
            if (!inside) {
                throw new IllegalArgumentException("output outside a sample");
            }
            Matcher result = RESULT.matcher(line);
            if (result.matches()) {
                if (resultToken != null || json.length() != 0) {
                    throw new IllegalArgumentException("duplicate benchmark result marker");
                }
                resultToken = result.group(1);
                continue;
            }
            if (resultToken == null) {
                throw new IllegalArgumentException("unframed output before benchmark result");
            }
            json.append(line).append('\n');
        }
        if (inside || samples.values.isEmpty()) {
            throw new IllegalArgumentException("missing sample trailer or no samples");
        }
        return samples;
    }

    @SuppressWarnings("unchecked")
    private static ParsedSample parseJmhJson(String text, int sample) {
        Object value = new JsonParser(text).parse();
        if (!(value instanceof List<?> rows) || rows.size() != SCENARIOS.size()) {
            throw new IllegalArgumentException("sample " + sample + " must contain exactly three JMH rows");
        }
        Map<String, Map<String, Double>> result = new LinkedHashMap<>();
        Fixture fixture = null;
        for (Object item : rows) {
            Map<String, Object> row = object(item, "JMH row");
            String benchmark = string(row.get("benchmark"), "benchmark");
            String scenario = scenarioName(benchmark);
            if (result.containsKey(scenario)) {
                throw new IllegalArgumentException("duplicate scenario in sample " + sample + ": " + scenario);
            }
            if (!"avgt".equals(string(row.get("mode"), "mode"))) {
                throw new IllegalArgumentException("unexpected JMH mode for " + scenario);
            }
            Map<String, Object> parameters = object(row.get("params"), "params");
            if (!parameters.keySet().equals(FIXTURE_PARAMETERS)) {
                throw new IllegalArgumentException("unexpected fixture parameter set for " + scenario);
            }
            Fixture rowFixture = Fixture.parse(
                    string(parameters.get("fixtureSeed"), "fixtureSeed"),
                    string(parameters.get("fixtureExpected"), "fixtureExpected"));
            if (fixture == null) {
                fixture = rowFixture;
            } else if (!fixture.equals(rowFixture)) {
                throw new IllegalArgumentException("fixture parameters differ within sample " + sample);
            }
            Map<String, Object> primary = object(row.get("primaryMetric"), "primaryMetric");
            requireUnit(primary, "ns/op", scenario);
            double time = positive(number(primary.get("score"), "primary score"), "ns/op", scenario);
            Map<String, Object> secondary = object(row.get("secondaryMetrics"), "secondaryMetrics");
            Map<String, Object> allocation = object(
                    secondary.get("gc.alloc.rate.norm"), "gc.alloc.rate.norm");
            requireUnit(allocation, "B/op", scenario);
            double bytes = nonNegative(number(allocation.get("score"), "allocation score"), "B/op", scenario);
            result.put(scenario, Map.of("ns/op", time, "B/op", bytes));
        }
        if (!result.keySet().equals(Set.copyOf(SCENARIOS))) {
            throw new IllegalArgumentException("sample " + sample + " scenario set differs");
        }
        if (fixture == null) throw new IllegalArgumentException("sample has no fixture contract");
        return new ParsedSample(result, fixture);
    }

    private static Comparison compare(Samples baseline, Samples candidate, int minimum) {
        if (minimum < 1 || baseline.values.size() < minimum || candidate.values.size() < minimum) {
            throw new IllegalArgumentException("each side needs at least " + minimum + " samples");
        }
        if (baseline.values.size() != candidate.values.size()) {
            throw new IllegalArgumentException("baseline and candidate sample counts differ");
        }
        if (!baseline.fixture.equals(candidate.fixture)) {
            throw new IllegalArgumentException("baseline and candidate fixture contracts differ");
        }
        Map<String, Map<String, Double>> baseMedian = baseline.medians();
        Map<String, Map<String, Double>> candidateMedian = candidate.medians();
        for (String scenario : SCENARIOS) {
            for (String metric : METRICS) {
                if (baseMedian.get(scenario).get(metric) <= 0.0) {
                    throw new IllegalArgumentException(
                            "baseline " + scenario + " " + metric + " must be positive");
                }
            }
        }
        Comparison result = new Comparison(baseMedian, candidateMedian);
        int bestTier = 0;
        boolean reachedTarget = false;
        for (String scenario : SCENARIOS) {
            for (String metric : METRICS) {
                double improvement = improvement(
                        baseMedian.get(scenario).get(metric), candidateMedian.get(scenario).get(metric));
                result.improvements.get(scenario).put(metric, improvement);
                if (improvement < -30.0 - EPSILON) {
                    result.passed = false;
                    result.reasons.add(scenario + " " + metric + " regressed by "
                            + format(-improvement) + "%, exceeding the 30% per-scenario limit");
                }
            }
        }
        for (String metric : METRICS) {
            double logSum = 0;
            for (String scenario : SCENARIOS) {
                logSum += Math.log(candidateMedian.get(scenario).get(metric)
                        / baseMedian.get(scenario).get(metric));
            }
            double improvement = (1.0 - Math.exp(logSum / SCENARIOS.size())) * 100.0;
            result.aggregate.put(metric, improvement);
            int tier = tier(improvement);
            result.metricTiers.put(metric, tierName(tier));
            bestTier = Math.max(bestTier, tier);
            reachedTarget |= improvement >= 20.0 - EPSILON;
            if (improvement < -20.0 - EPSILON) {
                result.passed = false;
                result.reasons.add(metric + " regressed by " + format(-improvement)
                        + "%, exceeding the 20% aggregate limit");
            }
        }
        result.overallTier = tierName(bestTier);
        if (!reachedTarget) {
            result.passed = false;
            result.reasons.add("no aggregate metric improved by at least 20%");
        }
        if (result.passed) {
            result.reasons.add("at least one aggregate metric improved by 20% or more; regression guards passed");
        }
        return result;
    }

    private static String scenarioName(String benchmark) {
        for (String scenario : SCENARIOS) {
            if (benchmark.equals("com.streamlens.assessment.AnalyzerBenchmark." + scenario)) {
                return scenario;
            }
        }
        throw new IllegalArgumentException("unexpected benchmark: " + benchmark);
    }

    private static void requireUnit(Map<String, Object> metric, String unit, String scenario) {
        if (!unit.equals(string(metric.get("scoreUnit"), "scoreUnit"))) {
            throw new IllegalArgumentException("unexpected " + unit + " unit for " + scenario);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String label) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        return (Map<String, Object>) map;
    }

    private static String string(Object value, String label) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(label + " must be a string");
        }
        return text;
    }

    private static double number(Object value, String label) {
        if (!(value instanceof Double number) || !Double.isFinite(number)) {
            throw new IllegalArgumentException(label + " must be a finite number");
        }
        return number;
    }

    private static double positive(double value, String metric, String scenario) {
        if (value <= 0) throw new IllegalArgumentException(scenario + " " + metric + " must be positive");
        return value;
    }

    private static double nonNegative(double value, String metric, String scenario) {
        if (value < 0) throw new IllegalArgumentException(scenario + " " + metric + " must be non-negative");
        return value;
    }

    private static double improvement(double baseline, double candidate) {
        return (baseline - candidate) / baseline * 100.0;
    }

    private static int tier(double value) {
        if (value >= 75.0 - EPSILON) return 3;
        if (value >= 50.0 - EPSILON) return 2;
        if (value >= 20.0 - EPSILON) return 1;
        return 0;
    }

    private static String tierName(int tier) {
        return switch (tier) {
            case 1 -> "Middle";
            case 2 -> "Senior";
            case 3 -> "Staff";
            default -> "Below target";
        };
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static final class Samples {
        final List<Map<String, Map<String, Double>>> values = new ArrayList<>();
        Fixture fixture;

        void add(ParsedSample value) {
            if (fixture == null) {
                fixture = value.fixture;
            } else if (!fixture.equals(value.fixture)) {
                throw new IllegalArgumentException("fixture parameters differ across samples");
            }
            values.add(value.values);
        }

        Map<String, Map<String, Double>> medians() {
            Map<String, Map<String, Double>> result = new LinkedHashMap<>();
            for (String scenario : SCENARIOS) {
                Map<String, Double> metrics = new LinkedHashMap<>();
                for (String metric : METRICS) {
                    List<Double> numbers = new ArrayList<>();
                    for (Map<String, Map<String, Double>> sample : values) {
                        numbers.add(sample.get(scenario).get(metric));
                    }
                    numbers.sort(Comparator.naturalOrder());
                    int middle = numbers.size() / 2;
                    double median = numbers.size() % 2 == 1 ? numbers.get(middle)
                            : (numbers.get(middle - 1) + numbers.get(middle)) / 2.0;
                    metrics.put(metric, median);
                }
                result.put(scenario, metrics);
            }
            return result;
        }
    }

    private record ParsedSample(Map<String, Map<String, Double>> values, Fixture fixture) {}

    private record Fixture(String seed, String expected) {
        static Fixture parse(String seed, String expected) {
            if (!ENTROPY.matcher(seed).matches()) {
                throw new IllegalArgumentException("authoritative fixture seed must be 256-bit lowercase hex");
            }
            String digest = "[0-9a-f]{64}";
            if (!expected.matches("streamlens-java-oracle-v3:" + seed + ':'
                    + digest + ':' + digest + ':' + digest + ':' + digest)) {
                throw new IllegalArgumentException("malformed authenticated fixture record");
            }
            return new Fixture(seed, expected);
        }
    }

    private static final class Comparison {
        final Map<String, Map<String, Double>> baseline;
        final Map<String, Map<String, Double>> candidate;
        final Map<String, Map<String, Double>> improvements = new LinkedHashMap<>();
        final Map<String, Double> aggregate = new LinkedHashMap<>();
        final Map<String, String> metricTiers = new LinkedHashMap<>();
        final List<String> reasons = new ArrayList<>();
        boolean passed = true;
        String overallTier = "Below target";

        Comparison(Map<String, Map<String, Double>> baseline,
                   Map<String, Map<String, Double>> candidate) {
            this.baseline = baseline;
            this.candidate = candidate;
            for (String scenario : SCENARIOS) improvements.put(scenario, new LinkedHashMap<>());
        }

        String markdown() {
            StringBuilder out = new StringBuilder("# Benchmark comparison\n\n");
            out.append("Result: ").append(passed ? "✅ passed" : "❌ below target or regression guard failed")
                    .append("\n\nOverall level: **").append(overallTier).append("**\n\n")
                    .append("| Scenario | Metric | Baseline median | Candidate median | Improvement |\n")
                    .append("| --- | ---: | ---: | ---: | ---: |\n");
            for (String scenario : SCENARIOS) {
                for (String metric : METRICS) {
                    out.append("| ").append(scenario).append(" | ").append(metric).append(" | ")
                            .append(format(baseline.get(scenario).get(metric))).append(" | ")
                            .append(format(candidate.get(scenario).get(metric))).append(" | ")
                            .append(String.format(Locale.ROOT, "%+.2f%%", improvements.get(scenario).get(metric)))
                            .append(" |\n");
                }
            }
            out.append("\n| Aggregate metric | Geometric-mean improvement | Level |\n")
                    .append("| --- | ---: | --- |\n");
            for (String metric : METRICS) {
                out.append("| ").append(metric).append(" | ")
                        .append(String.format(Locale.ROOT, "%+.2f%%", aggregate.get(metric)))
                        .append(" | ").append(metricTiers.get(metric)).append(" |\n");
            }
            out.append("\nEvaluation notes:\n\n");
            for (String reason : reasons) out.append("- ").append(reason).append("\n");
            return out.toString();
        }
    }

    private record Arguments(Path baseline, Path candidate, Path output, int minimumSamples) {
        static Arguments parse(String[] args) {
            Path baseline = null, candidate = null, output = null;
            int minimum = 5;
            for (int i = 0; i < args.length; i += 2) {
                if (i + 1 >= args.length) throw new IllegalArgumentException("missing option value");
                switch (args[i]) {
                    case "--baseline" -> baseline = Path.of(args[i + 1]);
                    case "--candidate" -> candidate = Path.of(args[i + 1]);
                    case "--output" -> output = Path.of(args[i + 1]);
                    case "--min-samples" -> minimum = Integer.parseInt(args[i + 1]);
                    default -> throw new IllegalArgumentException("unknown option: " + args[i]);
                }
            }
            if (baseline == null || candidate == null) {
                throw new IllegalArgumentException("--baseline and --candidate are required");
            }
            return new Arguments(baseline, candidate, output, minimum);
        }
    }

    /** Minimal strict JSON parser sufficient for JMH's result schema. */
    private static final class JsonParser {
        private final String text;
        private int offset;
        JsonParser(String text) { this.text = text; }

        Object parse() {
            Object value = value();
            whitespace();
            if (offset != text.length()) throw error("trailing JSON data");
            return value;
        }

        private Object value() {
            whitespace();
            if (offset >= text.length()) throw error("unexpected end of JSON");
            return switch (text.charAt(offset)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            whitespace();
            if (take('}')) return result;
            while (true) {
                String key = string();
                whitespace(); expect(':');
                if (result.containsKey(key)) throw error("duplicate object key");
                result.put(key, value());
                whitespace();
                if (take('}')) return result;
                expect(','); whitespace();
            }
        }

        private List<Object> array() {
            expect('[');
            List<Object> result = new ArrayList<>();
            whitespace();
            if (take(']')) return result;
            while (true) {
                result.add(value()); whitespace();
                if (take(']')) return result;
                expect(',');
            }
        }

        private String string() {
            whitespace(); expect('"');
            StringBuilder result = new StringBuilder();
            while (offset < text.length()) {
                char ch = text.charAt(offset++);
                if (ch == '"') return result.toString();
                if (ch == '\\') {
                    if (offset >= text.length()) throw error("unterminated escape");
                    char escape = text.charAt(offset++);
                    switch (escape) {
                        case '"', '\\', '/' -> result.append(escape);
                        case 'b' -> result.append('\b');
                        case 'f' -> result.append('\f');
                        case 'n' -> result.append('\n');
                        case 'r' -> result.append('\r');
                        case 't' -> result.append('\t');
                        case 'u' -> {
                            if (offset + 4 > text.length()) throw error("short Unicode escape");
                            result.append((char) Integer.parseInt(text.substring(offset, offset + 4), 16));
                            offset += 4;
                        }
                        default -> throw error("invalid escape");
                    }
                } else {
                    if (ch < 0x20) throw error("control character in string");
                    result.append(ch);
                }
            }
            throw error("unterminated string");
        }

        private Double number() {
            int start = offset;
            if (take('-')) {}
            if (take('0')) {
                if (offset < text.length() && isDigit(text.charAt(offset))) {
                    throw error("leading zero in number");
                }
            } else {
                oneToNine();
                while (offset < text.length() && isDigit(text.charAt(offset))) offset++;
            }
            if (take('.')) digits();
            if (take('e') || take('E')) {
                if (!take('+')) take('-');
                digits();
            }
            try {
                double value = Double.parseDouble(text.substring(start, offset));
                if (!Double.isFinite(value)) throw error("non-finite number");
                return value;
            } catch (NumberFormatException error) {
                throw error("invalid number");
            }
        }

        private void digits() {
            int start = offset;
            while (offset < text.length() && isDigit(text.charAt(offset))) offset++;
            if (start == offset) throw error("expected digit");
        }

        private void oneToNine() {
            if (offset >= text.length() || text.charAt(offset) < '1' || text.charAt(offset) > '9') {
                throw error("expected digit");
            }
            offset++;
        }

        private static boolean isDigit(char value) {
            return value >= '0' && value <= '9';
        }

        private Object literal(String expected, Object value) {
            if (!text.startsWith(expected, offset)) throw error("invalid literal");
            offset += expected.length();
            return value;
        }

        private void whitespace() {
            while (offset < text.length()) {
                char value = text.charAt(offset);
                if (value != ' ' && value != '\t' && value != '\r' && value != '\n') break;
                offset++;
            }
        }

        private boolean take(char expected) {
            if (offset < text.length() && text.charAt(offset) == expected) {
                offset++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!take(expected)) throw error("expected '" + expected + "'");
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at JSON offset " + offset);
        }
    }
}
