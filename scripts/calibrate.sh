#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

usage() {
  cat >&2 <<'USAGE'
usage: scripts/calibrate.sh \
  --baseline-dir <clean-checkout> --baseline-commit <40-sha> \
  --aa-dir <clean-neutral-candidate-checkout> --aa-commit <40-sha> \
  --optimized-dir <clean-reference-checkout> --optimized-commit <40-sha> \
  --optimized-min-tier <Middle|Senior|Staff> \
  --optimized-max-tier <Middle|Senior|Staff> \
  --output-dir <new-directory> [--aa-max-abs-percent <percent>]

Both candidate commits must be direct assessment submissions based on the
immutable baseline commit. ASSESSMENT_DOCKER_IMAGE must be digest-pinned.
USAGE
  exit 2
}

die() {
  echo "calibrate: $*" >&2
  exit 1
}

baseline=
baseline_commit=
aa=
aa_commit=
optimized=
optimized_commit=
minimum_tier=
maximum_tier=
output_input=
aa_limit=10
while [[ $# -gt 0 ]]; do
  case "$1" in
    --baseline-dir) [[ $# -ge 2 && -z $baseline ]] || usage; baseline=$2; shift 2 ;;
    --baseline-commit) [[ $# -ge 2 && -z $baseline_commit ]] || usage; baseline_commit=$2; shift 2 ;;
    --aa-dir) [[ $# -ge 2 && -z $aa ]] || usage; aa=$2; shift 2 ;;
    --aa-commit) [[ $# -ge 2 && -z $aa_commit ]] || usage; aa_commit=$2; shift 2 ;;
    --optimized-dir) [[ $# -ge 2 && -z $optimized ]] || usage; optimized=$2; shift 2 ;;
    --optimized-commit) [[ $# -ge 2 && -z $optimized_commit ]] || usage; optimized_commit=$2; shift 2 ;;
    --optimized-min-tier) [[ $# -ge 2 && -z $minimum_tier ]] || usage; minimum_tier=$2; shift 2 ;;
    --optimized-max-tier) [[ $# -ge 2 && -z $maximum_tier ]] || usage; maximum_tier=$2; shift 2 ;;
    --output-dir) [[ $# -ge 2 && -z $output_input ]] || usage; output_input=$2; shift 2 ;;
    --aa-max-abs-percent) [[ $# -ge 2 ]] || usage; aa_limit=$2; shift 2 ;;
    -h | --help) usage ;;
    *) usage ;;
  esac
done
[[ -n $baseline && -n $baseline_commit && -n $aa && -n $aa_commit \
   && -n $optimized && -n $optimized_commit && -n $minimum_tier \
   && -n $maximum_tier && -n $output_input ]] || usage
for revision in "$baseline_commit" "$aa_commit" "$optimized_commit"; do
  [[ $revision =~ ^[0-9a-f]{40}$ ]] || usage
done
rank() {
  case "$1" in Middle) echo 1 ;; Senior) echo 2 ;; Staff) echo 3 ;; *) return 1 ;; esac
}
minimum_rank=$(rank "$minimum_tier") || usage
maximum_rank=$(rank "$maximum_tier") || usage
((minimum_rank <= maximum_rank)) || usage
[[ $aa_limit =~ ^[0-9]+([.][0-9]+)?$ ]] || usage
awk -v value="$aa_limit" 'BEGIN { exit !(value > 0 && value <= 100) }' || usage

script_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
assessor="$script_directory/assess.sh"
[[ -f $assessor && ! -L $assessor ]] || die 'unified assessor is missing'
parent_input=$(dirname -- "$output_input")
name=$(basename -- "$output_input")
[[ -d $parent_input && ! -L $parent_input && -n $name && $name != . && $name != .. ]] || usage
parent=$(cd -- "$parent_input" && pwd -P)
output="$parent/$name"
[[ ! -e $output && ! -L $output ]] || die 'output directory must not already exist'
umask 077
mkdir -m 0700 "$output"

run_assessment() {
  local label=$1 checkout=$2 commit=$3 destination=$4 log=$5 status
  set +e
  env -u BASH_ENV -u ENV bash --noprofile --norc "$assessor" \
    "$baseline" "$checkout" "$baseline_commit" "$baseline_commit" "$commit" \
    "$destination" >"$log" 2>&1
  status=$?
  set -e
  case "$status" in
    0 | 1) ;;
    *) cat "$log" >&2; die "$label assessment failed as infrastructure (exit $status)" ;;
  esac
  printf '%s\n' "$status"
}

aa_status=$(run_assessment A/A "$aa" "$aa_commit" "$output/aa" "$output/aa-assessor.txt")
optimized_status=$(run_assessment reference "$optimized" "$optimized_commit" \
  "$output/optimized" "$output/optimized-assessor.txt")

aggregate() {
  local report=$1 metric=$2
  awk -F'|' -v wanted="$metric" '
    function trim(value) {
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
      return value
    }
    trim($2) == wanted {
      value = trim($3)
      gsub(/[+%]/, "", value)
      print value
      found++
    }
    END { if (found != 1) exit 1 }
  ' "$report"
}

aa_time=$(aggregate "$output/aa/benchmarks/report.md" ns/op) \
  || die 'could not parse A/A ns/op aggregate'
aa_bytes=$(aggregate "$output/aa/benchmarks/report.md" B/op) \
  || die 'could not parse A/A B/op aggregate'
for value in "$aa_time" "$aa_bytes"; do
  [[ $value =~ ^-?[0-9]+([.][0-9]+)?$ ]] || die "invalid A/A aggregate: $value"
  awk -v value="$value" -v limit="$aa_limit" \
    'BEGIN { if (value < 0) value = -value; exit !(value <= limit) }' \
    || die "A/A noise $value% exceeds ±$aa_limit%"
done

[[ $optimized_status == 0 ]] || die 'reference optimization did not pass numeric gates'
optimized_tier=$(sed -n 's/^Overall level: \*\*\([^*]*\)\*\*$/\1/p' \
  "$output/optimized/benchmarks/report.md")
[[ $(wc -l <<<"$optimized_tier" | tr -d '[:space:]') == 1 ]] \
  || die 'could not parse one reference tier'
optimized_rank=$(rank "$optimized_tier") || die "unexpected reference tier: $optimized_tier"
((optimized_rank >= minimum_rank && optimized_rank <= maximum_rank)) \
  || die "reference tier $optimized_tier is outside $minimum_tier-$maximum_tier"

{
  echo '# Java v2 calibration summary'
  echo
  echo "- Baseline: \`$baseline_commit\`"
  echo "- A/A candidate: \`$aa_commit\`"
  echo "- A/A assessor exit: $aa_status"
  echo "- A/A ns/op aggregate: ${aa_time}%"
  echo "- A/A B/op aggregate: ${aa_bytes}%"
  echo "- A/A allowed absolute noise: ${aa_limit}%"
  echo "- Reference candidate: \`$optimized_commit\`"
  echo "- Reference tier: **$optimized_tier** (required $minimum_tier-$maximum_tier)"
  echo "- Samples per side: ${BENCH_SAMPLES:-7}"
  echo
  echo 'Result: calibration gates passed.'
} >"$output/calibration-summary.md"
echo "Calibration evidence published: $output"
