#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: check-submission.sh REPOSITORY BASE_COMMIT CANDIDATE_COMMIT [GITHUB_OUTPUT_FILE]" >&2
  exit 2
}

[[ $# -ge 3 && $# -le 4 ]] || usage
repo=$1
base=$2
candidate=$3
output_file=${4:-}
implementation='src/main/java/com/streamlens/analyzer/Analyzer.java'
notes='OPTIMIZATION.md'
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

git -C "$repo" cat-file -e "${base}^{commit}"
git -C "$repo" cat-file -e "${candidate}^{commit}"

changed=()
while IFS= read -r -d '' path; do
  changed+=("$path")
done < <(git -C "$repo" diff --name-only -z "$base" "$candidate" --)

if (( ${#changed[@]} == 0 )); then
  echo "submission policy: no candidate changes found" >&2
  exit 1
fi
for path in "${changed[@]}"; do
  if [[ "$path" != "$implementation" && "$path" != "$notes" ]]; then
    echo "submission policy: protected path changed: $path" >&2
    exit 1
  fi
done

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

validate_blob() {
  local path=$1
  local limit=$2
  local mode type size
  mode=$(git -C "$repo" ls-tree "$candidate" -- "$path" | awk '{print $1}')
  type=$(git -C "$repo" ls-tree "$candidate" -- "$path" | awk '{print $2}')
  if [[ "$type" != blob || ( "$mode" != 100644 && "$mode" != 100755 ) ]]; then
    echo "submission policy: $path must be a regular Git blob" >&2
    exit 1
  fi
  size=$(git -C "$repo" cat-file -s "${candidate}:${path}")
  if (( size > limit )); then
    echo "submission policy: $path exceeds $limit bytes" >&2
    exit 1
  fi
  git -C "$repo" show "${candidate}:${path}" > "$tmp/$(basename "$path")"
}

validate_blob "$implementation" 2097152
validate_blob "$notes" 262144

implementation_changed=false
notes_changed=false
for path in "${changed[@]}"; do
  [[ "$path" == "$implementation" ]] && implementation_changed=true
  [[ "$path" == "$notes" ]] && notes_changed=true
done

if [[ "$implementation_changed" == true ]]; then
  python3 "$script_dir/source_policy.py" "$tmp/Analyzer.java"
fi
if [[ "$notes_changed" == true ]]; then
  "$script_dir/check-notes.sh" "$tmp/OPTIMIZATION.md"
fi

if [[ "$implementation_changed" == true && "$notes_changed" == true ]]; then
  mode=implementation-and-notes
elif [[ "$implementation_changed" == true ]]; then
  mode=implementation-only
else
  mode=notes-only
fi

echo "submission policy: accepted mode $mode"
if [[ -n "$output_file" ]]; then
  printf 'submission_mode=%s\n' "$mode" >> "$output_file"
fi
