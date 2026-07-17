#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 --isolated-runner <runner> <prepared-baseline> <prepared-candidate> <new-output-directory>" >&2
  exit 2
}

die() {
  echo "run-benchmarks: $*" >&2
  exit 2
}

[[ $# -eq 5 && $1 == --isolated-runner ]] || usage
runner=$2
baseline_input=$3
candidate_input=$4
output_input=$5
[[ -f $runner && ! -L $runner ]] || die 'isolated runner must be a regular file'
[[ -d $baseline_input && ! -L $baseline_input ]] || die 'baseline must be a non-symbolic-link directory'
[[ -d $candidate_input && ! -L $candidate_input ]] || die 'candidate must be a non-symbolic-link directory'
baseline=$(cd -- "$baseline_input" && pwd -P)
candidate=$(cd -- "$candidate_input" && pwd -P)

samples=${BENCH_SAMPLES:-7}
if [[ ! $samples =~ ^[0-9]+$ ]] || ((samples < 5 || samples > 15)); then
  die 'BENCH_SAMPLES must be an integer from 5 through 15'
fi

parent_input=$(dirname -- "$output_input")
name=$(basename -- "$output_input")
[[ -d $parent_input && ! -L $parent_input ]] || die 'output parent must be a non-symbolic-link directory'
parent=$(cd -- "$parent_input" && pwd -P)
output="$parent/$name"
[[ ! -e $output && ! -L $output ]] || die 'output directory must not already exist'
umask 077
mkdir -m 0700 "$output"

random_token() {
  local token
  token=$(LC_ALL=C od -An -N32 -tx1 /dev/urandom | tr -d '[:space:]')
  [[ $token =~ ^[0-9a-f]{64}$ ]] || die 'could not generate sample token'
  printf '%s\n' "$token"
}

fixture_seed=${ASSESSMENT_FIXTURE_SEED:-}
fixture_auth_key=${ASSESSMENT_FIXTURE_AUTH_KEY:-}
[[ $fixture_seed =~ ^[0-9a-f]{64}$ ]] \
  || die 'ASSESSMENT_FIXTURE_SEED must be 256-bit lowercase hex'
[[ $fixture_auth_key =~ ^[0-9a-f]{64}$ ]] \
  || die 'ASSESSMENT_FIXTURE_AUTH_KEY must be 256-bit lowercase hex'
oracle_token=$(random_token)
oracle_capture="$output/.oracle.tmp"
set +e
ASSESSMENT_FIXTURE_SEED="$fixture_seed" ASSESSMENT_FIXTURE_AUTH_KEY="$fixture_auth_key" \
ORACLE_RESULT_TOKEN="$oracle_token" \
  env -u BASH_ENV -u ENV bash --noprofile --norc \
    "$runner" "$baseline" oracle >"$oracle_capture" 2>&1
oracle_status=$?
set -e
if [[ $oracle_status -ne 0 ]]; then
  cat "$oracle_capture" >&2
  die "trusted baseline oracle failed with exit $oracle_status"
fi
oracle_prefix="@@STREAMLENS_JAVA_ORACLE_RESULT $oracle_token "
[[ $(grep -Fc "$oracle_prefix" "$oracle_capture" || true) == 1 \
   && $(wc -l <"$oracle_capture" | tr -d '[:space:]') == 1 ]] \
  || die 'trusted baseline oracle produced no unique authenticated record'
fixture_expected=$(sed -n "s/^$oracle_prefix//p" "$oracle_capture")
[[ $fixture_expected =~ ^streamlens-java-oracle-v3:${fixture_seed}:[0-9a-f]{64}:[0-9a-f]{64}:[0-9a-f]{64}:[0-9a-f]{64}$ ]] \
  || die 'trusted baseline oracle record is malformed or has the wrong seed'
rm -f -- "$oracle_capture"

{
  printf 'fixture.seed=%s\n' "$fixture_seed"
  printf 'fixture.expected=%s\n' "$fixture_expected"
} >"$output/fixture.properties"

baseline_token=$(random_token)
candidate_token=$(random_token)
baseline_output="$output/baseline.txt"
candidate_output="$output/candidate.txt"
: >"$baseline_output"
: >"$candidate_output"

run_sample() {
  local label=$1 prepared=$2 destination=$3 sample=$4 token=$5
  local capture="$output/.${label}-${sample}.tmp"
  set +e
  BENCHMARK_RESULT_TOKEN="$token" \
  ASSESSMENT_FIXTURE_SEED="$fixture_seed" \
  ASSESSMENT_FIXTURE_EXPECTED="$fixture_expected" \
  ASSESSMENT_FIXTURE_AUTH_KEY="$fixture_auth_key" \
    env -u BASH_ENV -u ENV bash --noprofile --norc \
      "$runner" "$prepared" benchmark >"$capture" 2>&1
  local status=$?
  set -e
  if [[ $status -ne 0 ]]; then
    cat "$capture" >&2
    die "$label benchmark sample $sample failed with exit $status"
  fi
  [[ $(grep -Fxc "@@STREAMLENS_JAVA_BENCHMARK_RESULT $token" "$capture" || true) == 1 ]] \
    || die "$label benchmark sample $sample has no unique authenticated result marker"
  {
    printf '@@STREAMLENS_JAVA_SAMPLE BEGIN %s %s\n' "$sample" "$token"
    cat "$capture"
    printf '@@STREAMLENS_JAVA_SAMPLE END %s %s\n' "$sample" "$token"
  } >>"$destination"
  rm -f -- "$capture"
}

for ((sample = 1; sample <= samples; sample++)); do
  if ((sample % 2 == 1)); then
    run_sample baseline "$baseline" "$baseline_output" "$sample" "$baseline_token"
    run_sample candidate "$candidate" "$candidate_output" "$sample" "$candidate_token"
  else
    run_sample candidate "$candidate" "$candidate_output" "$sample" "$candidate_token"
    run_sample baseline "$baseline" "$baseline_output" "$sample" "$baseline_token"
  fi
done

{
  printf 'samples=%s\n' "$samples"
  printf 'alternation=baseline-first-on-odd,candidate-first-on-even\n'
  printf 'jmh.mode=avgt\n'
  printf 'jmh.unit=ns/op\n'
  printf 'jmh.allocation=gc.alloc.rate.norm:B/op\n'
  printf 'jvm.active_processors=1\n'
  printf 'fixture.mode=trusted-randomized-oracle\n'
  printf 'fixture.seed=%s\n' "$fixture_seed"
  printf 'fixture.expected=%s\n' "$fixture_expected"
  printf 'container.image=%s\n' "${ASSESSMENT_DOCKER_IMAGE:-unset}"
} >"$output/environment.txt"

echo "Benchmark samples captured: $output"
