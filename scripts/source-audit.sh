#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 <candidate-Analyzer.java> <trusted-main-source-root>" >&2
  exit 2
}

die() {
  echo "source-audit: $*" >&2
  exit 2
}

[[ $# -eq 2 ]] || usage
candidate_input=$1
source_root_input=$2

[[ -f $candidate_input && ! -L $candidate_input ]] || die "candidate source must be a regular non-symbolic-link file"
[[ -d $source_root_input && ! -L $source_root_input ]] || die "source root must be a non-symbolic-link directory"

candidate=$(cd -- "$(dirname -- "$candidate_input")" && printf '%s/%s\n' "$(pwd -P)" "$(basename -- "$candidate_input")")
source_root=$(cd -- "$source_root_input" && pwd -P)
script_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
trusted_source="$script_directory/trusted/SourceAudit.java"
[[ -f $trusted_source && ! -L $trusted_source ]] || die "trusted SourceAudit.java is missing"

work_directory=$(mktemp -d "${TMPDIR:-/tmp}/streamlens-java-source-audit.XXXXXX")
trap 'rm -rf -- "$work_directory"' EXIT HUP INT TERM
classes="$work_directory/classes"
mkdir -m 0700 "$classes"

javac --release 21 -proc:none -d "$classes" "$trusted_source"

companion_sources=()
while IFS= read -r -d '' source; do
  canonical=$(cd -- "$(dirname -- "$source")" && printf '%s/%s\n' "$(pwd -P)" "$(basename -- "$source")")
  if [[ $canonical != "$candidate" && $canonical != "$source_root/com/streamlens/analyzer/Analyzer.java" ]]; then
    companion_sources+=("$canonical")
  fi
done < <(find "$source_root" -type f -name '*.java' -print0)

[[ ${#companion_sources[@]} -gt 0 ]] || die "trusted source root contains no companion Java sources"
exec java -cp "$classes" SourceAudit "$candidate" "${companion_sources[@]}"
