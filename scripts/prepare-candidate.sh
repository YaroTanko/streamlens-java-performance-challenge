#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "usage: prepare-candidate.sh BASELINE_DIR CANDIDATE_REPO CANDIDATE_COMMIT DESTINATION" >&2
  exit 2
fi

baseline=$1
candidate_repo=$2
candidate_commit=$3
destination=$4

if [[ -e "$destination" ]]; then
  echo "prepare candidate: destination already exists: $destination" >&2
  exit 1
fi

mkdir -p "$destination"
cp -a "$baseline/." "$destination/"
rm -rf "$destination/.git" "$destination/.gradle" "$destination/build" "$destination/.bench"

implementation='src/main/java/com/streamlens/analyzer/Analyzer.java'
notes='OPTIMIZATION.md'
mkdir -p "$destination/$(dirname "$implementation")"
git -C "$candidate_repo" show "${candidate_commit}:${implementation}" > "$destination/$implementation"
git -C "$candidate_repo" show "${candidate_commit}:${notes}" > "$destination/$notes"

echo "prepare candidate: created baseline-owned tree at $destination"
