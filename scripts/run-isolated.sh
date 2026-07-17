#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'USAGE'
usage:
  run-isolated.sh [--print-command] <prepared-directory> test
  run-isolated.sh [--print-command] <prepared-directory> oracle
  run-isolated.sh [--print-command] <prepared-directory> benchmark
  run-isolated.sh [--print-command] <prepared-directory> profile <cpu|alloc> <scenario> <duration> <new-output-directory>
USAGE
  exit 2
}

die() {
  echo "run-isolated: $*" >&2
  exit 2
}

paths_overlap() {
  local left=$1 right=$2
  [[ $left == "$right" || $left == "$right/"* || $right == "$left/"* ]]
}

directory_owner_and_mode() {
  local directory=$1
  if stat -f '%u %Lp' "$directory" >/dev/null 2>&1; then
    stat -f '%u %Lp' "$directory"
  else
    stat -c '%u %a' "$directory"
  fi
}

print_command=false
if [[ ${1:-} == --print-command ]]; then
  print_command=true
  shift
fi
[[ $# -ge 2 ]] || usage
prepared_input=$1
mode=$2
shift 2

[[ $prepared_input != *$'\n'* && $prepared_input != *$'\r'* && $prepared_input != *,* ]] \
  || die 'prepared path contains an unsupported character'
[[ -d $prepared_input && ! -L $prepared_input ]] || die 'prepared directory must be a non-symbolic-link directory'
prepared=$(cd -- "$prepared_input" && pwd -P)
[[ $prepared != *$'\n'* && $prepared != *$'\r'* && $prepared != *,* ]] \
  || die 'canonical prepared path contains an unsupported character'
[[ -f $prepared/gradlew && ! -L $prepared/gradlew ]] || die 'prepared tree must contain a regular gradlew'

profile_mode=false
profile_kind=
profile_scenario=
profile_duration=
profile_output=
profile_parent=
profile_name=
profile_token=
benchmark_token=
oracle_token=
fixture_seed=
fixture_expected=
fixture_auth_key=
container_cpus=2

load_fixture_entropy() {
  if [[ $print_command == true ]]; then
    fixture_seed=1111111111111111111111111111111111111111111111111111111111111111
    fixture_auth_key=2222222222222222222222222222222222222222222222222222222222222222
  else
    fixture_seed=${ASSESSMENT_FIXTURE_SEED:-}
    fixture_auth_key=${ASSESSMENT_FIXTURE_AUTH_KEY:-}
    [[ $fixture_seed =~ ^[0-9a-f]{64}$ ]] \
      || die 'ASSESSMENT_FIXTURE_SEED must be 256-bit lowercase hex'
    [[ $fixture_auth_key =~ ^[0-9a-f]{64}$ ]] \
      || die 'ASSESSMENT_FIXTURE_AUTH_KEY must be 256-bit lowercase hex'
  fi
}

load_fixture_contract() {
  load_fixture_entropy
  if [[ $print_command == true ]]; then
    fixture_expected="streamlens-java-oracle-v6:$fixture_seed:\
3333333333333333333333333333333333333333333333333333333333333333:\
4444444444444444444444444444444444444444444444444444444444444444:\
5555555555555555555555555555555555555555555555555555555555555555:\
6666666666666666666666666666666666666666666666666666666666666666"
  else
    fixture_expected=${ASSESSMENT_FIXTURE_EXPECTED:-}
    [[ $fixture_expected =~ ^streamlens-java-oracle-v6:${fixture_seed}:[0-9a-f]{64}:[0-9a-f]{64}:[0-9a-f]{64}:[0-9a-f]{64}$ ]] \
      || die 'ASSESSMENT_FIXTURE_EXPECTED is not the authenticated record for ASSESSMENT_FIXTURE_SEED'
  fi
}

case "$mode" in
  test)
    [[ $# -eq 0 ]] || usage
    container_script='set -eu
umask 077
cp -a --no-preserve=ownership /workspace /tmp/workspace
mkdir -p /tmp/gradle-home /tmp/home
cp -a --no-preserve=ownership /opt/streamlens-gradle-cache/. /tmp/gradle-home/
chmod -R u+rwX /tmp/gradle-home
cd /tmp/workspace
exec ./gradlew --offline --no-daemon --console=plain --stacktrace test'
    container_args=(-ceu "$container_script")
    ;;
  oracle)
    [[ $# -eq 0 ]] || usage
    container_cpus=1
    load_fixture_entropy
    if [[ $print_command == true ]]; then
      oracle_token=7777777777777777777777777777777777777777777777777777777777777777
    else
      oracle_token=${ORACLE_RESULT_TOKEN:-}
      [[ $oracle_token =~ ^[0-9a-f]{64}$ ]] \
        || die 'ORACLE_RESULT_TOKEN must be 256-bit lowercase hex'
    fi
    # Dollar expansions in this string intentionally run only in the container.
    # shellcheck disable=SC2016
    container_script='set -eu
umask 077
cp -a --no-preserve=ownership /workspace /tmp/workspace
mkdir -p /tmp/gradle-home /tmp/home
cp -a --no-preserve=ownership /opt/streamlens-gradle-cache/. /tmp/gradle-home/
chmod -R u+rwX /tmp/gradle-home
cd /tmp/workspace
seed=$1
key=$2
token=$3
./gradlew --offline --no-daemon --console=plain --quiet jmhJar >/tmp/gradle.txt 2>&1
version=$(sed -nE '"'"'s/^version = "([^"]+)"$/\1/p'"'"' build.gradle.kts)
test -n "$version"
case "$version" in *[!0-9A-Za-z._-]* ) exit 2 ;; esac
jar="build/libs/streamlens-java-performance-challenge-${version}-jmh.jar"
test -f "$jar" && test ! -L "$jar"
java -Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=1 \
  -cp "$jar" com.streamlens.assessment.BenchmarkVerifier \
  oracle --seed "$seed" --auth-key "$key" \
  >/tmp/oracle.txt 2>/tmp/oracle-error.txt
test "$(wc -l </tmp/oracle.txt | tr -d "[:space:]")" -eq 1
record=$(cat /tmp/oracle.txt)
printf "@@STREAMLENS_JAVA_ORACLE_RESULT %s %s\n" "$token" "$record"'
    container_args=(-ceu "$container_script" -- \
      "$fixture_seed" "$fixture_auth_key" "$oracle_token")
    ;;
  benchmark)
    [[ $# -eq 0 ]] || usage
    container_cpus=1
    load_fixture_contract
    if [[ $print_command == true ]]; then
      benchmark_token='<random-result-token>'
    elif [[ -n ${BENCHMARK_RESULT_TOKEN:-} ]]; then
      benchmark_token=$BENCHMARK_RESULT_TOKEN
      [[ $benchmark_token =~ ^[0-9a-f]{64}$ ]] || die 'BENCHMARK_RESULT_TOKEN must be 64 lowercase hex characters'
    else
      benchmark_token=$(LC_ALL=C od -An -N32 -tx1 /dev/urandom | tr -d '[:space:]')
      [[ $benchmark_token =~ ^[0-9a-f]{64}$ ]] || die 'could not generate benchmark token'
    fi
    # Dollar expansions in this string intentionally run only in the container.
    # shellcheck disable=SC2016
    container_script='set -eu
umask 077
cp -a --no-preserve=ownership /workspace /tmp/workspace
mkdir -p /tmp/gradle-home /tmp/home
cp -a --no-preserve=ownership /opt/streamlens-gradle-cache/. /tmp/gradle-home/
chmod -R u+rwX /tmp/gradle-home
cd /tmp/workspace
token=$1
seed=$2
expected=$3
key=$4
./gradlew --offline --no-daemon --console=plain --quiet jmhJar >/tmp/gradle.txt 2>&1
version=$(sed -nE '"'"'s/^version = "([^"]+)"$/\1/p'"'"' build.gradle.kts)
test -n "$version"
case "$version" in *[!0-9A-Za-z._-]* ) exit 2 ;; esac
jar="build/libs/streamlens-java-performance-challenge-${version}-jmh.jar"
test -f "$jar" && test ! -L "$jar"
java -Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=1 \
  -cp "$jar" com.streamlens.assessment.BenchmarkVerifier \
  verify --seed "$seed" --expected "$expected" --auth-key "$key" \
  >/tmp/verifier.txt 2>&1
STREAMLENS_JAVA_FIXTURE_AUTH_KEY="$key" \
java -Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=1 \
  -jar "$jar" ".*AnalyzerBenchmark.*" \
  -p "fixtureSeed=$seed" -p "fixtureExpected=$expected" \
  -wi 2 -w 200ms -i 3 -r 300ms -f 1 -t 1 \
  -bm avgt -tu ns -prof gc -rf json -rff /tmp/jmh-result.json \
  >/tmp/jmh.txt 2>&1
test -s /tmp/jmh-result.json
printf "@@STREAMLENS_JAVA_BENCHMARK_RESULT %s\n" "$token"
cat /tmp/jmh-result.json'
    container_args=(-ceu "$container_script" -- \
      "$benchmark_token" "$fixture_seed" "$fixture_expected" "$fixture_auth_key")
    ;;
  profile)
    [[ $# -eq 4 ]] || usage
    profile_mode=true
    profile_kind=$1
    profile_scenario=$2
    profile_duration=$3
    profile_output_input=$4
    container_cpus=1
    load_fixture_contract
    [[ $profile_kind == cpu || $profile_kind == alloc ]] || die 'profile kind must be cpu or alloc'
    case "$profile_scenario" in
      Balanced | HighCardinality | MostlyFiltered) ;;
      *) die "invalid profiling scenario: $profile_scenario" ;;
    esac
    [[ $profile_duration =~ ^([1-9][0-9]*)(ms|s|m)$ ]] || die "invalid profile duration: $profile_duration"
    [[ $profile_output_input != *$'\n'* && $profile_output_input != *$'\r'* ]] || die 'profile output path is invalid'
    profile_parent_input=$(dirname -- "$profile_output_input")
    profile_name=$(basename -- "$profile_output_input")
    [[ -n $profile_name && $profile_name != . && $profile_name != .. && $profile_name != / ]] || die 'invalid profile output name'
    [[ -d $profile_parent_input && ! -L $profile_parent_input ]] || die 'profile output parent must be a non-symbolic-link directory'
    profile_parent=$(cd -- "$profile_parent_input" && pwd -P)
    profile_output="$profile_parent/$profile_name"
    [[ ! -e $profile_output && ! -L $profile_output ]] || die 'profile output must not already exist'
    paths_overlap "$profile_output" "$prepared" && die 'profile output overlaps prepared tree'
    read -r owner permissions < <(directory_owner_and_mode "$profile_parent")
    [[ $owner == "$EUID" ]] || die 'profile output parent must be owned by current user'
    permission_value=$((8#$permissions))
    (( (permission_value & 0022) == 0 )) || die 'profile output parent must not be group- or world-writable'
    if [[ $print_command == true ]]; then
      profile_token='<random-ready-token>'
    else
      profile_token=$(LC_ALL=C od -An -N32 -tx1 /dev/urandom | tr -d '[:space:]')
      [[ $profile_token =~ ^[0-9a-f]{64}$ ]] || die 'could not generate profile token'
    fi
    # Dollar expansions in this string intentionally run only in the container.
    # shellcheck disable=SC2016
    container_script='set -eu
umask 077
cp -a --no-preserve=ownership /workspace /tmp/workspace
mkdir -p /tmp/gradle-home /tmp/home /tmp/profile
cp -a --no-preserve=ownership /opt/streamlens-gradle-cache/. /tmp/gradle-home/
chmod -R u+rwX /tmp/gradle-home
cd /tmp/workspace
kind=$1
scenario=$2
duration=$3
token=$4
seed=$5
expected=$6
key=$7
./gradlew --offline --no-daemon --console=plain --quiet jmhJar
version=$(sed -nE '"'"'s/^version = "([^"]+)"$/\1/p'"'"' build.gradle.kts)
test -n "$version"
case "$version" in *[!0-9A-Za-z._-]* ) exit 2 ;; esac
jar="build/libs/streamlens-java-performance-challenge-${version}-jmh.jar"
test -f "$jar" && test ! -L "$jar"
java -Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=1 \
  -cp "$jar" com.streamlens.assessment.BenchmarkVerifier \
  verify --seed "$seed" --expected "$expected" --auth-key "$key" \
  >/tmp/profile/verifier.txt 2>&1
events=jdk.ExecutionSample,jdk.NativeMethodSample
if test "$kind" = alloc; then
  events=jdk.ObjectAllocationSample,jdk.ObjectAllocationInNewTLAB,jdk.ObjectAllocationOutsideTLAB
fi
STREAMLENS_JAVA_FIXTURE_AUTH_KEY="$key" \
java -Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=1 \
  -jar "$jar" ".*AnalyzerBenchmark.${scenario}" \
  -p "fixtureSeed=$seed" -p "fixtureExpected=$expected" \
  -wi 2 -w 200ms -i 1 -r "$duration" -f 1 -t 1 -bm avgt -tu ns -prof gc \
  -jvmArgsAppend "-XX:StartFlightRecording=filename=/tmp/profile/recording.jfr,settings=profile,dumponexit=true" \
  >/tmp/profile/jmh.txt 2>&1
jfr summary /tmp/profile/recording.jfr >/tmp/profile/summary.txt
jfr print --events "$events" --stack-depth 32 /tmp/profile/recording.jfr >/tmp/profile/hotspots.txt
for artifact in recording.jfr summary.txt hotspots.txt jmh.txt; do
  test -f "/tmp/profile/$artifact"
  test ! -L "/tmp/profile/$artifact"
  test -s "/tmp/profile/$artifact"
done
printf "@@STREAMLENS_JAVA_PROFILE_READY %s\n" "$token"
while :; do sleep 1; done'
    container_args=(-ceu "$container_script" -- \
      "$profile_kind" "$profile_scenario" "$profile_duration" "$profile_token" \
      "$fixture_seed" "$fixture_expected" "$fixture_auth_key")
    ;;
  *) die "unknown mode: $mode" ;;
esac

image=${ASSESSMENT_DOCKER_IMAGE:-}
[[ $image == *@sha256:* ]] || die 'ASSESSMENT_DOCKER_IMAGE must use an immutable @sha256 digest'
image_name=${image%@sha256:*}
image_digest=${image##*@sha256:}
[[ $image_name =~ ^[A-Za-z0-9][A-Za-z0-9._/:-]*$ && $image_digest =~ ^[0-9a-f]{64}$ ]] \
  || die 'invalid digest-pinned image reference'

deadline=${ASSESSMENT_DOCKER_DEADLINE_SECONDS:-360}
output_limit=${ASSESSMENT_DOCKER_OUTPUT_LIMIT_BYTES:-8388608}
control_deadline=${ASSESSMENT_DOCKER_CONTROL_DEADLINE_SECONDS:-30}
if [[ ! $deadline =~ ^[1-9][0-9]{0,2}$ ]] || ((deadline > 600)); then
  die 'Docker deadline must be 1-600 seconds'
fi
if [[ ! $output_limit =~ ^[1-9][0-9]{3,7}$ ]] \
    || ((output_limit < 4096 || output_limit > 16777216)); then
  die 'Docker output limit must be 4096-16777216 bytes'
fi
if [[ ! $control_deadline =~ ^[1-9][0-9]?$ ]] || ((control_deadline > 60)); then
  die 'control deadline must be 1-60 seconds'
fi

if [[ $print_command == true ]]; then
  runtime='<private-runtime-directory>'
else
  command -v docker >/dev/null 2>&1 || die 'docker is required'
  runtime=$(mktemp -d "${TMPDIR:-/tmp}/streamlens-java-isolated.XXXXXX")
  chmod 0700 "$runtime"
fi
cid_file="$runtime/container.cid"
capture="$runtime/combined-output"

docker_command=(
  docker run --pull=never --cidfile="$cid_file" --entrypoint=/bin/sh
  --init --stop-timeout=1 --read-only --network=none --ipc=none --log-driver=none
  --cap-drop=ALL --security-opt=no-new-privileges:true --pids-limit=256
  --memory=2g --memory-swap=2g --cpus="$container_cpus"
  --ulimit=nofile=1024:1024 --ulimit=core=0:0 --user=65532:65532
  "--tmpfs=/tmp:rw,nosuid,nodev,exec,size=1536m,mode=1777"
  --mount "type=bind,source=$prepared,target=/workspace,readonly"
  --workdir=/tmp
  --env=HOME=/tmp/home --env=TMPDIR=/tmp --env=GRADLE_USER_HOME=/tmp/gradle-home
  --env=JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8
  --env=HTTP_PROXY= --env=HTTPS_PROXY= --env=ALL_PROXY= --env=NO_PROXY=
  --env=http_proxy= --env=https_proxy= --env=all_proxy= --env=no_proxy=
  "$image" "${container_args[@]}"
)

if [[ $print_command == true ]]; then
  printf '%q ' "${docker_command[@]}"
  printf '>%q 2>&1 &\n' "$capture"
  printf '# bounded host watchdog: deadline=%ss output-limit=%s bytes\n' "$deadline" "$output_limit"
  printf '# validated-CID cleanup: docker rm -f -v CID; no candidate-writable host mount\n'
  if [[ $profile_mode == true ]]; then
    printf '# extract fixed artifacts recording.jfr summary.txt hotspots.txt jmh.txt by validated CID after exact random ready marker\n'
  fi
  exit 0
fi

docker_pid=
container_removed=false
profile_staging=
absolute_deadline=$((SECONDS + deadline))
enforce_absolute_deadline=true

run_control_command() {
  local output=$1
  shift
  "$@" >"$output" 2>&1 &
  local process=$!
  local started=$SECONDS
  local size
  while kill -0 "$process" 2>/dev/null; do
    size=$(wc -c <"$output" 2>/dev/null | tr -d '[:space:]')
    if ((size > 65536 || SECONDS - started >= control_deadline)) \
      || [[ $enforce_absolute_deadline == true && $SECONDS -ge $absolute_deadline ]]; then
      kill -TERM "$process" 2>/dev/null || true
      sleep 0.1
      kill -KILL "$process" 2>/dev/null || true
      wait "$process" 2>/dev/null || true
      return 124
    fi
    sleep 0.1
  done
  wait "$process"
}

cleanup() {
  local status=$?
  local cleanup_failed=false
  set +e
  # Cleanup gets its own bounded control window even after the assessment's
  # absolute execution deadline has expired.
  enforce_absolute_deadline=false
  if [[ -n ${docker_pid:-} ]] && kill -0 "$docker_pid" 2>/dev/null; then
    kill -TERM "$docker_pid" 2>/dev/null || true
    sleep 0.1
    kill -KILL "$docker_pid" 2>/dev/null || true
    wait "$docker_pid" 2>/dev/null || true
  fi
  if [[ $container_removed == false && -f $cid_file ]]; then
    cid=$(tr -d '\r\n' <"$cid_file" 2>/dev/null || true)
    if [[ $cid =~ ^[0-9a-f]{64}$ ]]; then
      inspect_log="$runtime/cleanup-inspect"
      if run_control_command "$inspect_log" docker inspect --format '{{.Id}}' "$cid"; then
        actual_id=$(tr -d '\r\n' <"$inspect_log" 2>/dev/null || true)
      else
        actual_id=
      fi
      if [[ $actual_id == "$cid" ]]; then
        for attempt in 1 2; do
          if run_control_command "$runtime/cleanup-rm-$attempt" docker rm -f -v "$cid"; then
            container_removed=true
            break
          fi
        done
      else
        cleanup_failed=true
      fi
    else
      cleanup_failed=true
    fi
  fi
  if [[ $container_removed == false && -f $cid_file ]]; then
    echo "run-isolated: failed validated-CID cleanup; retained $runtime" >&2
    cleanup_failed=true
  else
    rm -rf -- "$runtime"
  fi
  [[ -z ${profile_staging:-} ]] || rm -rf -- "$profile_staging"
  if [[ $cleanup_failed == true && $status -eq 0 ]]; then
    status=2
  fi
  exit "$status"
}
trap cleanup EXIT HUP INT TERM

"${docker_command[@]}" >"$capture" 2>&1 &
docker_pid=$!
ready=false
while kill -0 "$docker_pid" 2>/dev/null; do
  size=$(wc -c <"$capture" 2>/dev/null | tr -d '[:space:]')
  if ((size > output_limit)); then
    kill -TERM "$docker_pid" 2>/dev/null || true
    sleep 0.2
    kill -KILL "$docker_pid" 2>/dev/null || true
    wait "$docker_pid" 2>/dev/null || true
    echo "run-isolated: combined container output exceeded $output_limit bytes" >&2
    head -c "$output_limit" "$capture"
    exit 2
  fi
  if [[ $profile_mode == true ]] && grep -Fqx "@@STREAMLENS_JAVA_PROFILE_READY $profile_token" "$capture" 2>/dev/null; then
    ready=true
    break
  fi
  if ((SECONDS >= absolute_deadline)); then
    kill -TERM "$docker_pid" 2>/dev/null || true
    sleep 0.2
    kill -KILL "$docker_pid" 2>/dev/null || true
    wait "$docker_pid" 2>/dev/null || true
    echo "run-isolated: host deadline exceeded after ${deadline}s" >&2
    head -c "$output_limit" "$capture"
    exit 2
  fi
  sleep 0.2
done

if [[ $profile_mode == false ]]; then
  set +e
  wait "$docker_pid"
  status=$?
  set -e
  docker_pid=
  size=$(wc -c <"$capture" 2>/dev/null | tr -d '[:space:]')
  if ((size > output_limit)); then
    echo "run-isolated: combined container output exceeded $output_limit bytes" >&2
    head -c "$output_limit" "$capture"
    exit 2
  fi
  cat "$capture"
  exit "$status"
fi

[[ $ready == true ]] || {
  set +e; wait "$docker_pid"; status=$?; set -e; docker_pid=
  cat "$capture"
  echo 'run-isolated: profile container exited before readiness marker' >&2
  exit "${status:-2}"
}
cid=$(tr -d '\r\n' <"$cid_file" 2>/dev/null || true)
[[ $cid =~ ^[0-9a-f]{64}$ ]] || die 'profile container CID is missing or malformed'
if ! run_control_command "$runtime/profile-inspect" docker inspect --format '{{.Id}}' "$cid"; then
  die 'profile container inspection exceeded its bound or failed'
fi
actual_id=$(tr -d '\r\n' <"$runtime/profile-inspect" 2>/dev/null || true)
[[ $actual_id == "$cid" ]] || die 'profile container CID failed validation'

profile_staging=$(mktemp -d "$profile_parent/.streamlens-java-profile.XXXXXX")
chmod 0700 "$profile_staging"
for artifact in recording.jfr summary.txt hotspots.txt jmh.txt; do
  control_file="$runtime/control-$artifact"
  docker exec "$cid" /bin/cat "/tmp/profile/$artifact" >"$profile_staging/$artifact" 2>"$control_file" &
  control_pid=$!
  control_start=$SECONDS
  while kill -0 "$control_pid" 2>/dev/null; do
    if ((SECONDS - control_start >= control_deadline || SECONDS >= absolute_deadline)); then
      kill -KILL "$control_pid" 2>/dev/null || true
      wait "$control_pid" 2>/dev/null || true
      die "profile extraction deadline exceeded for $artifact"
    fi
    size=$(wc -c <"$profile_staging/$artifact" 2>/dev/null | tr -d '[:space:]')
    ((size <= output_limit)) || die "profile artifact exceeds $output_limit bytes: $artifact"
    sleep 0.1
  done
  wait "$control_pid" || die "could not extract profile artifact: $artifact"
  size=$(wc -c <"$profile_staging/$artifact" 2>/dev/null | tr -d '[:space:]')
  ((size <= output_limit)) || die "profile artifact exceeds $output_limit bytes: $artifact"
  [[ -s $profile_staging/$artifact && ! -L $profile_staging/$artifact ]] || die "invalid profile artifact: $artifact"
done

run_control_command "$runtime/profile-stop" docker stop --time=1 "$cid" \
  || die 'could not stop profile container by validated CID within the control deadline'
set +e; wait "$docker_pid"; set -e
docker_pid=
run_control_command "$runtime/profile-rm" docker rm -f -v "$cid" \
  || die 'could not remove profile container by validated CID within the control deadline'
container_removed=true
mv "$profile_staging" "$profile_output"
profile_staging=
echo "Profile artifacts published: $profile_output"
