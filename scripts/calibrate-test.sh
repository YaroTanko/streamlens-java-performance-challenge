#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
temporary=$(mktemp -d "${TMPDIR:-/tmp}/streamlens-java-calibrate-test.XXXXXX")
trap 'rm -rf -- "$temporary"' EXIT HUP INT TERM

tool="$temporary/tool"
baseline="$temporary/baseline"
aa="$temporary/aa"
optimized="$temporary/optimized"
output_parent="$temporary/output-parent"
mkdir -p "$tool/scripts" "$baseline" "$aa" "$optimized" "$output_parent"
cp "$root/scripts/calibrate.sh" "$tool/scripts/calibrate.sh"

fake_assessor="$tool/scripts/assess.sh"
printf '%s\n' \
  '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  'candidate=$2' \
  'output=$6' \
  'mkdir -p "$output/benchmarks"' \
  'if [[ $candidate == *optimized ]]; then' \
  '  printf "%s\n" "| Metric | Improvement |" "| ns/op | 55.00% |" "| B/op | 4.00% |" "Overall level: **Senior**" >"$output/benchmarks/report.md"' \
  '  exit 0' \
  'fi' \
  'if [[ $output == *run-002* ]]; then' \
  '  time=-2.50' \
  '  bytes=3.00' \
  'else' \
  '  time=1.25' \
  '  bytes=-1.50' \
  'fi' \
  'printf "%s\n" "| Metric | Improvement |" "| ns/op | ${time}% |" "| B/op | ${bytes}% |" "Overall level: **Below target**" >"$output/benchmarks/report.md"' \
  'exit 1' >"$fake_assessor"
chmod 0755 "$tool/scripts/calibrate.sh" "$fake_assessor"

baseline_sha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
aa_sha=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
optimized_sha=cccccccccccccccccccccccccccccccccccccccc

run_calibration() {
  local output=$1
  shift
  "$tool/scripts/calibrate.sh" \
    --baseline-dir "$baseline" --baseline-commit "$baseline_sha" \
    --aa-dir "$aa" --aa-commit "$aa_sha" \
    --optimized-dir "$optimized" --optimized-commit "$optimized_sha" \
    --optimized-min-tier Middle --optimized-max-tier Senior \
    --output-dir "$output" "$@"
}

multi="$output_parent/multi"
run_calibration "$multi" --aa-max-abs-percent 5 --runs 2 >"$temporary/multi.out"
for run in run-001 run-002; do
  [[ -f $multi/aa/$run/benchmarks/report.md ]] || {
    echo "calibrate-test: missing A/A report for $run" >&2
    exit 1
  }
  [[ -f $multi/optimized/$run/benchmarks/report.md ]] || {
    echo "calibrate-test: missing optimized report for $run" >&2
    exit 1
  }
done
grep -Fq -- '- Repetitions: 2' "$multi/calibration-summary.md"
grep -Fq -- '- A/A ns/op range: -2.50% to 1.25%' "$multi/calibration-summary.md"
grep -Fq -- '- A/A B/op range: -1.50% to 3.00%' "$multi/calibration-summary.md"
grep -Fq -- '| run-002 | 1 | -2.50% | 3.00% | 0 | Senior |' \
  "$multi/calibration-summary.md"

single="$output_parent/single"
run_calibration "$single" --aa-max-abs-percent 5 >"$temporary/single.out"
[[ -f $single/aa/benchmarks/report.md && -f $single/optimized/benchmarks/report.md ]] || {
  echo 'calibrate-test: default output layout changed' >&2
  exit 1
}
[[ ! -e $single/aa/run-001 ]] || {
  echo 'calibrate-test: default invocation unexpectedly created repeated layout' >&2
  exit 1
}
grep -Fq -- '- Repetitions: 1' "$single/calibration-summary.md"

if run_calibration "$output_parent/invalid" --runs 1 >/dev/null 2>&1; then
  echo 'calibrate-test: explicit --runs 1 was accepted' >&2
  exit 1
fi

echo 'calibration repetition tests passed.'
