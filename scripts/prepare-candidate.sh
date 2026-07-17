#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 <trusted-baseline-checkout> <overlay-git-checkout> <overlay-commit> <new-output-directory>" >&2
  exit 2
}

die() {
  echo "prepare-candidate: $*" >&2
  exit 2
}

trusted_git() {
  env -u GIT_DIR -u GIT_WORK_TREE -u GIT_COMMON_DIR -u GIT_INDEX_FILE \
    -u GIT_OBJECT_DIRECTORY -u GIT_ALTERNATE_OBJECT_DIRECTORIES \
    -u GIT_CONFIG -u GIT_CONFIG_COUNT -u GIT_CONFIG_PARAMETERS \
    -u GIT_CONFIG_SYSTEM -u GIT_CONFIG_GLOBAL -u GIT_CONFIG_NOSYSTEM \
    -u GIT_EXEC_PATH -u GIT_EXTERNAL_DIFF -u GIT_DIFF_OPTS -u GIT_PAGER \
    -u GIT_ASKPASS -u GIT_SSH -u GIT_SSH_COMMAND \
    GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_OPTIONAL_LOCKS=0 \
    GIT_TERMINAL_PROMPT=0 GIT_NO_REPLACE_OBJECTS=1 \
    git -c core.fsmonitor=false -c core.hooksPath=/dev/null "$@"
}

physical_directory() {
  local input=$1 label=$2
  [[ -d $input && ! -L $input ]] || die "$label must be a non-symbolic-link directory: $input"
  (cd -- "$input" && pwd -P)
}

paths_overlap() {
  local left=$1 right=$2
  [[ $left == "$right" || $left == "$right/"* || $right == "$left/"* ]]
}

directory_owner_and_mode() {
  local directory=$1
  if stat -f '%u %Lp' "$directory" >/dev/null 2>&1; then
    stat -f '%u %Lp' "$directory"
  else
    stat -c '%u %a' "$directory"
  fi
}

[[ $# -eq 4 ]] || usage
baseline=$(physical_directory "$1" 'baseline checkout')
overlay=$(physical_directory "$2" 'overlay checkout')
commit=$3
output_input=$4
[[ $commit =~ ^[0-9a-f]{40}$ ]] || die 'overlay commit must be a full lowercase 40-character SHA'
baseline_top=$(trusted_git -C "$baseline" rev-parse --show-toplevel 2>/dev/null || true)
[[ -n $baseline_top ]] || die 'baseline checkout must be a Git checkout'
baseline_top=$(cd -- "$baseline_top" && pwd -P)
[[ $baseline_top == "$baseline" ]] || die 'baseline checkout must name the Git checkout root'
baseline_commit=$(trusted_git -C "$baseline" rev-parse --verify 'HEAD^{commit}' 2>/dev/null || true)
[[ $baseline_commit =~ ^[0-9a-f]{40}$ ]] || die 'baseline checkout HEAD is unavailable'
trusted_git -C "$overlay" cat-file -e "$commit^{commit}" 2>/dev/null \
  || die "overlay commit is unavailable: $commit"

output_parent_input=$(dirname -- "$output_input")
output_name=$(basename -- "$output_input")
[[ -n $output_name && $output_name != . && $output_name != .. && $output_name != / ]] \
  || die "invalid output directory: $output_input"
output_parent=$(physical_directory "$output_parent_input" 'output parent')
output="$output_parent/$output_name"
[[ ! -e $output && ! -L $output ]] || die "output already exists: $output"
paths_overlap "$output" "$baseline" && die 'output overlaps baseline checkout'
paths_overlap "$output" "$overlay" && die 'output overlaps overlay checkout'

read -r owner mode < <(directory_owner_and_mode "$output_parent")
[[ $owner == "$EUID" ]] || die 'output parent must be owned by the current user'
mode_value=$((8#$mode))
(( (mode_value & 0022) == 0 )) || die 'output parent must not be group- or world-writable'

work=$(mktemp -d "$output_parent/.streamlens-java-prepare.XXXXXX")
trap 'rm -rf -- "$work"' EXIT HUP INT TERM
chmod 0700 "$work"
trusted_git -C "$baseline" archive --format=tar --prefix=tree/ \
  --output="$work/baseline.tar" "$baseline_commit" \
  || die 'could not materialize exact committed baseline tree'
tar -xf "$work/baseline.tar" -C "$work" \
  || die 'could not extract exact committed baseline tree'
rm -f -- "$work/baseline.tar"

for path in \
  build.gradle.kts settings.gradle.kts gradlew gradle/wrapper/gradle-wrapper.jar \
  gradle/wrapper/gradle-wrapper.properties \
  src/main/java/com/streamlens/analyzer/Analyzer.java OPTIMIZATION.md; do
  [[ -f $work/tree/$path && ! -L $work/tree/$path ]] \
    || die "trusted committed baseline file is missing or unsafe: $path"
done

analyzer='src/main/java/com/streamlens/analyzer/Analyzer.java'
notes='OPTIMIZATION.md'
[[ $(trusted_git -C "$overlay" cat-file -t "$commit:$analyzer" 2>/dev/null || true) == blob ]] \
  || die "overlay $analyzer is not a blob"
[[ $(trusted_git -C "$overlay" cat-file -t "$commit:$notes" 2>/dev/null || true) == blob ]] \
  || die "overlay $notes is not a blob"
trusted_git -C "$overlay" cat-file blob "$commit:$analyzer" >"$work/Analyzer.java"
trusted_git -C "$overlay" cat-file blob "$commit:$notes" >"$work/OPTIMIZATION.md"
install -m 0644 "$work/Analyzer.java" "$work/tree/$analyzer"
install -m 0644 "$work/OPTIMIZATION.md" "$work/tree/$notes"

mv "$work/tree" "$output"
# The exact synthetic tree is public candidate/baseline source, never evidence.
# It is mounted read-only into a fixed non-root runtime UID, so retain neither
# group/other write access nor host-only read/traversal permissions.
find "$output" -type d -exec chmod 0755 {} +
find "$output" -type f -exec chmod u+rw,go+r,go-w {} +
if find "$output" -type l -print -quit | grep -q .; then
  die 'prepared synthetic tree must not contain symbolic links'
fi
if find "$output" -type d ! -perm -0001 -print -quit | grep -q .; then
  die 'prepared synthetic tree contains a directory inaccessible to the runtime UID'
fi
if find "$output" -type f ! -perm -0004 -print -quit | grep -q .; then
  die 'prepared synthetic tree contains a file unreadable by the runtime UID'
fi
if find "$output" -perm -0022 -print -quit | grep -q .; then
  die 'prepared synthetic tree must not be group- or world-writable'
fi
echo "Prepared baseline-owned assessment tree: $output"
