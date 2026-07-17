#!/usr/bin/env bash
set -euo pipefail

command=${1:-}
shift || true
cid=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
log=${FAKE_DOCKER_LOG:?}
state=${FAKE_DOCKER_STATE:?}

case "$command" in
  run)
    cid_file=
    token=
    for argument in "$@"; do
      [[ $argument == --cidfile=* ]] && cid_file=${argument#--cidfile=}
      if [[ -z $token && $argument =~ ^[0-9a-f]{64}$ ]]; then
        token=$argument
      fi
    done
    [[ -n $cid_file ]] || exit 91
    printf '%s\n' "$cid" >"$cid_file"
    printf 'run\n' >>"$log"
    case "${FAKE_DOCKER_MODE:-success}" in
      success)
        printf 'fake docker success canary\n'
        ;;
      hang)
        while :; do sleep 1; done
        ;;
      overflow)
        head -c 10000 /dev/zero | tr '\0' x
        while :; do sleep 1; done
        ;;
      profile)
        [[ $token =~ ^[0-9a-f]{64}$ ]] || exit 92
        printf '@@STREAMLENS_JAVA_PROFILE_READY %s\n' "$token"
        while [[ ! -e $state/stopped ]]; do sleep 0.1; done
        ;;
      *) exit 93 ;;
    esac
    ;;
  inspect)
    printf 'inspect cid=%s\n' "${*: -1}" >>"$log"
    [[ ${FAKE_DOCKER_INSPECT_MODE:-success} == success ]] || exit 1
    printf '%s\n' "$cid"
    ;;
  exec)
    requested_cid=$1
    artifact=${*: -1}
    artifact=${artifact##*/}
    printf 'exec cid=%s artifact=%s\n' "$requested_cid" "$artifact" >>"$log"
    case "${FAKE_DOCKER_COPY_MODE:-success}" in
      success) printf 'fake %s artifact\n' "$artifact" ;;
      empty) ;;
      failure) exit 1 ;;
      overflow) head -c 10000 /dev/zero | tr '\0' y ;;
      hang) while :; do sleep 1; done ;;
      *) exit 94 ;;
    esac
    ;;
  stop)
    requested_cid=${*: -1}
    printf 'stop cid=%s\n' "$requested_cid" >>"$log"
    [[ ${FAKE_DOCKER_STOP_MODE:-success} == success ]] || while :; do sleep 1; done
    : >"$state/stopped"
    ;;
  rm)
    requested_cid=${*: -1}
    printf 'rm cid=%s\n' "$requested_cid" >>"$log"
    [[ ${FAKE_DOCKER_CLEANUP_MODE:-success} == success ]] || exit 1
    ;;
  info | image)
    exit 0
    ;;
  *)
    printf 'unknown command=%s\n' "$command" >>"$log"
    exit 95
    ;;
esac
