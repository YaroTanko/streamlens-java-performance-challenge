#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
temporary=$(mktemp -d "${TMPDIR:-/tmp}/streamlens-java-assess-test.XXXXXX")
trap 'rm -rf -- "$temporary"' EXIT HUP INT TERM
baseline="$temporary/baseline"
candidate="$temporary/candidate"
output_parent="$temporary/output"
mkdir -m 0700 "$baseline" "$output_parent"
mkdir -p "$baseline/src/main/java" "$baseline/scripts/trusted" "$baseline/gradle/wrapper"
cp -a "$root/src/main/java/." "$baseline/src/main/java/"
cp "$root/scripts/check-protected.sh" "$baseline/scripts/check-protected.sh"
cp "$root/scripts/prepare-candidate.sh" "$baseline/scripts/prepare-candidate.sh"
cp "$root/scripts/source-audit.sh" "$baseline/scripts/source-audit.sh"
cp "$root/scripts/run-benchmarks.sh" "$baseline/scripts/run-benchmarks.sh"
cp "$root/scripts/trusted/SourceAudit.java" "$baseline/scripts/trusted/SourceAudit.java"
cp "$root/scripts/trusted/BenchmarkCompare.java" "$baseline/scripts/trusted/BenchmarkCompare.java"
cp "$root/scripts/trusted/EvidenceManifest.java" "$baseline/scripts/trusted/EvidenceManifest.java"
cp "$root/OPTIMIZATION.md" "$baseline/OPTIMIZATION.md"
printf 'plugins { java }\n' >"$baseline/build.gradle.kts"
printf 'rootProject.name = "assess-fixture"\n' >"$baseline/settings.gradle.kts"
printf '#!/bin/sh\nexit 0\n' >"$baseline/gradlew"
printf 'fixture\n' >"$baseline/gradle/wrapper/gradle-wrapper.jar"
printf 'distributionUrl=https://example.invalid/gradle.zip\n' \
  >"$baseline/gradle/wrapper/gradle-wrapper.properties"

cat >"$baseline/scripts/run-isolated.sh" <<'FAKE_RUNNER'
#!/usr/bin/env bash
set -euo pipefail
prepared=$1
mode=$2
shift 2
case "$mode" in
  test)
    [[ $# -eq 0 ]]
    echo 'fixture functional tests passed'
    ;;
  oracle)
    [[ $# -eq 0 ]]
    token=${ORACLE_RESULT_TOKEN:?}
    seed=${ASSESSMENT_FIXTURE_SEED:?}
    digest=0000000000000000000000000000000000000000000000000000000000000000
    printf '@@STREAMLENS_JAVA_ORACLE_RESULT %s streamlens-java-oracle-v2:%s:%s:%s:%s:%s\n' \
      "$token" "$seed" "$digest" "$digest" "$digest" "$digest"
    ;;
  benchmark)
    [[ $# -eq 0 ]]
    token=${BENCHMARK_RESULT_TOKEN:?}
    score=100
    grep -Fq 'candidate marker' \
      "$prepared/src/main/java/com/streamlens/analyzer/Analyzer.java" && score=70
    printf '@@STREAMLENS_JAVA_BENCHMARK_RESULT %s\n' "$token"
    cat <<JSON
[
 {"benchmark":"com.streamlens.assessment.AnalyzerBenchmark.Balanced","mode":"avgt","params":{"fixtureSeed":"$ASSESSMENT_FIXTURE_SEED","fixtureExpected":"$ASSESSMENT_FIXTURE_EXPECTED"},"primaryMetric":{"score":$score,"scoreUnit":"ns/op"},"secondaryMetrics":{"gc.alloc.rate.norm":{"score":$score,"scoreUnit":"B/op"}}},
 {"benchmark":"com.streamlens.assessment.AnalyzerBenchmark.HighCardinality","mode":"avgt","params":{"fixtureSeed":"$ASSESSMENT_FIXTURE_SEED","fixtureExpected":"$ASSESSMENT_FIXTURE_EXPECTED"},"primaryMetric":{"score":$score,"scoreUnit":"ns/op"},"secondaryMetrics":{"gc.alloc.rate.norm":{"score":$score,"scoreUnit":"B/op"}}},
 {"benchmark":"com.streamlens.assessment.AnalyzerBenchmark.MostlyFiltered","mode":"avgt","params":{"fixtureSeed":"$ASSESSMENT_FIXTURE_SEED","fixtureExpected":"$ASSESSMENT_FIXTURE_EXPECTED"},"primaryMetric":{"score":$score,"scoreUnit":"ns/op"},"secondaryMetrics":{"gc.alloc.rate.norm":{"score":$score,"scoreUnit":"B/op"}}}
]
JSON
    ;;
  profile)
    [[ $# -eq 4 ]]
    kind=$1
    output=$4
    mkdir "$output"
    for artifact in recording.jfr summary.txt hotspots.txt jmh.txt; do
      printf 'fixture %s %s\n' "$kind" "$artifact" >"$output/$artifact"
    done
    echo "fixture $kind profile passed"
    ;;
  *) exit 2 ;;
esac
FAKE_RUNNER
chmod 0755 "$baseline/gradlew" "$baseline/scripts/"*.sh
git -C "$baseline" init -q -b main
git -C "$baseline" config user.name 'Assess Test'
git -C "$baseline" config user.email 'assess-test@example.invalid'
git -C "$baseline" add .
git -C "$baseline" commit -qm baseline
baseline_commit=$(git -C "$baseline" rev-parse HEAD)

git clone -q "$baseline" "$candidate"
git -C "$candidate" config user.name 'Assess Test'
git -C "$candidate" config user.email 'assess-test@example.invalid'
printf '\n// candidate marker\n' \
  >>"$candidate/src/main/java/com/streamlens/analyzer/Analyzer.java"
cat >"$candidate/OPTIMIZATION.md" <<'EOF'
- Profile evidence: JFR showed the measured parsing allocation hotspot.
- Approach: reduce temporary parser objects.
- Contract: preserve validation and ordering.
- Expected effect: reduce time and allocation.
- Trade-off: use a more explicit parser state machine.
- Verification: correctness and benchmark checks passed.
EOF
git -C "$candidate" add \
  src/main/java/com/streamlens/analyzer/Analyzer.java OPTIMIZATION.md
git -C "$candidate" commit -qm candidate
candidate_commit=$(git -C "$candidate" rev-parse HEAD)

image=example.invalid/streamlens-java@sha256:0000000000000000000000000000000000000000000000000000000000000000
ASSESSMENT_DOCKER_IMAGE="$image" BENCH_SAMPLES=5 PROFILE_TIME=1s \
  bash "$root/scripts/assess.sh" \
  "$baseline" "$candidate" "$baseline_commit" "$baseline_commit" "$candidate_commit" \
  "$output_parent/assessment" >"$temporary/assess.out" 2>"$temporary/assess.err"

assessment="$output_parent/assessment"
grep -Fq 'Result: ✅ passed' "$assessment/benchmarks/report.md"
grep -Fq 'Overall level: **Middle**' "$assessment/benchmarks/report.md"
grep -Fqx 'functional=passed' "$assessment/assessment-status.txt"
grep -Fq '"assessment_version": "java-v2"' \
  "$assessment/evidence/manifest-core.json"
for kind in cpu alloc; do
  for artifact in recording.jfr summary.txt hotspots.txt jmh.txt; do
    [[ -s $assessment/profiles/$kind/$artifact ]] || {
      echo "assess-test: missing $kind/$artifact" >&2
      exit 1
    }
  done
done
if grep -R -E 'fixture[.]auth_key|fixtureAuthKey' "$assessment" >/dev/null; then
  echo 'assess-test: fixture authentication key leaked into retained evidence' >&2
  exit 1
fi

printf 'dirty\n' >"$candidate/dirty.txt"
if ASSESSMENT_DOCKER_IMAGE="$image" BENCH_SAMPLES=5 PROFILE_TIME=1s \
    bash "$root/scripts/assess.sh" \
    "$baseline" "$candidate" "$baseline_commit" "$baseline_commit" "$candidate_commit" \
    "$output_parent/dirty" >/dev/null 2>&1; then
  echo 'assess-test: dirty candidate checkout was accepted' >&2
  exit 1
fi
[[ ! -e $output_parent/dirty ]] || {
  echo 'assess-test: rejected dirty checkout still created output' >&2
  exit 1
}

echo 'unified assessor tests passed.'
