#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd -- "$root"
./gradlew --no-daemon --console=plain jmhJar
version=$(sed -nE 's/^version = "([^"]+)"$/\1/p' build.gradle.kts)
[[ -n $version && $version != *$'\n'* && $version =~ ^[0-9][0-9A-Za-z._-]*$ ]] || {
  echo 'benchmark: could not determine the protected project version' >&2
  exit 2
}
jar="build/libs/streamlens-java-performance-challenge-${version}-jmh.jar"
[[ -f $jar && ! -L $jar ]] || {
  echo "benchmark: missing runnable JMH jar for version $version" >&2
  exit 2
}

jvm=(java -Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=1)
"${jvm[@]}" -cp "$jar" com.streamlens.assessment.BenchmarkVerifier
exec "${jvm[@]}" -jar "$jar" '.*AnalyzerBenchmark.*' \
  -wi 2 -w 200ms -i 3 -r 300ms -f 1 -t 1 -bm avgt -tu ns -prof gc
