#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
temporary=$(mktemp -d "${TMPDIR:-/tmp}/streamlens-java-compare-test.XXXXXX")
trap 'rm -rf -- "$temporary"' EXIT HUP INT TERM
mkdir "$temporary/classes"
javac --release 21 -proc:none -d "$temporary/classes" \
  "$root/scripts/trusted/BenchmarkCompare.java"

token_a=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
token_b=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
fixture_seed=cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
fixture_digest=eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee
fixture_mac=ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff
fixture_expected="streamlens-java-oracle-v5:$fixture_seed:$fixture_digest:$fixture_digest:$fixture_digest:$fixture_mac"

write_samples() {
  local path=$1 token=$2 time=$3 bytes=$4 count=${5:-5} result_token=${6:-$2}
  : >"$path"
  for ((sample = 1; sample <= count; sample++)); do
    cat >>"$path" <<EOF
@@STREAMLENS_JAVA_SAMPLE BEGIN $sample $token
@@STREAMLENS_JAVA_BENCHMARK_RESULT $result_token
[
 {"benchmark":"com.streamlens.assessment.AnalyzerBenchmark.Balanced","mode":"avgt","params":{"fixtureSeed":"$fixture_seed","fixtureExpected":"$fixture_expected"},"primaryMetric":{"score":$time,"scoreUnit":"ns/op"},"secondaryMetrics":{"gc.alloc.rate.norm":{"score":$bytes,"scoreUnit":"B/op"}}},
 {"benchmark":"com.streamlens.assessment.AnalyzerBenchmark.HighCardinality","mode":"avgt","params":{"fixtureSeed":"$fixture_seed","fixtureExpected":"$fixture_expected"},"primaryMetric":{"score":$time,"scoreUnit":"ns/op"},"secondaryMetrics":{"gc.alloc.rate.norm":{"score":$bytes,"scoreUnit":"B/op"}}},
 {"benchmark":"com.streamlens.assessment.AnalyzerBenchmark.MostlyFiltered","mode":"avgt","params":{"fixtureSeed":"$fixture_seed","fixtureExpected":"$fixture_expected"},"primaryMetric":{"score":$time,"scoreUnit":"ns/op"},"secondaryMetrics":{"gc.alloc.rate.norm":{"score":$bytes,"scoreUnit":"B/op"}}}
]
@@STREAMLENS_JAVA_SAMPLE END $sample $token
EOF
  done
}

write_partial_samples() {
  local path=$1 token=$2
  : >"$path"
  for ((sample = 1; sample <= 5; sample++)); do
    cat >>"$path" <<EOF
@@STREAMLENS_JAVA_SAMPLE BEGIN $sample $token
@@STREAMLENS_JAVA_BENCHMARK_RESULT $token
[
 {"benchmark":"com.streamlens.assessment.AnalyzerBenchmark.Balanced","mode":"avgt","params":{"fixtureSeed":"$fixture_seed","fixtureExpected":"$fixture_expected"},"primaryMetric":{"score":70,"scoreUnit":"ns/op"},"secondaryMetrics":{"gc.alloc.rate.norm":{"score":70,"scoreUnit":"B/op"}}},
 {"benchmark":"com.streamlens.assessment.AnalyzerBenchmark.HighCardinality","mode":"avgt","params":{"fixtureSeed":"$fixture_seed","fixtureExpected":"$fixture_expected"},"primaryMetric":{"score":70,"scoreUnit":"ns/op"},"secondaryMetrics":{"gc.alloc.rate.norm":{"score":70,"scoreUnit":"B/op"}}}
]
@@STREAMLENS_JAVA_SAMPLE END $sample $token
EOF
  done
}

run_compare() {
  local expected=$1 baseline=$2 candidate=$3 report=$4
  set +e
  java -cp "$temporary/classes" BenchmarkCompare \
    --baseline "$baseline" --candidate "$candidate" --output "$report" --min-samples 5 \
    >"$temporary/stdout" 2>"$temporary/stderr"
  local status=$?
  set -e
  if [[ $status -ne $expected ]]; then
    cat "$temporary/stdout" "$temporary/stderr" >&2
    echo "benchmark-compare-test: got exit $status, expected $expected" >&2
    exit 1
  fi
  [[ -s $report ]] || {
    echo 'benchmark-compare-test: comparator produced no report' >&2
    exit 1
  }
}

baseline="$temporary/baseline.txt"
candidate="$temporary/candidate.txt"
write_samples "$baseline" "$token_a" 100 100
write_samples "$candidate" "$token_b" 70 70
run_compare 0 "$baseline" "$candidate" "$temporary/pass.md"
grep -Fq 'Overall level: **Middle**' "$temporary/pass.md"

write_samples "$candidate" "$token_b" 90 90
run_compare 1 "$baseline" "$candidate" "$temporary/below-target.md"
grep -Fq 'no aggregate metric improved by at least 20%' "$temporary/below-target.md"

write_samples "$candidate" "$token_b" 150 150
run_compare 1 "$baseline" "$candidate" "$temporary/regression.md"
grep -Fq 'per-scenario limit' "$temporary/regression.md"

write_samples "$candidate" "$token_b" 70 70 4
run_compare 2 "$baseline" "$candidate" "$temporary/sample-count.md"
grep -Fq 'needs at least 5 samples' "$temporary/sample-count.md"

write_samples "$candidate" "$token_b" 70 70 5 "$token_a"
run_compare 2 "$baseline" "$candidate" "$temporary/token.md"
grep -Fq 'token does not match' "$temporary/token.md"

write_partial_samples "$candidate" "$token_b"
run_compare 2 "$baseline" "$candidate" "$temporary/partial.md"
grep -Fq 'exactly three JMH rows' "$temporary/partial.md"

write_samples "$candidate" "$token_b" 70 70
printf 'untrusted output\n' >>"$candidate"
run_compare 2 "$baseline" "$candidate" "$temporary/unframed.md"
grep -Fq 'output outside a sample' "$temporary/unframed.md"

write_samples "$candidate" "$token_b" 70 70
sed 's/"mode"/"duplicate":null,"duplicate":1,"mode"/' "$candidate" \
  >"$temporary/rewrite"
mv "$temporary/rewrite" "$candidate"
run_compare 2 "$baseline" "$candidate" "$temporary/duplicate-null-key.md"
grep -Fq 'duplicate object key' "$temporary/duplicate-null-key.md"

write_samples "$candidate" "$token_b" 70 70
sed 's/"score":70/"score":070/g' "$candidate" >"$temporary/rewrite"
mv "$temporary/rewrite" "$candidate"
run_compare 2 "$baseline" "$candidate" "$temporary/leading-zero.md"
grep -Fq 'leading zero' "$temporary/leading-zero.md"

write_samples "$candidate" "$token_b" 70 70
{ printf '\n'; cat "$candidate"; } >"$temporary/rewrite"
mv "$temporary/rewrite" "$candidate"
run_compare 2 "$baseline" "$candidate" "$temporary/blank-outside.md"
grep -Fq 'output outside a sample' "$temporary/blank-outside.md"

write_samples "$candidate" "$token_b" 70 70
other_seed=1212121212121212121212121212121212121212121212121212121212121212
sed "s/$fixture_seed/$other_seed/g" "$candidate" >"$temporary/rewrite"
mv "$temporary/rewrite" "$candidate"
run_compare 2 "$baseline" "$candidate" "$temporary/fixture-mismatch.md"
grep -Fq 'fixture contracts differ' "$temporary/fixture-mismatch.md"

echo 'benchmark comparator tests passed.'
