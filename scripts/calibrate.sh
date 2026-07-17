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
  --output-dir <new-directory> [--aa-max-abs-percent <percent>] \
  [--runs <integer 2-20>]

Both candidate commits must be direct assessment submissions based on the
immutable baseline commit. ASSESSMENT_DOCKER_IMAGE must be digest-pinned.

Without --runs, calibration performs one A/A and one reference assessment in
the historical output layout. --runs performs the requested number of paired
assessments on the same host and retains every raw run directory.
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
runs=1
runs_requested=false

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
    --runs)
      [[ $# -ge 2 && $runs_requested == false ]] || usage
      runs=$2
      runs_requested=true
      shift 2
      ;;
    -h | --help) usage ;;
    *) usage ;;
  esac
done

for required in "$baseline" "$baseline_commit" "$aa" "$aa_commit" \
  "$optimized" "$optimized_commit" "$minimum_tier" "$maximum_tier" "$output_input"; do
  [[ -n $required ]] || usage
done
for revision in "$baseline_commit" "$aa_commit" "$optimized_commit"; do
  [[ $revision =~ ^[0-9a-f]{40}$ ]] || usage
done

rank() {
  case "$1" in
    Middle) printf '%s\n' 1 ;;
    Senior) printf '%s\n' 2 ;;
    Staff) printf '%s\n' 3 ;;
    *) return 1 ;;
  esac
}

tier_from_rank() {
  case "$1" in
    1) printf '%s\n' Middle ;;
    2) printf '%s\n' Senior ;;
    3) printf '%s\n' Staff ;;
    *) return 1 ;;
  esac
}

minimum_rank=$(rank "$minimum_tier") || usage
maximum_rank=$(rank "$maximum_tier") || usage
((minimum_rank <= maximum_rank)) || usage
[[ $aa_limit =~ ^[0-9]+([.][0-9]+)?$ ]] || usage
awk -v value="$aa_limit" 'BEGIN { exit !(value > 0 && value <= 100) }' || usage
if [[ $runs_requested == true ]]; then
  [[ $runs =~ ^([2-9]|1[0-9]|20)$ ]] || usage
fi

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

aggregate() {
  local report=$1 metric=$2
  [[ -f $report && ! -L $report ]] || return 1
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

validate_aa_noise() {
  local run_label=$1 metric=$2 value=$3
  [[ $value =~ ^-?[0-9]+([.][0-9]+)?$ ]] \
    || die "invalid A/A $metric aggregate for $run_label: $value"
  awk -v value="$value" -v limit="$aa_limit" \
    'BEGIN { if (value < 0) value = -value; exit !(value <= limit) }' \
    || die "A/A $metric noise for $run_label is $value%, exceeds ±$aa_limit%"
}

numeric_range() {
  (($# > 0)) || return 1
  printf '%s\n' "$@" | awk '
    NR == 1 {
      minimum = $1 + 0
      maximum = $1 + 0
      minimum_value = $1
      maximum_value = $1
      next
    }
    ($1 + 0) < minimum {
      minimum = $1 + 0
      minimum_value = $1
    }
    ($1 + 0) > maximum {
      maximum = $1 + 0
      maximum_value = $1
    }
    END {
      if (NR == 0) exit 1
      print minimum_value, maximum_value
    }
  '
}

maximum_absolute() {
  (($# > 0)) || return 1
  printf '%s\n' "$@" | awk '
    {
      magnitude = $1 + 0
      if (magnitude < 0) magnitude = -magnitude
      if (NR == 1 || magnitude > maximum) {
        maximum = magnitude
        maximum_value = magnitude
      }
    }
    END {
      if (NR == 0) exit 1
      print maximum_value
    }
  '
}

if ((runs > 1)); then
  mkdir -m 0700 "$output/aa" "$output/optimized"
fi

declare -a aa_statuses aa_times aa_bytes
declare -a optimized_statuses optimized_tiers optimized_ranks
for ((run = 1; run <= runs; run++)); do
  if ((runs == 1)); then
    run_label=run-001
    aa_destination="$output/aa"
    aa_log="$output/aa-assessor.txt"
    optimized_destination="$output/optimized"
    optimized_log="$output/optimized-assessor.txt"
  else
    printf -v run_label 'run-%03d' "$run"
    aa_destination="$output/aa/$run_label"
    aa_log="$output/aa/$run_label-assessor.txt"
    optimized_destination="$output/optimized/$run_label"
    optimized_log="$output/optimized/$run_label-assessor.txt"
  fi

  aa_status=$(run_assessment "A/A $run_label" "$aa" "$aa_commit" \
    "$aa_destination" "$aa_log")
  aa_time=$(aggregate "$aa_destination/benchmarks/report.md" ns/op) \
    || die "could not parse A/A ns/op aggregate for $run_label"
  aa_byte=$(aggregate "$aa_destination/benchmarks/report.md" B/op) \
    || die "could not parse A/A B/op aggregate for $run_label"
  validate_aa_noise "$run_label" ns/op "$aa_time"
  validate_aa_noise "$run_label" B/op "$aa_byte"
  aa_statuses+=("$aa_status")
  aa_times+=("$aa_time")
  aa_bytes+=("$aa_byte")

  optimized_status=$(run_assessment "reference $run_label" "$optimized" \
    "$optimized_commit" "$optimized_destination" "$optimized_log")
  [[ $optimized_status == 0 ]] \
    || die "reference optimization $run_label did not pass numeric gates"
  optimized_tier=$(sed -n 's/^Overall level: \*\*\([^*]*\)\*\*$/\1/p' \
    "$optimized_destination/benchmarks/report.md")
  [[ $(wc -l <<<"$optimized_tier" | tr -d '[:space:]') == 1 ]] \
    || die "could not parse one reference tier for $run_label"
  optimized_rank=$(rank "$optimized_tier") \
    || die "unexpected reference tier for $run_label: $optimized_tier"
  ((optimized_rank >= minimum_rank && optimized_rank <= maximum_rank)) \
    || die "reference tier for $run_label, $optimized_tier, is outside $minimum_tier-$maximum_tier"
  optimized_statuses+=("$optimized_status")
  optimized_tiers+=("$optimized_tier")
  optimized_ranks+=("$optimized_rank")
done

read -r aa_time_minimum aa_time_maximum < <(numeric_range "${aa_times[@]}") \
  || die 'could not calculate A/A ns/op range'
read -r aa_bytes_minimum aa_bytes_maximum < <(numeric_range "${aa_bytes[@]}") \
  || die 'could not calculate A/A B/op range'
aa_time_maximum_absolute=$(maximum_absolute "${aa_times[@]}") \
  || die 'could not calculate A/A ns/op maximum absolute noise'
aa_bytes_maximum_absolute=$(maximum_absolute "${aa_bytes[@]}") \
  || die 'could not calculate A/A B/op maximum absolute noise'
minimum_reference_rank=${optimized_ranks[0]}
maximum_reference_rank=${optimized_ranks[0]}
for optimized_rank in "${optimized_ranks[@]}"; do
  ((optimized_rank < minimum_reference_rank)) && minimum_reference_rank=$optimized_rank
  ((optimized_rank > maximum_reference_rank)) && maximum_reference_rank=$optimized_rank
done
minimum_reference_tier=$(tier_from_rank "$minimum_reference_rank") \
  || die 'could not calculate minimum reference tier'
maximum_reference_tier=$(tier_from_rank "$maximum_reference_rank") \
  || die 'could not calculate maximum reference tier'

{
  echo '# Java v3 calibration summary'
  echo
  echo "- Baseline: \`$baseline_commit\`"
  echo "- A/A candidate: \`$aa_commit\`"
  echo "- Reference candidate: \`$optimized_commit\`"
  echo "- Repetitions: $runs"
  echo "- A/A allowed absolute noise: ${aa_limit}%"
  echo "- A/A ns/op range: ${aa_time_minimum}% to ${aa_time_maximum}%"
  echo "- A/A ns/op maximum absolute noise: ${aa_time_maximum_absolute}%"
  echo "- A/A B/op range: ${aa_bytes_minimum}% to ${aa_bytes_maximum}%"
  echo "- A/A B/op maximum absolute noise: ${aa_bytes_maximum_absolute}%"
  echo "- Reference tier range: **$minimum_reference_tier** to **$maximum_reference_tier** (required $minimum_tier-$maximum_tier)"
  echo "- Samples per side: ${BENCH_SAMPLES:-7}"
  echo
  echo '## Per-run results'
  echo
  echo '| Run | A/A exit | A/A ns/op | A/A B/op | Reference exit | Reference tier |'
  echo '| --- | ---: | ---: | ---: | ---: | --- |'
  for index in "${!aa_times[@]}"; do
    printf -v run_label 'run-%03d' "$((index + 1))"
    printf '| %s | %s | %s%% | %s%% | %s | %s |\n' \
      "$run_label" "${aa_statuses[$index]}" "${aa_times[$index]}" \
      "${aa_bytes[$index]}" "${optimized_statuses[$index]}" \
      "${optimized_tiers[$index]}"
  done
  echo
  echo 'Every raw assessor log and report is retained below this output directory.'
  echo
  echo 'Result: calibration gates passed.'
} >"$output/calibration-summary.md"

echo "Calibration evidence published: $output"
