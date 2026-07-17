#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
repo="$tmp/repository"

git clone --quiet "$root" "$repo"
git -C "$repo" config user.name "StreamLens policy test"
git -C "$repo" config user.email "policy-test@localhost"
base=$(git -C "$repo" rev-parse HEAD)

commit_implementation() {
  printf '\n// Submission policy implementation canary.\n' \
    >> "$repo/src/main/java/com/streamlens/analyzer/Analyzer.java"
  git -C "$repo" add src/main/java/com/streamlens/analyzer/Analyzer.java
  git -C "$repo" commit --quiet -m implementation
}

write_valid_notes() {
  cat > "$repo/OPTIMIZATION.md" <<'NOTES'
- Profile evidence: JFR showed parser creation as an allocation hotspot.
- Approach: reuse immutable parser configuration.
- Expected effect: reduce per-event allocation.
- Correctness: parsing order and validation stay unchanged.
- Trade-off: shared immutable state requires review.
- Verification: ./gradlew test and ./gradlew jmh.
NOTES
}

assert_mode() {
  local expected=$1
  local candidate
  candidate=$(git -C "$repo" rev-parse HEAD)
  "$root/scripts/check-submission.sh" "$repo" "$base" "$candidate" "$tmp/output"
  grep -Fxq "submission_mode=$expected" "$tmp/output"
  : > "$tmp/output"
}

commit_implementation
assert_mode implementation-only

git -C "$repo" reset --hard --quiet "$base"
write_valid_notes
git -C "$repo" add OPTIMIZATION.md
git -C "$repo" commit --quiet -m notes
assert_mode notes-only

git -C "$repo" reset --hard --quiet "$base"
commit_implementation
write_valid_notes
git -C "$repo" add OPTIMIZATION.md
git -C "$repo" commit --quiet --amend --no-edit
assert_mode implementation-and-notes

git -C "$repo" reset --hard --quiet "$base"
printf '\nprotected canary\n' >> "$repo/README.md"
git -C "$repo" add README.md
git -C "$repo" commit --quiet -m protected
if "$root/scripts/check-submission.sh" "$repo" "$base" HEAD >/dev/null 2>&1; then
  echo "submission policy test: protected path was accepted" >&2
  exit 1
fi

if "$root/scripts/check-submission.sh" "$repo" "$base" "$base" >/dev/null 2>&1; then
  echo "submission policy test: empty submission was accepted" >&2
  exit 1
fi

git -C "$repo" reset --hard --quiet "$base"
rm "$repo/src/main/java/com/streamlens/analyzer/Analyzer.java"
ln -s ../../../../../../README.md "$repo/src/main/java/com/streamlens/analyzer/Analyzer.java"
git -C "$repo" add src/main/java/com/streamlens/analyzer/Analyzer.java
git -C "$repo" commit --quiet -m symlink
if "$root/scripts/check-submission.sh" "$repo" "$base" HEAD >/dev/null 2>&1; then
  echo "submission policy test: symlink implementation was accepted" >&2
  exit 1
fi

echo "submission policy tests: passed"
