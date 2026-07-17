#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 <cpu|alloc> [Balanced|HighCardinality|MostlyFiltered] [output-directory]" >&2
  exit 2
}

die() {
  echo "profile: $*" >&2
  exit 2
}

[[ $# -ge 1 && $# -le 3 ]] || usage
kind=$1
scenario=${2:-${PROFILE_SCENARIO:-Balanced}}
duration=${PROFILE_TIME:-2s}
[[ $kind == cpu || $kind == alloc ]] || usage
case "$scenario" in
  Balanced | HighCardinality | MostlyFiltered) ;;
  *) usage ;;
esac
[[ $duration =~ ^[1-9][0-9]*(ms|s|m)$ ]] || die 'PROFILE_TIME must be a positive integer duration (ms, s, or m)'
command -v java >/dev/null 2>&1 || die 'java is required'
command -v jfr >/dev/null 2>&1 || die 'the Java 21 jfr command is required'

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
output_input=${3:-$root/build/profiles/$kind}
[[ $output_input != *$'\n'* && $output_input != *$'\r'* && $output_input != *,* ]] \
  || die 'output path contains an unsupported character'
parent_input=$(dirname -- "$output_input")
name=$(basename -- "$output_input")
[[ -n $name && $name != . && $name != .. && $name != / ]] || die 'invalid output directory'
mkdir -p -- "$parent_input"
[[ -d $parent_input && ! -L $parent_input ]] || die 'output parent must be a non-symbolic-link directory'
parent=$(cd -- "$parent_input" && pwd -P)
output="$parent/$name"
[[ ! -L $output ]] || die 'output directory must not be a symbolic link'
if [[ -e $output && ! -d $output ]]; then
  die 'output path exists and is not a directory'
fi

cd -- "$root"
./gradlew --no-daemon --console=plain jmhJar
jars=(build/libs/*-jmh.jar)
[[ ${#jars[@]} -eq 1 && -f ${jars[0]} && ! -L ${jars[0]} ]] \
  || die 'expected exactly one runnable JMH jar'
jar=${jars[0]}

staging=$(mktemp -d "$parent/.streamlens-java-${kind}.XXXXXX")
trap 'rm -rf -- "$staging"' EXIT HUP INT TERM
jvm=(java -Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=1)
"${jvm[@]}" -cp "$jar" com.streamlens.assessment.BenchmarkVerifier >/dev/null

events=jdk.ExecutionSample,jdk.NativeMethodSample
if [[ $kind == alloc ]]; then
  events=jdk.ObjectAllocationSample,jdk.ObjectAllocationInNewTLAB,jdk.ObjectAllocationOutsideTLAB
fi

"${jvm[@]}" \
  -jar "$jar" ".*AnalyzerBenchmark.${scenario}$" \
  -wi 2 -w 200ms -i 1 -r "$duration" -f 1 -t 1 -bm avgt -tu ns -prof gc \
  -jvmArgsAppend "-XX:StartFlightRecording=filename=$staging/recording.jfr,settings=profile,dumponexit=true" \
  >"$staging/jmh.txt" 2>&1
jfr summary "$staging/recording.jfr" >"$staging/summary.txt"
jfr print --events "$events" --stack-depth 32 "$staging/recording.jfr" \
  >"$staging/hotspots.txt"

for artifact in recording.jfr summary.txt hotspots.txt jmh.txt; do
  [[ -s $staging/$artifact && ! -L $staging/$artifact ]] \
    || die "profile artifact is missing or empty: $artifact"
done

if [[ -d $output ]]; then
  for artifact in recording.jfr summary.txt hotspots.txt jmh.txt; do
    [[ ! -e $output/$artifact || ( -f $output/$artifact && ! -L $output/$artifact ) ]] \
      || die "refusing to replace unsafe prior artifact: $output/$artifact"
    rm -f -- "$output/$artifact"
  done
  rmdir -- "$output" 2>/dev/null \
    || die 'prior profile directory contains unexpected files; move or remove it explicitly'
fi
mv -- "$staging" "$output"
trap - EXIT HUP INT TERM
echo "Profile artifacts published: $output"
