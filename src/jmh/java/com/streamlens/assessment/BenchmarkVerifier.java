package com.streamlens.assessment;

import com.streamlens.analyzer.Analyzer;
import com.streamlens.analyzer.Group;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Untimed fail-closed oracle and complete-result verifier for seeded fixtures. */
public final class BenchmarkVerifier {
    static final String AUTH_KEY_ENVIRONMENT = "STREAMLENS_JAVA_FIXTURE_AUTH_KEY";
    private static final String RECORD_VERSION = "streamlens-java-oracle-v6";
    private static final String LOCAL_SEED =
            "4bd31f5c4ecdb1633d14d50f72132011cbd2f8d5b76f36668b45e20f86f2c872";
    // These constants are produced once by the trusted baseline over LOCAL_SEED.
    private static final AnalyzerBenchmark.ExpectedDigests LOCAL_EXPECTED =
            new AnalyzerBenchmark.ExpectedDigests(
                    "a9a0f851256fa676786ea585ee4adef4c54ebb9116f4d0f4298f050edd4854e5",
                    "64de96e42113960351314f80ad7a6052eb6e523f15744322a3d7a891662e89f5",
                    "94d3c9bc8625959f7aa1988d4ab963a5b1434b8d5a8b83e84310a2e2a8faf33a");

    private BenchmarkVerifier() {}

    /**
     * Usage:
     * <pre>
     *   BenchmarkVerifier
     *   BenchmarkVerifier oracle --seed HEX --auth-key HEX
     *   BenchmarkVerifier verify --seed HEX --expected RECORD --auth-key HEX
     * </pre>
     * Oracle mode must be run from the immutable baseline tree. Its single stdout
     * line is passed unchanged to candidate verify/JMH; candidate code never
     * computes its own expected values.
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            AnalyzerBenchmark.FixtureSet fixtures = AnalyzerBenchmark.fixtures(LOCAL_SEED);
            verify(fixtures, LOCAL_EXPECTED);
            System.out.println("All local benchmark scenario digests verified.");
            return;
        }
        if (args.length == 5
                && args[0].equals("oracle")
                && args[1].equals("--seed")
                && args[3].equals("--auth-key")) {
            String seed = canonicalEntropyHex(args[2], "seed");
            String key = canonicalEntropyHex(args[4], "auth key");
            AnalyzerBenchmark.ExpectedDigests observed = observe(
                    AnalyzerBenchmark.fixtures(seed));
            System.out.println(createRecord(seed, observed, key));
            return;
        }
        if (args.length == 7
                && args[0].equals("verify")
                && args[1].equals("--seed")
                && args[3].equals("--expected")
                && args[5].equals("--auth-key")) {
            String seed = canonicalEntropyHex(args[2], "seed");
            String key = canonicalEntropyHex(args[6], "auth key");
            AnalyzerBenchmark.ExpectedDigests expected = authenticateRecord(
                    seed, args[4], key);
            verify(AnalyzerBenchmark.fixtures(seed), expected);
            System.out.println(RECORD_VERSION + ":verified:" + seed);
            return;
        }
        throw new IllegalArgumentException("invalid BenchmarkVerifier arguments");
    }

    static FixtureContract contractForJmh(
            String seedParameter,
            String expectedParameter,
            String authKeyEnvironment) {
        boolean localSeed = AnalyzerBenchmark.LOCAL_PARAMETER.equals(seedParameter);
        boolean localExpected = AnalyzerBenchmark.LOCAL_PARAMETER.equals(expectedParameter);
        if (localSeed && localExpected && authKeyEnvironment == null) {
            return new FixtureContract(LOCAL_SEED, LOCAL_EXPECTED);
        }
        if (localSeed || localExpected) {
            throw new IllegalArgumentException(
                    "fixtureSeed and fixtureExpected must be replaced together");
        }
        if (authKeyEnvironment == null || authKeyEnvironment.isEmpty()) {
            throw new IllegalArgumentException(
                    AUTH_KEY_ENVIRONMENT + " is required for authenticated JMH fixtures");
        }
        String seed = canonicalEntropyHex(seedParameter, "seed");
        String key = canonicalEntropyHex(authKeyEnvironment, "auth key");
        return new FixtureContract(seed, authenticateRecord(seed, expectedParameter, key));
    }

    static void verify(
            AnalyzerBenchmark.FixtureSet fixtures,
            AnalyzerBenchmark.ExpectedDigests expected) throws Exception {
        AnalyzerBenchmark.ExpectedDigests observed = observe(fixtures);
        requireEqual("Balanced", observed.balanced(), expected.balanced());
        requireEqual(
                "HighCardinality", observed.highCardinality(), expected.highCardinality());
        requireEqual(
                "MostlyFiltered", observed.mostlyFiltered(), expected.mostlyFiltered());
    }

    private static AnalyzerBenchmark.ExpectedDigests observe(
            AnalyzerBenchmark.FixtureSet fixtures) throws Exception {
        String[] digests = new String[3];
        int index = 0;
        for (AnalyzerBenchmark.Scenario scenario : fixtures.all()) {
            List<Group> groups = Analyzer.analyze(
                    new ByteArrayInputStream(scenario.input()), scenario.config());
            digests[index++] = AnalyzerBenchmark.digest(groups);
        }
        return new AnalyzerBenchmark.ExpectedDigests(digests[0], digests[1], digests[2]);
    }

    private static void requireEqual(String scenario, String observed, String expected) {
        if (!MessageDigest.isEqual(
                observed.getBytes(StandardCharsets.US_ASCII),
                expected.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalStateException(scenario + " complete-result digest = "
                    + observed + ", expected " + expected);
        }
    }

    private static String createRecord(
            String seed,
            AnalyzerBenchmark.ExpectedDigests expected,
            String key) {
        String prefix = RECORD_VERSION + ':' + seed
                + ':' + requireDigest(expected.balanced())
                + ':' + requireDigest(expected.highCardinality())
                + ':' + requireDigest(expected.mostlyFiltered());
        return prefix + ':' + hmac(prefix, key);
    }

    private static AnalyzerBenchmark.ExpectedDigests authenticateRecord(
            String expectedSeed,
            String record,
            String key) {
        String[] parts = record.split(":", -1);
        if (parts.length != 6 || !parts[0].equals(RECORD_VERSION)) {
            throw new IllegalArgumentException("malformed expected-digest record");
        }
        if (!parts[1].equals(expectedSeed)) {
            throw new IllegalArgumentException("expected-digest record seed differs");
        }
        String prefix = String.join(":", parts[0], parts[1], parts[2], parts[3], parts[4]);
        String expectedMac = hmac(prefix, key);
        String suppliedMac = requireDigest(parts[5]);
        if (!MessageDigest.isEqual(
                expectedMac.getBytes(StandardCharsets.US_ASCII),
                suppliedMac.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("expected-digest record authentication failed");
        }
        return new AnalyzerBenchmark.ExpectedDigests(
                requireDigest(parts[2]),
                requireDigest(parts[3]),
                requireDigest(parts[4]));
    }

    private static String hmac(String text, String canonicalKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HexFormat.of().parseHex(canonicalKey), "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal(text.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String canonicalEntropyHex(String value, String label) {
        if (value == null || (value.length() != 32 && value.length() != 64)) {
            throw new IllegalArgumentException(label + " must be 128 or 256-bit hex");
        }
        StringBuilder canonical = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= '0' && character <= '9') {
                canonical.append(character);
            } else if (character >= 'a' && character <= 'f') {
                canonical.append(character);
            } else if (character >= 'A' && character <= 'F') {
                canonical.append((char) (character + ('a' - 'A')));
            } else {
                throw new IllegalArgumentException(label + " must be hex");
            }
        }
        return canonical.toString();
    }

    private static String requireDigest(String value) {
        if (value == null || value.length() != 64) {
            throw new IllegalArgumentException("digest must be 256-bit lowercase hex");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f'))) {
                throw new IllegalArgumentException("digest must be 256-bit lowercase hex");
            }
        }
        return value;
    }

    record FixtureContract(String seed, AnalyzerBenchmark.ExpectedDigests expected) {}
}
