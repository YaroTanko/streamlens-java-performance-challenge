#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
profile_dir="$root/.bench/profiles"
mkdir -p "$profile_dir"
rm -f "$profile_dir"/*.jfr "$profile_dir/jfr-summary.txt" "$profile_dir/hot-methods.txt"
: > "$profile_dir/jfr-summary.txt"
: > "$profile_dir/hot-methods.txt"

for scenario in balanced highCardinality mostlyFiltered; do
  recording="$profile_dir/$scenario.jfr"
  recording_arg="-XX:StartFlightRecording=filename=$recording,settings=profile,dumponexit=true"
  (
    cd "$root"
    ./gradlew jmh \
      -PjmhInclude="com.streamlens.assessment.StreamLensBenchmark.$scenario" \
      -PjmhWarmupIterations=1 \
      -PjmhWarmupTime=200ms \
      -PjmhIterations=2 \
      -PjmhMeasurementTime=300ms \
      -PjmhForks=1 \
      -PjmhJvmArgsAppend="$recording_arg"
  )
  if [[ ! -s "$recording" ]]; then
    echo "profile: JFR recording was not created for $scenario" >&2
    exit 1
  fi
  printf '\n===== %s =====\n' "$scenario" >> "$profile_dir/jfr-summary.txt"
  jfr summary "$recording" >> "$profile_dir/jfr-summary.txt"
  printf '\n===== %s =====\n' "$scenario" >> "$profile_dir/hot-methods.txt"
  if jfr view hot-methods "$recording" >> "$profile_dir/hot-methods.txt" 2>/dev/null; then
    :
  else
    jfr print --events jdk.ExecutionSample,jdk.ObjectAllocationSample "$recording" \
      >> "$profile_dir/hot-methods.txt"
  fi
  echo "profile: $recording"
done

echo "profile: $profile_dir/jfr-summary.txt"
echo "profile: $profile_dir/hot-methods.txt"
