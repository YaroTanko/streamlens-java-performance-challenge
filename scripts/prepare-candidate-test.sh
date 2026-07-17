#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
temporary=$(mktemp -d "${TMPDIR:-/tmp}/streamlens-java-prepare-test.XXXXXX")
trap 'rm -rf -- "$temporary"' EXIT HUP INT TERM
baseline="$temporary/baseline"
candidate="$temporary/candidate"
output_parent="$temporary/output"
mkdir -m 0700 "$baseline" "$output_parent"
mkdir -p "$baseline/gradle/wrapper" \
  "$baseline/src/main/java/com/streamlens/analyzer"
printf 'plugins { java }\n' >"$baseline/build.gradle.kts"
printf 'rootProject.name = "fixture"\n' >"$baseline/settings.gradle.kts"
printf '#!/bin/sh\nexit 0\n' >"$baseline/gradlew"
chmod 0755 "$baseline/gradlew"
printf 'jar\n' >"$baseline/gradle/wrapper/gradle-wrapper.jar"
printf 'distributionUrl=https://example.invalid/gradle.zip\n' \
  >"$baseline/gradle/wrapper/gradle-wrapper.properties"
printf 'package com.streamlens.analyzer; public final class Analyzer {}\n' \
  >"$baseline/src/main/java/com/streamlens/analyzer/Analyzer.java"
printf 'baseline notes\n' >"$baseline/OPTIMIZATION.md"
printf 'committed baseline\n' >"$baseline/marker.txt"
git -C "$baseline" init -q -b main
git -C "$baseline" config user.name 'Prepare Test'
git -C "$baseline" config user.email 'prepare-test@example.invalid'
git -C "$baseline" add .
git -C "$baseline" commit -qm baseline

git clone -q "$baseline" "$candidate"
git -C "$candidate" config user.name 'Prepare Test'
git -C "$candidate" config user.email 'prepare-test@example.invalid'
printf 'package com.streamlens.analyzer; public final class Analyzer { private Analyzer() {} }\n' \
  >"$candidate/src/main/java/com/streamlens/analyzer/Analyzer.java"
printf '%s\n' '- Profile evidence: fixture.' '- Approach: fixture.' '- Contract: fixture.' \
  '- Effect: fixture.' '- Trade-off: fixture.' >"$candidate/OPTIMIZATION.md"
git -C "$candidate" add .
git -C "$candidate" commit -qm candidate
candidate_commit=$(git -C "$candidate" rev-parse HEAD)

# Dirty and untracked checkout state must never enter the synthetic tree.
printf 'dirty baseline\n' >"$baseline/marker.txt"
printf 'untracked baseline\n' >"$baseline/evil.txt"
printf 'dirty candidate\n' >"$candidate/OPTIMIZATION.md"
printf 'untracked candidate\n' >"$candidate/evil.txt"

bash "$root/scripts/prepare-candidate.sh" \
  "$baseline" "$candidate" "$candidate_commit" "$output_parent/prepared" >/dev/null
prepared="$output_parent/prepared"
grep -Fqx 'committed baseline' "$prepared/marker.txt"
grep -Fq 'private Analyzer()' \
  "$prepared/src/main/java/com/streamlens/analyzer/Analyzer.java"
grep -Fqx -- '- Profile evidence: fixture.' "$prepared/OPTIMIZATION.md"
[[ ! -e $prepared/evil.txt && ! -e $prepared/.git && ! -e $prepared/build ]] || {
  echo 'prepare-candidate-test: untrusted checkout state entered synthetic tree' >&2
  exit 1
}
[[ -x $prepared/gradlew ]] || {
  echo 'prepare-candidate-test: committed executable mode was not preserved' >&2
  exit 1
}
if find "$prepared" -type d ! -perm -0001 -print -quit | grep -q .; then
  echo 'prepare-candidate-test: runtime cannot traverse the prepared source tree' >&2
  exit 1
fi
if find "$prepared" -type f ! -perm -0004 -print -quit | grep -q .; then
  echo 'prepare-candidate-test: runtime cannot read the prepared source tree' >&2
  exit 1
fi
if find "$prepared" -perm -0022 -print -quit | grep -q .; then
  echo 'prepare-candidate-test: prepared source tree is group- or world-writable' >&2
  exit 1
fi

if bash "$root/scripts/prepare-candidate.sh" \
    "$baseline" "$candidate" "$candidate_commit" "$output_parent/prepared" \
    >/dev/null 2>&1; then
  echo 'prepare-candidate-test: existing output was accepted' >&2
  exit 1
fi

echo 'candidate overlay tests passed.'
