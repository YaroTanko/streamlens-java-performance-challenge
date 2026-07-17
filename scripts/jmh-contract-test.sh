#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd -- "$root"
./gradlew --no-daemon --console=plain jmhJar
version=$(sed -nE 's/^version = "([^"]+)"$/\1/p' build.gradle.kts)
[[ $(wc -l <<<"$version" | tr -d '[:space:]') == 1 ]] || {
  echo 'jmh-contract-test: could not determine the protected project version' >&2
  exit 1
}
jar="build/libs/streamlens-java-performance-challenge-${version}-jmh.jar"
[[ -f $jar && ! -L $jar ]] || {
  echo "jmh-contract-test: missing runnable JMH jar for version $version" >&2
  exit 1
}
java -Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=1 \
  -cp "$jar" com.streamlens.assessment.BenchmarkVerifier
seed=1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef
key=abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890
record=$(java -Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=1 \
  -cp "$jar" com.streamlens.assessment.BenchmarkVerifier \
  oracle --seed "$seed" --auth-key "$key")
[[ $record =~ ^streamlens-java-oracle-v3:${seed}:[0-9a-f]{64}:[0-9a-f]{64}:[0-9a-f]{64}:[0-9a-f]{64}$ ]] || {
  echo 'jmh-contract-test: oracle record is malformed' >&2
  exit 1
}
java -Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=1 \
  -cp "$jar" com.streamlens.assessment.BenchmarkVerifier \
  verify --seed "$seed" --expected "$record" --auth-key "$key" >/dev/null
if java -Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=1 \
    -cp "$jar" com.streamlens.assessment.BenchmarkVerifier \
    verify --seed "$seed" --expected "$record" \
    --auth-key ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff \
    >/dev/null 2>&1; then
  echo 'jmh-contract-test: tampered oracle key was accepted' >&2
  exit 1
fi

STREAMLENS_JAVA_FIXTURE_AUTH_KEY="$key" \
java -Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=1 \
  -jar "$jar" '.*AnalyzerBenchmark.Balanced$' \
  -p "fixtureSeed=$seed" -p "fixtureExpected=$record" \
  -wi 0 -i 1 -r 50ms -f 1 -t 1 -bm avgt -tu ns \
  >build/jmh-contract-smoke.txt
grep -Fq 'AnalyzerBenchmark.Balanced' build/jmh-contract-smoke.txt
echo 'JMH local and trusted-randomized complete-result contracts passed.'
