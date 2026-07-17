#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: check-notes.sh PATH_TO_OPTIMIZATION_MD" >&2
  exit 2
fi

notes=$1
if [[ ! -f "$notes" || -L "$notes" ]]; then
  echo "notes policy: OPTIMIZATION.md must be a regular file" >&2
  exit 1
fi
if (( $(wc -c < "$notes") > 262144 )); then
  echo "notes policy: OPTIMIZATION.md exceeds 262144 bytes" >&2
  exit 1
fi

bullet_count=$(awk '/^[[:space:]]*[-*+][[:space:]]+/{count++} END{print count+0}' "$notes")
if (( bullet_count < 5 || bullet_count > 10 )); then
  echo "notes policy: expected 5-10 Markdown bullets, found $bullet_count" >&2
  exit 1
fi

profile_line=$(awk '
  /^[[:space:]]*[-*+][[:space:]]+Profile evidence:[[:space:]]*/ {
    sub(/^[[:space:]]*[-*+][[:space:]]+Profile evidence:[[:space:]]*/, "")
    print
    exit
  }
' "$notes")
if [[ -z "${profile_line//[[:space:]]/}" ]]; then
  echo "notes policy: add a non-empty 'Profile evidence:' bullet" >&2
  exit 1
fi
if grep -Eiq 'TODO|replace this|command/tool.*hotspot|template prompt' "$notes"; then
  echo "notes policy: replace every template prompt" >&2
  exit 1
fi

echo "notes policy: OPTIMIZATION.md accepted ($bullet_count bullets)"
