#!/usr/bin/env bash
set -euo pipefail

script_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
root=$(cd -- "$script_directory/.." && pwd -P)
fixture="$script_directory/testdata/runtime-canary"
runner="$script_directory/run-isolated.sh"
configured_image=${ASSESSMENT_DOCKER_IMAGE:-}
construction_image=${configured_image:-example.invalid/streamlens-java@sha256:0000000000000000000000000000000000000000000000000000000000000000}
require_runtime=${REQUIRE_DOCKER_RUNTIME:-0}
temporary=$(mktemp -d "${TMPDIR:-/tmp}/streamlens-java-isolation-test.XXXXXX")
trap 'rm -rf -- "$temporary"' EXIT HUP INT TERM

fail() {
  echo "isolation-test: $*" >&2
  exit 1
}

assert_contains() {
  [[ $1 == *"$2"* ]] || fail "constructed command is missing: $2"
}

[[ $require_runtime == 0 || $require_runtime == 1 ]] \
  || fail 'REQUIRE_DOCKER_RUNTIME must be 0 or 1'
[[ -x $fixture/gradlew ]] || fail 'runtime canary gradlew must be executable'

test_command=$(ASSESSMENT_DOCKER_IMAGE="$construction_image" \
  bash "$runner" --print-command "$fixture" test)
for required in \
  '--pull=never' '--read-only' '--network=none' '--ipc=none' '--log-driver=none' \
  '--cap-drop=ALL' '--security-opt=no-new-privileges:true' '--pids-limit=256' \
  '--memory=2g' '--memory-swap=2g' '--cpus=2' '--ulimit=nofile=1024:1024' \
  '--ulimit=core=0:0' '--user=65532:65532' \
  'tmpfs=/tmp:rw\,nosuid\,nodev\,exec' 'target=/workspace\,readonly' \
  'GRADLE_USER_HOME=/tmp/gradle-home' 'HTTP_PROXY=' 'HTTPS_PROXY=' 'ALL_PROXY=' \
  'NO_PROXY=' 'validated-CID cleanup' 'deadline=360s' 'output-limit=8388608'; do
  assert_contains "$test_command" "$required"
done
mount_count=$(grep -o -- '--mount' <<<"$test_command" | wc -l | tr -d '[:space:]')
[[ $mount_count == 1 ]] || fail "expected one host mount, found $mount_count"
[[ $test_command != *'--privileged'* && $test_command != *'--name='* ]] \
  || fail 'constructed command enables an unsafe Docker option'

benchmark_command=$(ASSESSMENT_DOCKER_IMAGE="$construction_image" \
  bash "$runner" --print-command "$fixture" benchmark)
for required in 'BenchmarkVerifier' 'AnalyzerBenchmark' '-wi 2' '-i 3' '-bm avgt' \
  '-prof gc' '--cpus=1' '\<random-result-token\>'; do
  assert_contains "$benchmark_command" "$required"
done
[[ $benchmark_command != *'fixtureSeed=local'* \
   && $benchmark_command != *'fixtureExpected=local'* \
   && $benchmark_command != *'fixtureAuthKey='* ]] \
  || fail 'authoritative benchmark command fell back to local fixture parameters'

profile_parent="$temporary/profile-parent"
mkdir -m 0700 "$profile_parent"
profile_command=$(ASSESSMENT_DOCKER_IMAGE="$construction_image" \
  bash "$runner" --print-command "$fixture" \
  profile cpu Balanced 1s "$profile_parent/result")
for required in 'BenchmarkVerifier' 'StartFlightRecording' '-jvmArgsAppend' 'Balanced' 'recording.jfr' \
  'summary.txt' 'hotspots.txt' 'jmh.txt' '\<random-ready-token\>'; do
  assert_contains "$profile_command" "$required"
done
[[ $profile_command != *'fixtureSeed=local'* \
   && $profile_command != *'fixtureExpected=local'* \
   && $profile_command != *'fixtureAuthKey='* ]] \
  || fail 'authoritative profile command fell back to local fixture parameters'
profile_mount_count=$(grep -o -- '--mount' <<<"$profile_command" | wc -l | tr -d '[:space:]')
[[ $profile_mount_count == 1 ]] || fail 'profile command exposes an additional host mount'
[[ $profile_command != *"$profile_parent/result"* ]] \
  || fail 'profile output directory leaked into the container command'

if ASSESSMENT_DOCKER_IMAGE="$construction_image" bash "$runner" --print-command \
    "$fixture" profile cpu Unknown 1s "$profile_parent/bad" >/dev/null 2>&1; then
  fail 'invalid profile scenario was accepted'
fi
if ASSESSMENT_DOCKER_IMAGE="$construction_image" bash "$runner" --print-command \
    "$fixture" profile cpu Balanced 0s "$profile_parent/bad" >/dev/null 2>&1; then
  fail 'zero profile duration was accepted'
fi
mkdir "$profile_parent/existing"
if ASSESSMENT_DOCKER_IMAGE="$construction_image" bash "$runner" --print-command \
    "$fixture" profile cpu Balanced 1s "$profile_parent/existing" >/dev/null 2>&1; then
  fail 'existing profile output was accepted'
fi
unsafe_parent="$temporary/unsafe"
mkdir "$unsafe_parent"
chmod 0777 "$unsafe_parent"
if ASSESSMENT_DOCKER_IMAGE="$construction_image" bash "$runner" --print-command \
    "$fixture" profile cpu Balanced 1s "$unsafe_parent/result" >/dev/null 2>&1; then
  fail 'world-writable profile parent was accepted'
fi
if ASSESSMENT_DOCKER_IMAGE='eclipse-temurin:21-jdk' bash "$runner" --print-command \
    "$fixture" test >/dev/null 2>&1; then
  fail 'mutable Docker image was accepted'
fi
if ASSESSMENT_DOCKER_DEADLINE_SECONDS=0 ASSESSMENT_DOCKER_IMAGE="$construction_image" \
    bash "$runner" --print-command "$fixture" test >/dev/null 2>&1; then
  fail 'zero execution deadline was accepted'
fi
if ASSESSMENT_DOCKER_OUTPUT_LIMIT_BYTES=1024 ASSESSMENT_DOCKER_IMAGE="$construction_image" \
    bash "$runner" --print-command "$fixture" test >/dev/null 2>&1; then
  fail 'unsafe output cap was accepted'
fi

fake_bin="$temporary/fake-bin"
fake_tmp="$temporary/fake-tmp"
fake_state="$temporary/fake-state"
fake_log="$temporary/fake-docker.log"
mkdir -m 0700 "$fake_bin" "$fake_tmp" "$fake_state"
cp "$script_directory/testdata/fake-docker.sh" "$fake_bin/docker"
chmod 0755 "$fake_bin/docker"
expected_cid=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
fake_fixture_seed=1111111111111111111111111111111111111111111111111111111111111111
fake_fixture_key=2222222222222222222222222222222222222222222222222222222222222222
fake_fixture_digest=3333333333333333333333333333333333333333333333333333333333333333
fake_fixture_expected="streamlens-java-oracle-v2:$fake_fixture_seed:$fake_fixture_digest:$fake_fixture_digest:$fake_fixture_digest:$fake_fixture_digest"

run_fake() {
  local mode=$1 cleanup=$2 deadline=$3 cap=$4
  rm -f -- "$fake_state/stopped"
  PATH="$fake_bin:$PATH" TMPDIR="$fake_tmp" \
    FAKE_DOCKER_LOG="$fake_log" FAKE_DOCKER_STATE="$fake_state" \
    FAKE_DOCKER_MODE="$mode" FAKE_DOCKER_CLEANUP_MODE="$cleanup" \
    ASSESSMENT_DOCKER_DEADLINE_SECONDS="$deadline" \
    ASSESSMENT_DOCKER_OUTPUT_LIMIT_BYTES="$cap" \
    ASSESSMENT_DOCKER_IMAGE="$construction_image" \
    bash "$runner" "$fixture" test
}

run_fake_profile() {
  local output=$1 copy_mode=$2 cleanup=$3 deadline=${4:-10} control=${5:-2}
  rm -f -- "$fake_state/stopped"
  PATH="$fake_bin:$PATH" TMPDIR="$fake_tmp" \
    FAKE_DOCKER_LOG="$fake_log" FAKE_DOCKER_STATE="$fake_state" \
    FAKE_DOCKER_MODE=profile FAKE_DOCKER_COPY_MODE="$copy_mode" \
    FAKE_DOCKER_CLEANUP_MODE="$cleanup" \
    ASSESSMENT_FIXTURE_SEED="$fake_fixture_seed" ASSESSMENT_FIXTURE_EXPECTED="$fake_fixture_expected" \
    ASSESSMENT_FIXTURE_AUTH_KEY="$fake_fixture_key" \
    ASSESSMENT_DOCKER_DEADLINE_SECONDS="$deadline" \
    ASSESSMENT_DOCKER_CONTROL_DEADLINE_SECONDS="$control" \
    ASSESSMENT_DOCKER_OUTPUT_LIMIT_BYTES=4096 \
    ASSESSMENT_DOCKER_IMAGE="$construction_image" \
    bash "$runner" "$fixture" profile cpu Balanced 1s "$output"
}

assert_safe_cleanup() {
  grep -Fqx "rm cid=$expected_cid" "$fake_log" \
    || fail "$1 did not clean by the validated CID"
}

: >"$fake_log"
success=$(run_fake success success 10 4096)
[[ $success == *'fake docker success canary'* ]] || fail 'successful output was lost'
assert_safe_cleanup success

: >"$fake_log"
set +e
deadline_output=$(run_fake hang success 1 4096 2>&1)
deadline_status=$?
set -e
[[ $deadline_status -ne 0 && $deadline_output == *'host deadline exceeded'* ]] \
  || fail 'hung Docker process did not fail at its host deadline'
assert_safe_cleanup deadline

: >"$fake_log"
set +e
overflow_output=$(run_fake overflow success 10 4096 2>&1)
overflow_status=$?
set -e
[[ $overflow_status -ne 0 && $overflow_output == *'output exceeded 4096 bytes'* ]] \
  || fail 'overflowing Docker output did not fail closed'
(( ${#overflow_output} < 8192 )) || fail 'overflow error output was not bounded'
assert_safe_cleanup overflow

: >"$fake_log"
set +e
cleanup_output=$(run_fake success failure 10 4096 2>&1)
cleanup_status=$?
set -e
[[ $cleanup_status -ne 0 && $cleanup_output == *'failed validated-CID cleanup'* ]] \
  || fail 'cleanup failure did not fail closed'
cleanup_count=$(grep -Fxc "rm cid=$expected_cid" "$fake_log" || true)
[[ $cleanup_count == 2 ]] || fail "cleanup was not attempted twice: $cleanup_count"

: >"$fake_log"
fake_profile="$profile_parent/fake-success"
profile_output=$(run_fake_profile "$fake_profile" success success)
[[ $profile_output == *'Profile artifacts published:'* ]] || fail 'profile was not published'
for artifact in recording.jfr summary.txt hotspots.txt jmh.txt; do
  [[ -s $fake_profile/$artifact && ! -L $fake_profile/$artifact ]] \
    || fail "fake profile is missing $artifact"
  grep -Fqx "exec cid=$expected_cid artifact=$artifact" "$fake_log" \
    || fail "profile extraction did not use validated CID for $artifact"
done
grep -Fqx "stop cid=$expected_cid" "$fake_log" || fail 'profile stop did not use validated CID'
assert_safe_cleanup profile-success

: >"$fake_log"
absolute_output="$profile_parent/fake-absolute-deadline"
absolute_start=$SECONDS
set +e
absolute_log=$(run_fake_profile "$absolute_output" hang success 1 5 2>&1)
absolute_status=$?
set -e
absolute_elapsed=$((SECONDS - absolute_start))
[[ $absolute_status -ne 0 && ! -e $absolute_output ]] \
  || fail 'hung profile extraction unexpectedly published artifacts'
((absolute_elapsed < 4)) \
  || fail "profile exceeded its one absolute deadline (${absolute_elapsed}s): $absolute_log"
assert_safe_cleanup profile-deadline

runtime_unavailable() {
  if [[ $require_runtime == 1 ]]; then
    fail "$1"
  fi
  echo "isolation command/fail-closed tests passed; Docker canary skipped ($1)"
  exit 0
}

[[ -n $configured_image ]] || runtime_unavailable 'ASSESSMENT_DOCKER_IMAGE is not configured'
command -v docker >/dev/null 2>&1 || runtime_unavailable 'docker CLI is unavailable'
docker info >/dev/null 2>&1 || runtime_unavailable 'docker daemon is unavailable'
docker image inspect "$configured_image" >/dev/null 2>&1 \
  || runtime_unavailable "pinned image is not present: $configured_image"

restriction_output=$(ASSESSMENT_DOCKER_IMAGE="$configured_image" \
  bash "$runner" "$fixture" test 2>&1) \
  || fail "real restriction canary failed: $restriction_output"
[[ $restriction_output == *'restriction canary passed'* && ! -e $fixture/host-write ]] \
  || fail 'real restriction canary did not prove read-only/no-network execution'

functional_output=$(ASSESSMENT_DOCKER_IMAGE="$configured_image" \
  bash "$runner" "$root" test 2>&1) \
  || fail "real baseline functional canary failed: $functional_output"

runtime_fixture_seed=$(LC_ALL=C od -An -N32 -tx1 /dev/urandom | tr -d '[:space:]')
runtime_fixture_key=$(LC_ALL=C od -An -N32 -tx1 /dev/urandom | tr -d '[:space:]')
runtime_oracle_token=$(LC_ALL=C od -An -N32 -tx1 /dev/urandom | tr -d '[:space:]')
oracle_output=$(ASSESSMENT_FIXTURE_SEED="$runtime_fixture_seed" ASSESSMENT_FIXTURE_AUTH_KEY="$runtime_fixture_key" \
  ORACLE_RESULT_TOKEN="$runtime_oracle_token" ASSESSMENT_DOCKER_IMAGE="$configured_image" \
  bash "$runner" "$root" oracle 2>&1) \
  || fail "real randomized oracle canary failed: $oracle_output"
oracle_prefix="@@STREAMLENS_JAVA_ORACLE_RESULT $runtime_oracle_token "
[[ $(grep -Fc "$oracle_prefix" <<<"$oracle_output" || true) == 1 ]] \
  || fail 'real oracle canary produced no authenticated result'
runtime_fixture_expected=$(sed -n "s/^$oracle_prefix//p" <<<"$oracle_output")
[[ $runtime_fixture_expected =~ ^streamlens-java-oracle-v2:${runtime_fixture_seed}:[0-9a-f]{64}:[0-9a-f]{64}:[0-9a-f]{64}:[0-9a-f]{64}$ ]] \
  || fail 'real oracle canary record was malformed'

benchmark_output=$(ASSESSMENT_FIXTURE_SEED="$runtime_fixture_seed" \
  ASSESSMENT_FIXTURE_EXPECTED="$runtime_fixture_expected" ASSESSMENT_FIXTURE_AUTH_KEY="$runtime_fixture_key" \
  ASSESSMENT_DOCKER_IMAGE="$configured_image" \
  bash "$runner" "$root" benchmark 2>&1) \
  || fail "real baseline benchmark canary failed: $benchmark_output"
grep -Eq '^@@STREAMLENS_JAVA_BENCHMARK_RESULT [0-9a-f]{64}$' <<<"$benchmark_output" \
  || fail 'real benchmark canary produced no authenticated result'
for kind in cpu alloc; do
  runtime_profile="$profile_parent/runtime-$kind"
  profile_output=$(ASSESSMENT_FIXTURE_SEED="$runtime_fixture_seed" \
    ASSESSMENT_FIXTURE_EXPECTED="$runtime_fixture_expected" ASSESSMENT_FIXTURE_AUTH_KEY="$runtime_fixture_key" \
    ASSESSMENT_DOCKER_IMAGE="$configured_image" \
    bash "$runner" "$root" profile "$kind" Balanced 1s "$runtime_profile" 2>&1) \
    || fail "real baseline $kind profile canary failed: $profile_output"
  for artifact in recording.jfr summary.txt hotspots.txt jmh.txt; do
    [[ -s $runtime_profile/$artifact && ! -L $runtime_profile/$artifact ]] \
      || fail "real $kind profile canary is missing $artifact"
  done
done
echo 'isolation command, fail-closed, and exact-image runtime canaries passed.'
