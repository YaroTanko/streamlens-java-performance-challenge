#!/usr/bin/env bash
set -euo pipefail

script_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
root=$(cd -- "$script_directory/.." && pwd -P)
audit="$script_directory/source-audit.sh"
baseline="$root/src/main/java/com/streamlens/analyzer/Analyzer.java"
source_root="$root/src/main/java"
temporary=$(mktemp -d "${TMPDIR:-/tmp}/streamlens-java-audit-test.XXXXXX")
trap 'rm -rf -- "$temporary"' EXIT HUP INT TERM

fail() {
  echo "source-audit-test: $*" >&2
  exit 1
}

if ! bash "$audit" "$baseline" "$source_root" >"$temporary/valid.log" 2>&1; then
  cat "$temporary/valid.log" >&2
  fail 'baseline Analyzer.java did not pass its own source audit'
fi

case_number=0
executed_cases=0
case_filter=${AUDIT_CASE_FILTER:-}
expect_rejected() {
  local label=$1 expected=$2 replacement=$3
  if [[ -n $case_filter && ",$case_filter," != *",$label,"* ]]; then
    return
  fi
  case_number=$((case_number + 1))
  executed_cases=$((executed_cases + 1))
  local case_directory="$temporary/case-$case_number"
  mkdir "$case_directory"
  local candidate="$case_directory/Analyzer.java"
  local output="$temporary/output-$case_number.log"
  REPLACEMENT="private Analyzer() { $replacement }" \
    perl -0pe 's/private Analyzer\(\) \{\}/$ENV{REPLACEMENT}/e' "$baseline" >"$candidate"
  cmp -s "$candidate" "$baseline" && fail "$label mutation did not modify Analyzer.java"
  if bash "$audit" "$candidate" "$source_root" >"$output" 2>&1; then
    fail "$label unexpectedly passed"
  fi
  if ! grep -Fq "$expected" "$output"; then
    cat "$output" >&2
    fail "$label reported no expected policy diagnostic: $expected"
  fi
}

expect_rejected system-exit 'safe-JDK subset' 'System.exit(0);'
expect_rejected process-builder 'safe-JDK subset' 'new ProcessBuilder("true");'
expect_rejected file-write 'safe-JDK subset' \
  'try { java.nio.file.Files.writeString(java.nio.file.Path.of("x"), "x"); } catch (java.io.IOException ignored) {}'
expect_rejected socket 'safe-JDK subset' 'new java.net.Socket();'
expect_rejected reflection 'safe-JDK subset' \
  'try { Class.forName("example.Type"); } catch (Exception ignored) {}'
expect_rejected runtime 'safe-JDK subset' 'Runtime.getRuntime();'
expect_rejected thread-sleep 'Thread method is prohibited' \
  'try { Thread.sleep(1L); } catch (InterruptedException ignored) { Thread.currentThread().isInterrupted(); }'
expect_rejected clears-interrupt 'Thread method is prohibited' 'Thread.interrupted();'
expect_rejected object-wait 'object monitor/identity/reflection method is prohibited' \
  'try { wait(); } catch (InterruptedException ignored) { Thread.currentThread().isInterrupted(); }'
expect_rejected object-notify 'object monitor/identity/reflection method is prohibited' 'notify();'
expect_rejected object-identity 'object monitor/identity/reflection method is prohibited' \
  'new int[0].toString();'
expect_rejected string-intern 'String.intern mutates global VM state' '"x".intern();'
expect_rejected arrays-parallel 'parallel/common-pool operations are prohibited' \
  'java.util.Arrays.parallelSort(new int[0]);'
expect_rejected collection-parallel 'parallel/common-pool operations are prohibited' \
  'java.util.List.of().parallelStream();'
expect_rejected default-locale 'default locale/charset overload is prohibited' '"x".toLowerCase();'
expect_rejected default-charset 'default locale/charset overload is prohibited' '"x".getBytes();'
expect_rejected dynamic-charset 'default/dynamic charset lookup is prohibited' \
  'java.nio.charset.Charset.defaultCharset();'
expect_rejected random 'nondeterministic random source is prohibited' 'Math.random();'
expect_rejected wall-clock 'ambient wall-clock access is prohibited' 'java.time.Instant.now();'
expect_rejected shuffle 'nondeterministic collection shuffling is prohibited' \
  'java.util.Collections.shuffle(new java.util.ArrayList<String>());'
expect_rejected default-format 'default-locale formatting is prohibited' \
  'String.format("%s", "x");'
expect_rejected boolean-property 'system-property access is prohibited' \
  'Boolean.getBoolean("user.home");'
expect_rejected integer-property 'system-property access is prohibited' \
  'Integer.getInteger("x");'
expect_rejected long-property 'system-property access is prohibited' \
  'Long.getLong("x");'
expect_rejected default-string-charset 'default-charset String byte constructor is prohibited' \
  'new String(new byte[0]);'
expect_rejected default-reader-charset 'default/dynamic-charset InputStreamReader constructor is prohibited' \
  'new java.io.InputStreamReader((java.io.InputStream) null);'
expect_rejected named-reader-charset 'default/dynamic-charset InputStreamReader constructor is prohibited' \
  'try { new java.io.InputStreamReader((java.io.InputStream) null, "UTF-8"); } catch (java.io.IOException ignored) {}'
expect_rejected named-string-charset 'default-charset String byte constructor is prohibited' \
  'try { new String(new byte[0], "UTF-8"); } catch (java.io.IOException ignored) {}'
expect_rejected named-getbytes-charset 'dynamic charset-name overload is prohibited' \
  'try { "x".getBytes("UTF-8"); } catch (java.io.IOException ignored) {}'
expect_rejected mutable-static 'static field type is not an allowed deep-immutable constant' \
  '} private static final java.util.Map<String, String> CACHE = new java.util.HashMap<>(); private void ignored() {'
expect_rejected nonfinal-static 'static fields must be private final deep-immutable constants' \
  '} private static long calls; private void ignored() {'
expect_rejected static-block 'static initializer blocks are prohibited' \
  '} static { long ignored = 0L; } private void ignored() {'
expect_rejected enum-state 'candidate enum declarations are prohibited' \
  '} private enum State { VALUE } private void ignored() {'
expect_rejected evil-biginteger 'static BigInteger is allowed only for the baseline BILLION literal constant' \
  '} private static final BigInteger EVIL = new EvilBigInteger(); private static final class EvilBigInteger extends BigInteger { private long state; private EvilBigInteger() { super("1"); } } private void ignored() {'
expect_rejected finalizer 'finalizer declarations are prohibited' \
  '} protected void finalize() {'
expect_rejected benchmark-marker 'benchmark/workload marker is prohibited' \
  'String marker = "HighCardinality"; marker.length();'

if [[ -z $case_filter || ",$case_filter," == *",unicode,"* ]]; then
  mkdir "$temporary/unicode"
  unicode_candidate="$temporary/unicode/Analyzer.java"
  cp "$baseline" "$unicode_candidate"
  printf '\n// \\u0053ystem.exit(0);\n' >>"$unicode_candidate"
  if bash "$audit" "$unicode_candidate" "$source_root" >"$temporary/unicode.log" 2>&1; then
    fail 'Unicode escape unexpectedly passed'
  fi
  grep -Fq 'Unicode escape sequences are prohibited' "$temporary/unicode.log" \
    || fail 'Unicode escape reported no expected diagnostic'
  executed_cases=$((executed_cases + 1))
fi

[[ $executed_cases -gt 0 ]] || fail 'case filter selected no tests'
echo "source audit tests passed ($executed_cases negative cases)"
