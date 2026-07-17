#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

python3 "$root/scripts/source_policy.py" \
  "$root/src/main/java/com/streamlens/analyzer/Analyzer.java" >/dev/null

sed 's/import java.io.BufferedReader;/import java.nio.file.Files;/' \
  "$root/src/main/java/com/streamlens/analyzer/Analyzer.java" > "$tmp/Unsafe.java"
if python3 "$root/scripts/source_policy.py" "$tmp/Unsafe.java" >/dev/null 2>&1; then
  echo "policy test: unsafe filesystem import was accepted" >&2
  exit 1
fi

sed 's/checkInterrupted();/System.exit(0);/' \
  "$root/src/main/java/com/streamlens/analyzer/Analyzer.java" > "$tmp/Output.java"
if python3 "$root/scripts/source_policy.py" "$tmp/Output.java" >/dev/null 2>&1; then
  echo "policy test: prohibited System API was accepted" >&2
  exit 1
fi

sed 's/checkInterrupted();/java.net.Socket socket = null;/' \
  "$root/src/main/java/com/streamlens/analyzer/Analyzer.java" > "$tmp/Network.java"
if python3 "$root/scripts/source_policy.py" "$tmp/Network.java" >/dev/null 2>&1; then
  echo "policy test: fully-qualified network API was accepted" >&2
  exit 1
fi

cat > "$tmp/valid.md" <<'NOTES'
- Profile evidence: JFR showed parsing as the hottest allocation path.
- Approach: reuse the parser factory.
- Expected effect: fewer allocations.
- Trade-off: one shared immutable factory.
- Verification: ./gradlew test and ./gradlew jmh.
NOTES
"$root/scripts/check-notes.sh" "$tmp/valid.md" >/dev/null

if "$root/scripts/check-notes.sh" "$root/OPTIMIZATION.md" >/dev/null 2>&1; then
  echo "policy test: starter notes template was accepted" >&2
  exit 1
fi

echo "policy tests: passed"
