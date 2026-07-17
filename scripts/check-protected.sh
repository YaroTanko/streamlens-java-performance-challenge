#!/usr/bin/env bash
set -euo pipefail

readonly allowed_analyzer='src/main/java/com/streamlens/analyzer/Analyzer.java'
readonly allowed_notes='OPTIMIZATION.md'

usage() {
  echo "usage: $0 <pull-request-base-commit> <candidate-commit>" >&2
  exit 2
}

fail() {
  echo "$*" >&2
  exit 1
}

trusted_git() {
  env \
    -u GIT_DIR -u GIT_WORK_TREE -u GIT_COMMON_DIR -u GIT_INDEX_FILE \
    -u GIT_OBJECT_DIRECTORY -u GIT_ALTERNATE_OBJECT_DIRECTORIES \
    -u GIT_CONFIG -u GIT_CONFIG_COUNT -u GIT_CONFIG_PARAMETERS \
    -u GIT_CONFIG_SYSTEM -u GIT_CONFIG_GLOBAL -u GIT_CONFIG_NOSYSTEM \
    -u GIT_EXEC_PATH -u GIT_EXTERNAL_DIFF -u GIT_DIFF_OPTS -u GIT_PAGER \
    -u GIT_ASKPASS -u GIT_SSH -u GIT_SSH_COMMAND \
    -u GIT_TRACE -u GIT_TRACE2 -u GIT_TRACE2_EVENT -u GIT_TRACE2_PERF \
    GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_OPTIONAL_LOCKS=0 \
    GIT_TERMINAL_PROMPT=0 GIT_NO_REPLACE_OBJECTS=1 \
    git -c core.fsmonitor=false -c core.hooksPath=/dev/null "$@"
}

display_path() {
  local path=$1
  path=${path//\\/\\\\}
  path=${path//$'\n'/\\n}
  path=${path//$'\r'/\\r}
  path=${path//$'\t'/\\t}
  printf '%s' "$path"
}

validate_commit() {
  local label=$1
  local commit=$2
  [[ $commit =~ ^[0-9a-f]{40}$ ]] || {
    echo "$label must be a full lowercase 40-character commit SHA." >&2
    exit 2
  }
  [[ $(trusted_git cat-file -t "$commit" 2>/dev/null || true) == commit ]] || {
    echo "$label is not an available commit: $commit" >&2
    exit 2
  }
}

[[ $# -eq 2 ]] || usage
base_commit=$1
candidate_commit=$2
validate_commit base-commit "$base_commit"
validate_commit candidate-commit "$candidate_commit"

script_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
trusted_root=$(cd -- "$script_directory/.." && pwd -P)
source_audit="$script_directory/source-audit.sh"
[[ -f $source_audit && ! -L $source_audit ]] || fail 'Trusted source audit is missing.'

work_directory=$(mktemp -d "${TMPDIR:-/tmp}/streamlens-java-protected.XXXXXX")
trap 'rm -rf -- "$work_directory"' EXIT HUP INT TERM
raw_diff="$work_directory/diff.raw"

trusted_git diff-tree -r --no-commit-id --raw -z --no-abbrev \
  --find-renames --find-copies-harder "$base_commit" "$candidate_commit" >"$raw_diff" \
  || fail 'Unable to inspect candidate changes.'

change_count=0
analyzer_changed=false
notes_changed=false
while IFS= read -r -d '' header; do
  if [[ ! $header =~ ^:([0-7]{6})[[:space:]]+([0-7]{6})[[:space:]]+([0-9a-f]+)[[:space:]]+([0-9a-f]+)[[:space:]]+([A-Z][0-9]*)$ ]]; then
    fail 'Malformed raw Git diff record.'
  fi
  old_mode=${BASH_REMATCH[1]}
  new_mode=${BASH_REMATCH[2]}
  status=${BASH_REMATCH[5]}
  status_kind=${status:0:1}
  IFS= read -r -d '' first_path || fail 'Malformed raw Git diff path.'
  change_count=$((change_count + 1))

  if [[ $status_kind == R || $status_kind == C ]]; then
    IFS= read -r -d '' second_path || fail 'Malformed rename/copy record.'
    fail "Rename/copy changes are prohibited: $(display_path "$first_path") -> $(display_path "$second_path")."
  fi
  case "$first_path" in
    "$allowed_analyzer")
      [[ $analyzer_changed == false ]] || fail "Duplicate change record for $allowed_analyzer."
      analyzer_changed=true
      ;;
    "$allowed_notes")
      [[ $notes_changed == false ]] || fail "Duplicate change record for $allowed_notes."
      notes_changed=true
      ;;
    *)
      fail "Protected assessment path changed: $(display_path "$first_path"). Only $allowed_analyzer and $allowed_notes may change."
      ;;
  esac

  [[ $status_kind == M ]] || fail "Only in-place modifications are allowed: $(display_path "$first_path") has status $status."
  [[ $old_mode == 100644 && $new_mode == 100644 ]] \
    || fail "Deliverables must remain non-executable regular files: $(display_path "$first_path") changed $old_mode -> $new_mode."
done <"$raw_diff"

[[ $change_count -gt 0 ]] || fail 'No candidate changes found.'
[[ $analyzer_changed == true ]] || fail "Required deliverable was not changed: $allowed_analyzer."
[[ $notes_changed == true ]] || fail "Required deliverable was not changed: $allowed_notes."

analyzer_blob="$work_directory/Analyzer.java"
notes_blob="$work_directory/OPTIMIZATION.md"
trusted_git cat-file blob "$candidate_commit:$allowed_analyzer" >"$analyzer_blob" \
  || fail "$allowed_analyzer must exist as a blob."
trusted_git cat-file blob "$candidate_commit:$allowed_notes" >"$notes_blob" \
  || fail "$allowed_notes must exist as a blob."

notes_size=$(wc -c <"$notes_blob" | tr -d '[:space:]')
[[ $notes_size =~ ^[0-9]+$ ]] || fail "Could not determine $allowed_notes size."
((notes_size > 0 && notes_size <= 65536)) \
  || fail "$allowed_notes must be 1-65536 bytes; found $notes_size."
command -v iconv >/dev/null 2>&1 || fail 'Trusted host is missing iconv.'
iconv -f UTF-8 -t UTF-8 "$notes_blob" >/dev/null 2>&1 \
  || fail "$allowed_notes must contain strict UTF-8."
if od -An -v -t u1 "$notes_blob" | awk '
  { for (field = 1; field <= NF; field++) if ($field == 0) found = 1 }
  END { exit !found }
'; then
  fail "$allowed_notes must not contain NUL bytes."
fi

if ! env -u BASH_ENV -u ENV bash --noprofile --norc "$source_audit" \
    "$analyzer_blob" "$trusted_root/src/main/java"; then
  fail "Candidate source audit failed for $allowed_analyzer."
fi

[[ -s $notes_blob ]] || fail "$allowed_notes must not be empty."
if grep -Fq -- 'Replace this template' "$notes_blob"; then
  fail "$allowed_notes still contains the candidate template instruction."
fi
bullet_count=$(awk '
  /^[[:space:]]*[-*+][[:space:]]+[^[:space:]]/ { count++ }
  END { print count + 0 }
' "$notes_blob")
if [[ $bullet_count -lt 5 || $bullet_count -gt 10 ]]; then
  fail "$allowed_notes must contain 5-10 non-empty Markdown bullets; found $bullet_count."
fi
if ! grep -Eq '^[[:space:]]*[-*+][[:space:]]+Profile evidence:[[:space:]]*[^[:space:]]' "$notes_blob"; then
  fail "$allowed_notes must include '- Profile evidence: <observed hotspot>'."
fi

echo 'Protected-file check passed.'
