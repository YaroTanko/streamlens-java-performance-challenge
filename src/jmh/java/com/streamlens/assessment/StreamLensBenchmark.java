package com.streamlens.assessment;

import com.streamlens.analyzer.Analyzer;
import com.streamlens.analyzer.Config;
import com.streamlens.analyzer.Group;

import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
public class StreamLensBenchmark {
    @Benchmark
    public void balanced(BenchmarkState state, Blackhole blackhole) throws Exception {
        blackhole.consume(run(state.balancedInput, state.balancedConfig));
    }

    @Benchmark
    public void highCardinality(BenchmarkState state, Blackhole blackhole) throws Exception {
        blackhole.consume(run(state.highCardinalityInput, state.highCardinalityConfig));
    }

    @Benchmark
    public void mostlyFiltered(BenchmarkState state, Blackhole blackhole) throws Exception {
        blackhole.consume(run(state.mostlyFilteredInput, state.mostlyFilteredConfig));
    }

    private static List<Group> run(String input, Config config) throws Exception {
        return Analyzer.analyze(new StringReader(input), config);
    }

    @State(Scope.Benchmark)
    public static class BenchmarkState {
        private String balancedInput;
        private String highCardinalityInput;
        private String mostlyFilteredInput;
        private Config balancedConfig;
        private Config highCardinalityConfig;
        private Config mostlyFilteredConfig;

        @Setup
        public void setUp() {
            balancedInput = generate(3_000, 6, 5, 180, 180, false);
            highCardinalityInput = generate(1_800, 300, 6, 1_600, 1_800, false);
            mostlyFilteredInput = generate(4_000, 8, 8, 500, 240, true);

            balancedConfig = new Config(null, null, Set.of(), Duration.ofMinutes(1), 5);
            highCardinalityConfig = new Config(null, null, Set.of(), Duration.ofSeconds(30), 5);
            mostlyFilteredConfig = new Config(
                    Instant.parse("2026-01-15T12:02:00Z"),
                    Instant.parse("2026-01-15T12:03:00Z"),
                    Set.of("type-0"),
                    Duration.ofMinutes(1),
                    5);
        }

        private static String generate(
                int events,
                int tenants,
                int types,
                int users,
                int timeSpanSeconds,
                boolean includePayload) {
            StringBuilder builder = new StringBuilder(events * 150);
            Instant origin = Instant.parse("2026-01-15T12:00:00Z");
            for (int i = 0; i < events; i++) {
                Instant timestamp = origin.plusMillis((long) (i % timeSpanSeconds) * 1_000L + (i % 997));
                double value = ((i * 37L) % 10_000L) / 100.0;
                builder.append("{\"timestamp\":\"")
                        .append(timestamp)
                        .append("\",\"tenant_id\":\"tenant-")
                        .append(i % tenants)
                        .append("\",\"user_id\":\"user-")
                        .append((i * 17L) % users)
                        .append("\",\"type\":\"type-")
                        .append(i % types)
                        .append("\",\"value\":")
                        .append(String.format(Locale.ROOT, "%.2f", value));
                if (includePayload) {
                    builder.append(",\"payload\":{\"request_id\":\"")
                            .append(i)
                            .append("\",\"flags\":[true,false,null]}");
                }
                builder.append("}\n");
            }
            return builder.toString();
        }
    }
}
