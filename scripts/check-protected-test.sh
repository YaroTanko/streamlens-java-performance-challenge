#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
temporary=$(mktemp -d "${TMPDIR:-/tmp}/streamlens-java-scope-test.XXXXXX")
trap 'rm -rf -- "$temporary"' EXIT HUP INT TERM
repository="$temporary/repository"
mkdir -p "$repository/src/main/java" "$repository/scripts/trusted"
cp -a "$root/src/main/java/." "$repository/src/main/java/"
cp "$root/scripts/source-audit.sh" "$repository/scripts/source-audit.sh"
cp "$root/scripts/trusted/SourceAudit.java" "$repository/scripts/trusted/SourceAudit.java"
cp "$root/OPTIMIZATION.md" "$repository/OPTIMIZATION.md"
git -C "$repository" init -q -b main
git -C "$repository" config user.name 'Scope Test'
git -C "$repository" config user.email 'scope-test@example.invalid'
git -C "$repository" add .
git -C "$repository" commit -qm baseline
base=$(git -C "$repository" rev-parse HEAD)

write_valid_change() {
  printf '\n// candidate change\n' \
    >>"$repository/src/main/java/com/streamlens/analyzer/Analyzer.java"
  cat >"$repository/OPTIMIZATION.md" <<'EOF'
- Profile evidence: JFR showed parsing allocations as the observed hotspot.
- Approach: reduce temporary parser objects.
- Contract: preserve validation and ordering.
- Expected effect: lower allocation pressure.
- Trade-off: the parser is more explicit.
- Verification: tests and benchmark were run.
EOF
}

commit_all() {
  git -C "$repository" add -A
  git -C "$repository" commit -qm "$1"
  git -C "$repository" rev-parse HEAD
}

restore_base() {
  git -C "$repository" reset --hard -q "$base"
  git -C "$repository" clean -fdq
}

expect_failure() {
  local label=$1 head=$2
  if (cd "$repository" && bash "$root/scripts/check-protected.sh" "$base" "$head") \
      >"$temporary/$label.out" 2>&1; then
    echo "check-protected-test: $label unexpectedly passed" >&2
    exit 1
  fi
}

write_valid_change
valid=$(commit_all valid)
(cd "$repository" && bash "$root/scripts/check-protected.sh" "$base" "$valid") >/dev/null

# A caller-owned replace ref must not change the commit/tree named by evidence.
printf 'replace-only protected path\n' >"$repository/replace-only.txt"
evil=$(commit_all replacement)
git -C "$repository" replace "$valid" "$evil"
(cd "$repository" && bash "$root/scripts/check-protected.sh" "$base" "$valid") >/dev/null
git -C "$repository" replace -d "$valid" >/dev/null

restore_base
write_valid_change
printf '\300\257' >"$repository/OPTIMIZATION.md"
malformed=$(commit_all malformed-utf8)
expect_failure malformed-utf8 "$malformed"

restore_base
write_valid_change
printf '%s\0%s\n' '- Profile evidence: measured' '- five bullets are impossible here' \
  >"$repository/OPTIMIZATION.md"
nul=$(commit_all nul)
expect_failure nul "$nul"

restore_base
write_valid_change
head -c 65537 /dev/zero | tr '\0' a >"$repository/OPTIMIZATION.md"
oversized=$(commit_all oversized)
expect_failure oversized "$oversized"

restore_base
write_valid_change
printf 'protected\n' >"$repository/extra.txt"
extra=$(commit_all extra-path)
expect_failure extra-path "$extra"

echo 'protected-scope tests passed.'
