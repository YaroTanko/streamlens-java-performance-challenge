#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

usage() {
  cat >&2 <<USAGE
usage: $0 <baseline-checkout> <candidate-checkout> <baseline-commit> <candidate-base-commit> <candidate-commit> <new-output-directory>

ASSESSMENT_DOCKER_IMAGE must be an immutable name@sha256:<64 lowercase hex> reference.
USAGE
  exit 2
}

die() {
  echo "assess: $*" >&2
  exit 2
}

trusted_git() {
  env \
    -u GIT_DIR -u GIT_WORK_TREE -u GIT_COMMON_DIR -u GIT_INDEX_FILE \
    -u GIT_OBJECT_DIRECTORY -u GIT_ALTERNATE_OBJECT_DIRECTORIES \
    -u GIT_CONFIG -u GIT_CONFIG_COUNT -u GIT_CONFIG_PARAMETERS \
    -u GIT_CONFIG_SYSTEM -u GIT_CONFIG_GLOBAL -u GIT_CONFIG_NOSYSTEM \
    -u GIT_EXEC_PATH -u GIT_EXTERNAL_DIFF -u GIT_DIFF_OPTS -u GIT_PAGER \
    -u GIT_ASKPASS -u GIT_SSH -u GIT_SSH_COMMAND \
    -u GIT_CEILING_DIRECTORIES -u GIT_DISCOVERY_ACROSS_FILESYSTEM \
    -u GIT_TRACE -u GIT_TRACE2 -u GIT_TRACE2_EVENT -u GIT_TRACE2_PERF \
    -u GIT_TRACE_PACKET -u GIT_TRACE_PERFORMANCE -u GIT_TRACE_SETUP \
    -u GIT_TRACE_SHALLOW -u GIT_TRACE_PACK_ACCESS \
    GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_OPTIONAL_LOCKS=0 \
    GIT_TERMINAL_PROMPT=0 GIT_NO_REPLACE_OBJECTS=1 \
    git -c core.fsmonitor=false -c core.hooksPath=/dev/null "$@"
}

physical_checkout() {
  local input=$1 label=$2
  [[ -d $input && ! -L $input ]] || die "$label must be a non-symbolic-link directory"
  local directory top
  directory=$(cd -- "$input" && pwd -P)
  top=$(trusted_git -C "$directory" rev-parse --show-toplevel 2>/dev/null || true)
  [[ -n $top ]] || die "$label is not a Git checkout"
  top=$(cd -- "$top" && pwd -P)
  [[ $top == "$directory" ]] || die "$label must name the checkout root"
  printf '%s\n' "$directory"
}

validate_checkout() {
  local directory=$1 expected=$2 label=$3 actual status entry git_directory replacements unsafe_metadata
  git_directory=$(trusted_git -C "$directory" rev-parse --absolute-git-dir 2>/dev/null || true)
  [[ -d $git_directory && ! -L $git_directory ]] \
    || die "$label must use a fresh regular Git metadata directory"
  for unsafe_metadata in \
    "$git_directory/shallow" "$git_directory/info/grafts" \
    "$git_directory/objects/info/alternates"; do
    [[ ! -e $unsafe_metadata && ! -L $unsafe_metadata ]] \
      || die "$label uses unsupported non-self-contained Git metadata: $unsafe_metadata"
  done
  replacements=$(trusted_git -C "$directory" for-each-ref --format='%(refname)' refs/replace)
  [[ -z $replacements ]] || die "$label contains forbidden Git replacement refs"
  [[ $(trusted_git -C "$directory" cat-file -t "$expected" 2>/dev/null || true) == commit ]] \
    || die "$label does not contain commit $expected"
  actual=$(trusted_git -C "$directory" rev-parse --verify 'HEAD^{commit}')
  [[ $actual == "$expected" ]] || die "$label HEAD is $actual, expected $expected"
  status=$(trusted_git -C "$directory" -c core.quotePath=true status \
    --porcelain=v1 --untracked-files=all --ignored=matching)
  [[ -z $status ]] || die "$label must be pristine (including ignored files)"
  while IFS= read -r entry; do
    [[ -z $entry || $entry == 'H '* ]] \
      || die "$label uses unsupported assume-unchanged or skip-worktree flags"
  done < <(trusted_git -C "$directory" -c core.quotePath=true ls-files -v)
}

require_file() {
  [[ -f $1 && ! -L $1 ]] || die "trusted baseline file is missing or unsafe: $1"
}

paths_overlap() {
  local left=$1 right=$2
  [[ $left == "$right" || $left == "$right/"* || $right == "$left/"* ]]
}

directory_owner_and_mode() {
  if stat -f '%u %Lp' "$1" >/dev/null 2>&1; then
    stat -f '%u %Lp' "$1"
  else
    stat -c '%u %a' "$1"
  fi
}

[[ $# -eq 6 ]] || usage
baseline_input=$1
candidate_input=$2
baseline_commit=$3
candidate_base_commit=$4
candidate_commit=$5
output_input=$6
for path in "$baseline_input" "$candidate_input" "$output_input"; do
  [[ $path != *$'\n'* && $path != *$'\r'* ]] || die 'paths containing newlines are unsupported'
done
for revision in "$baseline_commit" "$candidate_base_commit" "$candidate_commit"; do
  [[ $revision =~ ^[0-9a-f]{40}$ ]] || die 'revisions must be full lowercase 40-character SHAs'
done
[[ $candidate_base_commit == "$baseline_commit" ]] \
  || die 'java-v6 candidate base must equal the immutable assessment baseline'

baseline=$(physical_checkout "$baseline_input" 'baseline checkout')
candidate=$(physical_checkout "$candidate_input" 'candidate checkout')
[[ $baseline != "$candidate" ]] || die 'baseline and candidate checkouts must be separate'
validate_checkout "$baseline" "$baseline_commit" 'baseline checkout'
validate_checkout "$candidate" "$candidate_commit" 'candidate checkout'
trusted_git -C "$candidate" merge-base --is-ancestor "$candidate_base_commit" "$candidate_commit" \
  || die 'candidate commit is not descended from its assessment base'

image=${ASSESSMENT_DOCKER_IMAGE:-}
[[ $image == *@sha256:* ]] || die 'ASSESSMENT_DOCKER_IMAGE must use an immutable @sha256 digest'
image_name=${image%@sha256:*}
image_digest=${image##*@sha256:}
[[ $image_name =~ ^[A-Za-z0-9][A-Za-z0-9._/:-]*$ && $image_digest =~ ^[0-9a-f]{64}$ ]] \
  || die 'invalid digest-pinned assessment image'

samples=${BENCH_SAMPLES:-7}
profile_scenario=${PROFILE_SCENARIO:-Balanced}
profile_time=${PROFILE_TIME:-2s}
if [[ ! $samples =~ ^[0-9]+$ ]] || ((samples < 5 || samples > 15)); then
  die 'BENCH_SAMPLES must be 5-15'
fi
case "$profile_scenario" in
  Balanced | HighCardinality | MostlyFiltered) ;;
  *) die 'invalid PROFILE_SCENARIO' ;;
esac
[[ $profile_time =~ ^[1-9][0-9]*(ms|s|m)$ ]] || die 'invalid PROFILE_TIME'

check="$baseline/scripts/check-protected.sh"
prepare="$baseline/scripts/prepare-candidate.sh"
isolated="$baseline/scripts/run-isolated.sh"
benchmarks="$baseline/scripts/run-benchmarks.sh"
comparator_source="$baseline/scripts/trusted/BenchmarkCompare.java"
manifest_source="$baseline/scripts/trusted/EvidenceManifest.java"
for file in "$check" "$prepare" "$isolated" "$benchmarks" "$comparator_source" "$manifest_source"; do
  require_file "$file"
done
command -v javac >/dev/null 2>&1 || die 'javac is required on the trusted host'
command -v java >/dev/null 2>&1 || die 'java is required on the trusted host'

parent_input=$(dirname -- "$output_input")
name=$(basename -- "$output_input")
[[ -n $name && $name != . && $name != .. && $name != / ]] || die 'invalid output directory'
[[ -d $parent_input && ! -L $parent_input ]] || die 'output parent must be a non-symbolic-link directory'
parent=$(cd -- "$parent_input" && pwd -P)
output="$parent/$name"
[[ ! -e $output && ! -L $output ]] || die 'output directory must not already exist'
paths_overlap "$output" "$baseline" && die 'output overlaps baseline checkout'
paths_overlap "$output" "$candidate" && die 'output overlaps candidate checkout'
read -r owner mode < <(directory_owner_and_mode "$parent")
[[ $owner == "$EUID" ]] || die 'output parent must be owned by current user'
mode_value=$((8#$mode))
(( (mode_value & 0022) == 0 )) || die 'output parent must not be group- or world-writable'
umask 077
mkdir -m 0700 -- "$output"

scope_output="$output/scope.txt"
set +e
(
  cd -- "$candidate"
  env -u BASH_ENV -u ENV bash --noprofile --norc \
    "$check" "$candidate_base_commit" "$candidate_commit"
) >"$scope_output" 2>&1
scope_status=$?
set -e
if [[ $scope_status -ne 0 ]]; then
  cat "$scope_output" >&2
  die "protected-scope validation failed with exit $scope_status"
fi
mkdir -m 0700 "$output/submission"
trusted_git -C "$candidate" cat-file blob \
  "$candidate_commit:src/main/java/com/streamlens/analyzer/Analyzer.java" \
  >"$output/submission/Analyzer.java"
trusted_git -C "$candidate" cat-file blob "$candidate_commit:OPTIMIZATION.md" \
  >"$output/submission/OPTIMIZATION.md"
chmod 0600 "$output/submission/Analyzer.java" "$output/submission/OPTIMIZATION.md"

work=$(mktemp -d "${TMPDIR:-/tmp}/streamlens-java-assess.XXXXXX")
runtime_root=$(mktemp -d "${TMPDIR:-/tmp}/streamlens-java-runtime.XXXXXX")
chmod 0700 "$work"
# The fixed non-root Docker UID needs to traverse the synthetic source tree,
# while its random, owner-controlled parent remains non-writable to everyone
# else. Evidence and trusted host-only state stay under the private work root.
chmod 0755 "$runtime_root"
trap 'rm -rf -- "$work" "$runtime_root"' EXIT HUP INT TERM
prepared_baseline="$runtime_root/prepared-baseline"
prepared_candidate="$runtime_root/prepared-candidate"
classes="$work/trusted-classes"
mkdir -m 0700 "$classes"

env -u BASH_ENV -u ENV bash --noprofile --norc \
  "$prepare" "$baseline" "$baseline" "$baseline_commit" "$prepared_baseline" >/dev/null
env -u BASH_ENV -u ENV bash --noprofile --norc \
  "$prepare" "$baseline" "$candidate" "$candidate_commit" "$prepared_candidate" >/dev/null

javac --release 21 -proc:none -Xlint:all -Werror -d "$classes" \
  "$comparator_source" "$manifest_source" \
  || die 'could not compile trusted assessment tools'

java -version >"$output/host-java-version.txt" 2>&1
functional="$output/functional.txt"
set +e
env -u BASH_ENV -u ENV bash --noprofile --norc \
  "$isolated" "$prepared_candidate" test >"$functional" 2>&1
functional_status=$?
set -e
if [[ $functional_status -ne 0 ]]; then
  cat "$functional" >&2
  die "functional tests failed with exit $functional_status"
fi

random_entropy() {
  local value
  value=$(LC_ALL=C od -An -N32 -tx1 /dev/urandom | tr -d '[:space:]')
  [[ $value =~ ^[0-9a-f]{64}$ ]] || die 'could not generate assessment fixture entropy'
  printf '%s\n' "$value"
}
fixture_seed=$(random_entropy)
fixture_auth_key=$(random_entropy)
benchmark_directory="$output/benchmarks"
BENCH_SAMPLES="$samples" ASSESSMENT_FIXTURE_SEED="$fixture_seed" \
ASSESSMENT_FIXTURE_AUTH_KEY="$fixture_auth_key" \
env -u BASH_ENV -u ENV bash --noprofile --norc \
  "$benchmarks" --isolated-runner "$isolated" \
  "$prepared_baseline" "$prepared_candidate" "$benchmark_directory"

fixture_file="$benchmark_directory/fixture.properties"
[[ -f $fixture_file && ! -L $fixture_file \
   && $(wc -l <"$fixture_file" | tr -d '[:space:]') == 2 ]] \
  || die 'benchmark fixture contract file is missing or malformed'
fixture_seed=$(sed -n 's/^fixture[.]seed=//p' "$fixture_file")
fixture_expected=$(sed -n 's/^fixture[.]expected=//p' "$fixture_file")
[[ $fixture_seed =~ ^[0-9a-f]{64}$ \
   && $fixture_auth_key =~ ^[0-9a-f]{64}$ \
   && $fixture_expected =~ ^streamlens-java-oracle-v6:${fixture_seed}:[0-9a-f]{64}:[0-9a-f]{64}:[0-9a-f]{64}:[0-9a-f]{64}$ ]] \
  || die 'benchmark fixture contract values are invalid'

report="$benchmark_directory/report.md"
set +e
java -cp "$classes" BenchmarkCompare \
  --baseline "$benchmark_directory/baseline.txt" \
  --candidate "$benchmark_directory/candidate.txt" \
  --output "$report" --min-samples "$samples"
comparator_status=$?
set -e
case "$comparator_status" in
  0 | 1 | 2) ;;
  *) comparator_status=2 ;;
esac
[[ -s $report && ! -L $report ]] || die 'benchmark comparator produced no safe report'

profile_parent="$output/profiles"
mkdir -m 0700 "$profile_parent"
profile_cpu_log="$output/profile-cpu.txt"
profile_alloc_log="$output/profile-alloc.txt"
set +e
ASSESSMENT_FIXTURE_SEED="$fixture_seed" ASSESSMENT_FIXTURE_EXPECTED="$fixture_expected" \
ASSESSMENT_FIXTURE_AUTH_KEY="$fixture_auth_key" \
env -u BASH_ENV -u ENV bash --noprofile --norc \
  "$isolated" "$prepared_candidate" profile cpu "$profile_scenario" "$profile_time" \
  "$profile_parent/cpu" >"$profile_cpu_log" 2>&1
profile_cpu_status=$?
ASSESSMENT_FIXTURE_SEED="$fixture_seed" ASSESSMENT_FIXTURE_EXPECTED="$fixture_expected" \
ASSESSMENT_FIXTURE_AUTH_KEY="$fixture_auth_key" \
env -u BASH_ENV -u ENV bash --noprofile --norc \
  "$isolated" "$prepared_candidate" profile alloc "$profile_scenario" "$profile_time" \
  "$profile_parent/alloc" >"$profile_alloc_log" 2>&1
profile_alloc_status=$?
set -e

{
  printf 'assessment_version=java-v6\n'
  printf 'scope=passed\n'
  printf 'functional=passed\n'
  printf 'comparator_exit=%s\n' "$comparator_status"
  printf 'profile_cpu_exit=%s\n' "$profile_cpu_status"
  printf 'profile_alloc_exit=%s\n' "$profile_alloc_status"
} >"$output/assessment-status.txt"

manifest=(
  --root "$output" --output-dir "$output/evidence"
  --revision "assessment_baseline=$baseline_commit"
  --revision "candidate_base=$candidate_base_commit"
  --revision "candidate=$candidate_commit"
  --parameter assessment.version=java-v6
  --parameter "runtime.image=$image"
  --parameter "benchmark.samples=$samples"
  --parameter benchmark.mode=avgt
  --parameter benchmark.unit=ns/op
  --parameter benchmark.allocation=gc.alloc.rate.norm:B/op
  --parameter fixture.mode=trusted-randomized-oracle
  --parameter "fixture.seed=$fixture_seed"
  --parameter "fixture.expected=$fixture_expected"
  --parameter "profile.scenario=$profile_scenario"
  --parameter "profile.time=$profile_time"
  --environment "container.image=$image"
  --runner "host.os=$(uname -s)"
  --runner "host.arch=$(uname -m)"
  --runner "github.image_os=${ImageOS:-local}"
  --runner "github.image_version=${ImageVersion:-local}"
  --artifact scope.output=scope.txt
  --artifact submission.analyzer=submission/Analyzer.java
  --artifact submission.optimization=submission/OPTIMIZATION.md
  --artifact functional.output=functional.txt
  --artifact host.java=host-java-version.txt
  --artifact baseline.samples=benchmarks/baseline.txt
  --artifact candidate.samples=benchmarks/candidate.txt
  --artifact benchmark.environment=benchmarks/environment.txt
  --artifact benchmark.fixture=benchmarks/fixture.properties
  --artifact comparison.report=benchmarks/report.md
  --artifact profile.cpu.output=profile-cpu.txt
  --artifact profile.alloc.output=profile-alloc.txt
  --artifact assessment.status=assessment-status.txt
)
if [[ $profile_cpu_status -eq 0 ]]; then
  for artifact in recording.jfr summary.txt hotspots.txt jmh.txt; do
    manifest+=(--artifact "profile.cpu.${artifact//./_}=profiles/cpu/$artifact")
  done
fi
if [[ $profile_alloc_status -eq 0 ]]; then
  for artifact in recording.jfr summary.txt hotspots.txt jmh.txt; do
    manifest+=(--artifact "profile.alloc.${artifact//./_}=profiles/alloc/$artifact")
  done
fi
java -cp "$classes" EvidenceManifest "${manifest[@]}" \
  || die 'evidence manifest generation failed'

if [[ $profile_cpu_status -ne 0 || $profile_alloc_status -ne 0 ]]; then
  echo "assess: profile capture failed (cpu=$profile_cpu_status alloc=$profile_alloc_status)" >&2
  exit 2
fi
exit "$comparator_status"
