#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 [base-ref]" >&2
  echo 'default base ref: STREAMLENS_BASE_REF or origin/main' >&2
  exit 2
}

(( $# <= 1 )) || usage
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd -- "$root"
for tool in bash git java javac; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "preflight requires $tool in PATH" >&2
    exit 2
  }
done
if [[ -n $(git status --porcelain=v1 --untracked-files=all) ]]; then
  echo 'preflight requires a clean worktree:' >&2
  git status --short >&2
  exit 1
fi
base_ref=${1:-${STREAMLENS_BASE_REF:-origin/main}}
base=$(git rev-parse --verify "$base_ref^{commit}" 2>/dev/null) || {
  echo "cannot resolve preflight base ref: $base_ref" >&2
  exit 2
}
head=$(git rev-parse --verify 'HEAD^{commit}')
echo "Preflight scope: $base..$head"
bash scripts/check-protected.sh "$base" "$head"
./gradlew --no-daemon --console=plain test
echo 'Preflight passed. CI remains authoritative for comparative scoring.'
