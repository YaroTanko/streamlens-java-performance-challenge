#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd -- "$root"
./gradlew --no-daemon --console=plain jmhJar
jars=(build/libs/*-jmh.jar)
[[ ${#jars[@]} -eq 1 && -f ${jars[0]} && ! -L ${jars[0]} ]] || {
  echo 'benchmark: expected exactly one runnable JMH jar' >&2
  exit 2
}

jvm=(java -Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=1)
"${jvm[@]}" -cp "${jars[0]}" com.streamlens.assessment.BenchmarkVerifier
exec "${jvm[@]}" -jar "${jars[0]}" '.*AnalyzerBenchmark.*' \
  -wi 2 -w 200ms -i 3 -r 300ms -f 1 -t 1 -bm avgt -tu ns -prof gc
