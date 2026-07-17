#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd -- "$root"
./gradlew --no-daemon --console=plain jmhJar
jars=(build/libs/*-jmh.jar)
[[ ${#jars[@]} -eq 1 && -f ${jars[0]} ]] || {
  echo 'jmh-contract-test: expected exactly one runnable JMH jar' >&2
  exit 1
}
java -Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=1 \
  -cp "${jars[0]}" com.streamlens.assessment.BenchmarkVerifier
seed=1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef
key=abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890
record=$(java -Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=1 \
  -cp "${jars[0]}" com.streamlens.assessment.BenchmarkVerifier \
  oracle --seed "$seed" --auth-key "$key")
[[ $record =~ ^streamlens-java-oracle-v2:${seed}:[0-9a-f]{64}:[0-9a-f]{64}:[0-9a-f]{64}:[0-9a-f]{64}$ ]] || {
  echo 'jmh-contract-test: oracle record is malformed' >&2
  exit 1
}
java -Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=1 \
  -cp "${jars[0]}" com.streamlens.assessment.BenchmarkVerifier \
  verify --seed "$seed" --expected "$record" --auth-key "$key" >/dev/null
if java -Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=1 \
    -cp "${jars[0]}" com.streamlens.assessment.BenchmarkVerifier \
    verify --seed "$seed" --expected "$record" \
    --auth-key ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff \
    >/dev/null 2>&1; then
  echo 'jmh-contract-test: tampered oracle key was accepted' >&2
  exit 1
fi

STREAMLENS_JAVA_FIXTURE_AUTH_KEY="$key" \
java -Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=1 \
  -jar "${jars[0]}" '.*AnalyzerBenchmark.Balanced$' \
  -p "fixtureSeed=$seed" -p "fixtureExpected=$record" \
  -wi 0 -i 1 -r 50ms -f 1 -t 1 -bm avgt -tu ns \
  >build/jmh-contract-smoke.txt
grep -Fq 'AnalyzerBenchmark.Balanced' build/jmh-contract-smoke.txt
echo 'JMH local and trusted-randomized complete-result contracts passed.'
