package com.streamlens.assessment;

import com.streamlens.analyzer.AnalysisException;
import com.streamlens.analyzer.Analyzer;
import com.streamlens.analyzer.AnalyzerConfig;
import com.streamlens.analyzer.Group;
import com.streamlens.analyzer.TopUser;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.spec.ChaCha20ParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/** Seeded workloads generated and verified completely outside the timed region. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class AnalyzerBenchmark {
    static final String LOCAL_PARAMETER = "local";
    private static final long YEAR_ONE_EPOCH_SECOND = -62_135_596_800L;
    private static final byte[] RESULT_DOMAIN =
            "streamlens-java-result-v4\0".getBytes(StandardCharsets.US_ASCII);
    private static final int[][] REQUIRED_ORDERS = {
        {0, 1, 2, 3, 4},
        {1, 4, 0, 3, 2},
        {3, 2, 0, 4, 1},
        {4, 0, 1, 2, 3},
        {2, 3, 1, 0, 4},
        {0, 4, 3, 1, 2}
    };

    /** Assessment scripts replace both parameters together. */
    @Param({LOCAL_PARAMETER})
    public String fixtureSeed;

    @Param({LOCAL_PARAMETER})
    public String fixtureExpected;

    private Scenario balanced;
    private Scenario highCardinality;
    private Scenario mostlyFiltered;
    private ExpectedDigests expected;
    private List<Group> lastResult;
    private String expectedDigest;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        BenchmarkVerifier.FixtureContract contract = BenchmarkVerifier.contractForJmh(
                fixtureSeed,
                fixtureExpected,
                System.getenv(BenchmarkVerifier.AUTH_KEY_ENVIRONMENT));
        FixtureSet fixtureSet = fixtures(contract.seed());
        BenchmarkVerifier.verify(fixtureSet, contract.expected());
        balanced = fixtureSet.balanced();
        highCardinality = fixtureSet.highCardinality();
        mostlyFiltered = fixtureSet.mostlyFiltered();
        expected = contract.expected();
    }

    @Benchmark
    public List<Group> Balanced() throws AnalysisException, InterruptedException {
        return execute(balanced, expected.balanced());
    }

    @Benchmark
    public List<Group> HighCardinality() throws AnalysisException, InterruptedException {
        return execute(highCardinality, expected.highCardinality());
    }

    @Benchmark
    public List<Group> MostlyFiltered() throws AnalysisException, InterruptedException {
        return execute(mostlyFiltered, expected.mostlyFiltered());
    }

    // Trial teardown is deliberately outside every scored measurement. Hashing
    // on each invocation would contaminate gc.alloc.rate.norm and invalidate B/op.
    @TearDown(Level.Trial)
    public void verifyCompleteResult() {
        if (lastResult == null || expectedDigest == null) {
            throw new IllegalStateException("benchmark did not produce a result");
        }
        String actual = digest(lastResult);
        if (!actual.equals(expectedDigest)) {
            throw new IllegalStateException(
                    "benchmark result changed: got " + actual + ", expected " + expectedDigest);
        }
    }

    private List<Group> execute(Scenario scenario, String scenarioExpected)
            throws AnalysisException, InterruptedException {
        List<Group> result = Analyzer.analyze(
                new ByteArrayInputStream(scenario.input()), scenario.config());
        lastResult = result;
        expectedDigest = scenarioExpected;
        return result;
    }

    static FixtureSet fixtures(String canonicalSeed) {
        byte[] seed = decodeCanonicalSeed(canonicalSeed);
        return new FixtureSet(
                balanced(seed),
                highCardinality(seed),
                mostlyFiltered(seed));
    }

    private static Scenario balanced(byte[] seed) {
        FixtureRandom random = FixtureRandom.create(seed, "Balanced");
        int eventCount = 17_000 + random.nextInt(2_001);
        int tenantCount = 7 + random.nextInt(4);
        int userCount = 210 + random.nextInt(91);
        int typeCount = 3 + random.nextInt(3);
        long windowSeconds = 43L + random.nextInt(48);
        int topK = 4 + random.nextInt(5);
        Instant base = randomBase(random, 2025, 1_600_000);

        List<String> tenants = identifiers(random, "tenant", tenantCount);
        List<String> users = identifiers(random, "user", userCount);
        List<String> types = identifiers(random, "kind", typeCount);
        List<FixtureEvent> events = new ArrayList<>(eventCount);
        for (int index = 0; index < eventCount; index++) {
            events.add(event(
                    random,
                    base.plusSeconds(random.nextInt(2_400))
                            .plusNanos(random.nextInt(1_000_000_000)),
                    tenants.get(random.nextInt(tenants.size())),
                    users.get(random.nextInt(users.size())),
                    types.get(random.nextInt(types.size())),
                    index));
        }
        shuffle(events, random);
        return new Scenario(
                "Balanced",
                encode(events),
                new AnalyzerConfig(
                        null, null, List.of(), Duration.ofSeconds(windowSeconds), topK));
    }

    private static Scenario highCardinality(byte[] seed) {
        FixtureRandom random = FixtureRandom.create(seed, "HighCardinality");
        int eventCount = 7_600 + random.nextInt(801);
        int userCount = 3_700 + random.nextInt(601);
        long windowSeconds = 360L + 60L * random.nextInt(5);
        int topK = 8 + random.nextInt(7);
        Instant roughBase = randomBase(random, 2025, 2_000_000);
        long aligned = roughBase.getEpochSecond()
                - Math.floorMod(
                        roughBase.getEpochSecond() - YEAR_ONE_EPOCH_SECOND, windowSeconds);
        Instant base = Instant.ofEpochSecond(aligned + 5L);

        String tenant = identifier(random, "dense-tenant", 0);
        String type = identifier(random, "dense-kind", 0);
        List<String> users = identifiers(random, "cardinal-user", userCount);
        List<FixtureEvent> events = new ArrayList<>(eventCount);
        for (int index = 0; index < eventCount; index++) {
            int userIndex = index < userCount ? index : random.nextInt(userCount);
            events.add(event(
                    random,
                    base.plusSeconds(random.nextInt((int) windowSeconds - 10))
                            .plusNanos(random.nextInt(1_000_000_000)),
                    tenant,
                    users.get(userIndex),
                    type,
                    index));
        }
        shuffle(events, random);
        return new Scenario(
                "HighCardinality",
                encode(events),
                new AnalyzerConfig(
                        null, null, List.of(), Duration.ofSeconds(windowSeconds), topK));
    }

    private static Scenario mostlyFiltered(byte[] seed) {
        FixtureRandom random = FixtureRandom.create(seed, "MostlyFiltered");
        int eventCount = 23_000 + random.nextInt(2_001);
        int tenantCount = 10 + random.nextInt(5);
        int userCount = 650 + random.nextInt(251);
        int discardTypeCount = 3 + random.nextInt(3);
        int keepEvery = 17 + random.nextInt(9);
        long windowSeconds = 180L + 60L * random.nextInt(5);
        int topK = 2 + random.nextInt(4);
        Instant base = randomBase(random, 2025, 2_400_000);

        List<String> tenants = identifiers(random, "filter-tenant", tenantCount);
        List<String> users = identifiers(random, "filter-user", userCount);
        String keepType = identifier(random, "accepted-kind", 0);
        List<String> discardTypes = identifiers(random, "discarded-kind", discardTypeCount);
        List<FixtureEvent> events = new ArrayList<>(eventCount);
        for (int index = 0; index < eventCount; index++) {
            String type = index % keepEvery == 0
                    ? keepType
                    : discardTypes.get(random.nextInt(discardTypes.size()));
            events.add(event(
                    random,
                    base.plusSeconds(random.nextInt(7_200))
                            .plusNanos(random.nextInt(1_000_000_000)),
                    tenants.get(random.nextInt(tenants.size())),
                    users.get(random.nextInt(users.size())),
                    type,
                    index));
        }
        shuffle(events, random);
        return new Scenario(
                "MostlyFiltered",
                encode(events),
                new AnalyzerConfig(
                        base.plusSeconds(900L + random.nextInt(301)),
                        base.plusSeconds(6_000L + random.nextInt(301)),
                        List.of(keepType),
                        Duration.ofSeconds(windowSeconds),
                        topK));
    }

    private static Instant randomBase(FixtureRandom random, int year, int secondRange) {
        Instant start = Instant.parse(year + "-01-01T00:00:00Z");
        return start.plusSeconds(random.nextInt(secondRange));
    }

    private static List<String> identifiers(
            FixtureRandom random, String prefix, int count) {
        List<String> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(identifier(random, prefix, index));
        }
        return result;
    }

    private static String identifier(FixtureRandom random, String prefix, int index) {
        String suffix = switch (random.nextInt(8)) {
            case 0 -> "-snow-雪";
            case 1 -> "-combining-e\u0301";
            case 2 -> "-quote-\"";
            case 3 -> "-slash-\\";
            case 4 -> "-greek-π";
            default -> "";
        };
        return prefix + '-' + index + '-' + random.token(10 + random.nextInt(9)) + suffix;
    }

    private static FixtureEvent event(
            FixtureRandom random,
            Instant timestamp,
            String tenant,
            String user,
            String type,
            int sequence) {
        int offsetQuarterHours = random.nextInt(113) - 56;
        int paddingLength = random.nextInt(73);
        if (random.oneIn(97)) {
            paddingLength += 1_500 + random.nextInt(2_501);
        }
        return new FixtureEvent(
                timestamp,
                offsetQuarterHours * 15,
                tenant,
                user,
                type,
                valueText(random),
                sequence,
                random.nextInt(48),
                random.token(7 + random.nextInt(10)),
                random.token(paddingLength),
                random.nextLong(),
                random.nextInt(3));
    }

    private static String valueText(FixtureRandom random) {
        int whole = random.nextInt(90_000);
        int fraction = random.nextInt(1_000);
        return switch (random.nextInt(7)) {
            case 0 -> "-0";
            case 1 -> Integer.toString(whole);
            case 2 -> whole + "." + threeDigits(fraction);
            case 3 -> (1 + random.nextInt(9_000)) + "e-" + (1 + random.nextInt(4));
            case 4 -> (1 + random.nextInt(900)) + "." + threeDigits(fraction) + "E+2";
            case 5 -> "0.000" + (1 + random.nextInt(999));
            default -> Double.toString(whole / 8.0d);
        };
    }

    private static String threeDigits(int value) {
        if (value < 10) return "00" + value;
        if (value < 100) return "0" + value;
        return Integer.toString(value);
    }

    private static void shuffle(List<FixtureEvent> events, FixtureRandom random) {
        for (int index = events.size() - 1; index > 0; index--) {
            int other = random.nextInt(index + 1);
            FixtureEvent value = events.get(index);
            events.set(index, events.get(other));
            events.set(other, value);
        }
    }

    private static byte[] encode(List<FixtureEvent> events) {
        StringBuilder output = new StringBuilder(events.size() * 230);
        boolean needsNewline = false;
        for (FixtureEvent event : events) {
            for (int blank = 0; blank < event.blankLines(); blank++) {
                if (needsNewline) output.append('\n');
                output.append(blank % 2 == 0 ? " \t" : "\u3000");
                needsNewline = true;
            }
            if (needsNewline) output.append('\n');
            appendEvent(output, event);
            needsNewline = true;
        }
        if (!events.isEmpty() && (events.get(0).shape() & 1) == 0) {
            output.append('\n');
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendEvent(StringBuilder output, FixtureEvent event) {
        int shape = event.shape();
        if ((shape & 1) == 0) output.append(shape % 3 == 0 ? " \t" : "\u00a0");
        output.append('{');
        boolean[] first = {true};

        if (shape % 11 == 0) {
            appendField(output, first, escapedKey("value", true),
                    "{\"superseded\":[true,null,1e9999]}", shape);
        }
        if (shape % 7 == 0) appendUnknown(output, first, event, 0);

        int[] order = REQUIRED_ORDERS[shape % REQUIRED_ORDERS.length];
        for (int position = 0; position < order.length; position++) {
            int field = order[position];
            String key;
            String value;
            switch (field) {
                case 0 -> {
                    key = "timestamp";
                    String timestamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                            event.timestamp().atOffset(
                                    ZoneOffset.ofTotalSeconds(event.offsetMinutes() * 60)));
                    value = jsonString(timestamp, shape + position);
                }
                case 1 -> {
                    key = "tenant_id";
                    value = jsonString(event.tenant(), shape + position);
                }
                case 2 -> {
                    key = "user_id";
                    value = jsonString(event.user(), shape + position);
                }
                case 3 -> {
                    key = "type";
                    value = jsonString(event.type(), shape + position);
                }
                case 4 -> {
                    key = "value";
                    value = event.valueText();
                }
                default -> throw new AssertionError("unreachable field");
            }
            appendField(
                    output,
                    first,
                    escapedKey(key, (shape + field) % 9 == 0),
                    value,
                    shape + position);
            if ((shape + position) % 5 == 0) {
                appendUnknown(output, first, event, position + 1);
            }
        }
        appendUnknown(output, first, event, 9);
        output.append('}');
        if ((shape & 2) != 0) output.append(shape % 3 == 1 ? "\t " : "\u3000");
    }

    private static void appendUnknown(
            StringBuilder output,
            boolean[] first,
            FixtureEvent event,
            int slot) {
        String key = jsonString("unknown-" + event.unknownToken() + '-' + slot, event.shape() + slot);
        String value = switch ((event.shape() + slot) % 6) {
            case 0 -> "{\"sequence\":" + event.sequence()
                    + ",\"tags\":[true,null,{\"source\":"
                    + jsonString("fixture-" + event.unknownToken(), slot) + "}]}";
            case 1 -> "[false,{\"nested\":[1e9999,-0,\"ignored\"]},null]";
            case 2 -> "{\"padding\":" + jsonString(event.padding(), slot)
                    + ",\"noise\":\"" + Long.toUnsignedString(event.noise(), 16) + "\"}";
            case 3 -> "1e9999";
            case 4 -> jsonString(event.padding(), slot);
            default -> "{\"a\":{\"b\":[[],{},true]},\"n\":"
                    + Long.toUnsignedString(event.noise() >>> 1) + '}';
        };
        appendField(output, first, key, value, event.shape() + slot);
    }

    private static void appendField(
            StringBuilder output,
            boolean[] first,
            String encodedKey,
            String encodedValue,
            int style) {
        if (!first[0]) {
            output.append(style % 3 == 0 ? ", " : style % 3 == 1 ? ",\t" : ",");
        }
        first[0] = false;
        output.append(encodedKey);
        output.append(style % 4 == 0 ? " : " : style % 4 == 1 ? ":\t" : ":");
        output.append(encodedValue);
    }

    private static String escapedKey(String key, boolean escaped) {
        if (!escaped) return jsonString(key, -1);
        char first = key.charAt(0);
        StringBuilder result = new StringBuilder(key.length() + 8);
        result.append("\"\\u00");
        int high = (first >>> 4) & 0xf;
        int low = first & 0xf;
        result.append(Character.forDigit(high, 16));
        result.append(Character.forDigit(low, 16));
        result.append(key, 1, key.length()).append('"');
        return result.toString();
    }

    private static String jsonString(String value, int style) {
        StringBuilder result = new StringBuilder(value.length() + 16);
        result.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') {
                result.append('\\').append(character);
            } else if (character < 0x20) {
                result.append("\\u00");
                result.append(Character.forDigit((character >>> 4) & 0xf, 16));
                result.append(Character.forDigit(character & 0xf, 16));
            } else if (style >= 0 && (style + index) % 29 == 0) {
                result.append("\\u");
                result.append(Character.forDigit((character >>> 12) & 0xf, 16));
                result.append(Character.forDigit((character >>> 8) & 0xf, 16));
                result.append(Character.forDigit((character >>> 4) & 0xf, 16));
                result.append(Character.forDigit(character & 0xf, 16));
            } else {
                result.append(character);
            }
        }
        return result.append('"').toString();
    }

    static String digest(List<Group> groups) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(RESULT_DOMAIN);
            updateInt(digest, groups.size());
            for (Group group : groups) {
                digest.update((byte) 0x47);
                updateLong(digest, group.windowStart().getEpochSecond());
                updateInt(digest, group.windowStart().getNano());
                updateString(digest, group.tenantId());
                updateString(digest, group.type());
                updateLong(digest, group.count());
                updateLong(digest, Double.doubleToRawLongBits(group.sum()));
                updateInt(digest, group.uniqueUsers());
                updateInt(digest, group.topUsers().size());
                for (TopUser user : group.topUsers()) {
                    digest.update((byte) 0x55);
                    updateString(digest, user.userId());
                    updateLong(digest, Double.doubleToRawLongBits(user.value()));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void updateString(MessageDigest digest, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        updateInt(digest, encoded.length);
        digest.update(encoded);
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void updateLong(MessageDigest digest, long value) {
        digest.update((byte) (value >>> 56));
        digest.update((byte) (value >>> 48));
        digest.update((byte) (value >>> 40));
        digest.update((byte) (value >>> 32));
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static byte[] decodeCanonicalSeed(String seed) {
        if ((seed.length() != 32 && seed.length() != 64) || !isLowerHex(seed)) {
            throw new IllegalArgumentException("fixture seed must be 128 or 256-bit lowercase hex");
        }
        return HexFormat.of().parseHex(seed);
    }

    private static boolean isLowerHex(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    record Scenario(String name, byte[] input, AnalyzerConfig config) {}

    record FixtureSet(Scenario balanced, Scenario highCardinality, Scenario mostlyFiltered) {
        List<Scenario> all() {
            return List.of(balanced, highCardinality, mostlyFiltered);
        }
    }

    record ExpectedDigests(String balanced, String highCardinality, String mostlyFiltered) {
        String forScenario(String name) {
            return switch (name) {
                case "Balanced" -> balanced;
                case "HighCardinality" -> highCardinality;
                case "MostlyFiltered" -> mostlyFiltered;
                default -> throw new IllegalArgumentException("unknown scenario: " + name);
            };
        }
    }

    private record FixtureEvent(
            Instant timestamp,
            int offsetMinutes,
            String tenant,
            String user,
            String type,
            String valueText,
            int sequence,
            int shape,
            String unknownToken,
            String padding,
            long noise,
            int blankLines) {}

    private static final class FixtureRandom {
        private static final byte[] ZERO_BLOCK = new byte[4_096];
        private final Cipher streamCipher;
        private byte[] stream = new byte[0];
        private int streamOffset;

        private FixtureRandom(Cipher streamCipher) {
            this.streamCipher = streamCipher;
        }

        static FixtureRandom create(byte[] seed, String domain) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update("streamlens-java-fixture-v4-key\0"
                        .getBytes(StandardCharsets.US_ASCII));
                digest.update(seed);
                digest.update((byte) 0);
                digest.update(domain.getBytes(StandardCharsets.US_ASCII));
                byte[] key = digest.digest();

                digest.update("streamlens-java-fixture-v4-nonce\0"
                        .getBytes(StandardCharsets.US_ASCII));
                digest.update(seed);
                digest.update((byte) 0);
                digest.update(domain.getBytes(StandardCharsets.US_ASCII));
                byte[] nonceHash = digest.digest();
                byte[] nonce = new byte[12];
                System.arraycopy(nonceHash, 0, nonce, 0, nonce.length);

                Cipher cipher = Cipher.getInstance("ChaCha20");
                cipher.init(
                        Cipher.ENCRYPT_MODE,
                        new SecretKeySpec(key, "ChaCha20"),
                        new ChaCha20ParameterSpec(nonce, 1));
                return new FixtureRandom(cipher);
            } catch (GeneralSecurityException impossible) {
                throw new AssertionError(impossible);
            }
        }

        long nextLong() {
            if (streamOffset + Long.BYTES > stream.length) {
                stream = streamCipher.update(ZERO_BLOCK);
                streamOffset = 0;
                if (stream == null || stream.length != ZERO_BLOCK.length) {
                    throw new IllegalStateException("ChaCha20 fixture stream failed");
                }
            }
            long value = 0L;
            for (int index = 0; index < Long.BYTES; index++) {
                value = (value << 8) | (stream[streamOffset++] & 0xffL);
            }
            return value;
        }

        int nextInt(int bound) {
            if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
            return (int) Long.remainderUnsigned(nextLong(), bound);
        }

        boolean oneIn(int denominator) {
            return nextInt(denominator) == 0;
        }

        String token(int length) {
            String alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
            StringBuilder result = new StringBuilder(length);
            for (int index = 0; index < length; index++) {
                result.append(alphabet.charAt(nextInt(alphabet.length())));
            }
            return result.toString();
        }
    }
}
