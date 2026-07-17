#!/usr/bin/env bash
set -euo pipefail

script_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
work=$(mktemp -d "${TMPDIR:-/tmp}/streamlens-java-manifest-test.XXXXXX")
trap 'rm -rf -- "$work"' EXIT HUP INT TERM
mkdir -p "$work/classes" "$work/one/run" "$work/two/run"

javac --release 21 -Xlint:all -Werror -d "$work/classes" \
  "$script_directory/trusted/EvidenceManifest.java"

for root in "$work/one/run" "$work/two/run"; do
  mkdir -p "$root/logs"
  printf 'functional-ok\n' >"$root/logs/functional.txt"
  printf 'benchmark-ok\n' >"$root/logs/benchmark.txt"
done

run_manifest() {
  local root=$1 environment=$2
  java -cp "$work/classes" EvidenceManifest \
    --root "$root" --output-dir "$root/evidence" \
    --revision assessment_baseline=1111111111111111111111111111111111111111 \
    --revision candidate=2222222222222222222222222222222222222222 \
    --parameter benchmark.samples=7 \
    --artifact functional.output=logs/functional.txt \
    --artifact benchmark.output=logs/benchmark.txt \
    --environment "host.name=$environment" \
    --runner host.os=test >/dev/null
}

run_manifest "$work/one/run" first
run_manifest "$work/two/run" second
cmp "$work/one/run/evidence/manifest-core.json" "$work/two/run/evidence/manifest-core.json"
grep -Fq '"schema": "streamlens-java-evidence-core-v4"' \
  "$work/one/run/evidence/manifest-core.json"
if cmp -s "$work/one/run/evidence/manifest-envelope.json" \
    "$work/two/run/evidence/manifest-envelope.json"; then
  echo 'volatile manifest envelopes unexpectedly match' >&2
  exit 1
fi

mkdir -p "$work/unlisted/run"
printf 'listed\n' >"$work/unlisted/run/listed.txt"
printf 'unlisted\n' >"$work/unlisted/run/unlisted.txt"
if java -cp "$work/classes" EvidenceManifest \
    --root "$work/unlisted/run" --output-dir "$work/unlisted/run/evidence" \
    --revision candidate=2222222222222222222222222222222222222222 \
    --parameter benchmark.samples=7 --artifact listed=listed.txt \
    >/dev/null 2>&1; then
  echo 'unlisted artifact was accepted' >&2
  exit 1
fi

mkdir -p "$work/symlink/run"
printf 'outside\n' >"$work/symlink/outside.txt"
ln -s "$work/symlink/outside.txt" "$work/symlink/run/evidence.txt"
if java -cp "$work/classes" EvidenceManifest \
    --root "$work/symlink/run" --output-dir "$work/symlink/run/evidence" \
    --revision candidate=2222222222222222222222222222222222222222 \
    --parameter benchmark.samples=7 --artifact linked=evidence.txt \
    >/dev/null 2>&1; then
  echo 'symbolic-link artifact was accepted' >&2
  exit 1
fi

echo 'evidence manifest tests passed.'
