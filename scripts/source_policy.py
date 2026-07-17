#!/usr/bin/env python3
"""Fail-closed lexical source policy for the candidate-owned Analyzer.java."""

from __future__ import annotations

import re
import sys
from pathlib import Path


MAX_BYTES = 2 * 1024 * 1024
EXPECTED_PACKAGE = "com.streamlens.analyzer"
ALLOWED_IMPORTS = {
    "com.fasterxml.jackson.core.JsonFactory",
    "com.fasterxml.jackson.core.JsonParser",
    "com.fasterxml.jackson.core.JsonToken",
    "com.fasterxml.jackson.core.StreamReadConstraints",
    "java.io.BufferedReader",
    "java.io.CharArrayReader",
    "java.io.IOException",
    "java.io.InterruptedIOException",
    "java.io.Reader",
    "java.io.StringReader",
}
ALLOWED_PREFIXES = ("java.math.", "java.time.", "java.util.")
FORBIDDEN_IMPORT_PREFIXES = (
    "java.io.File",
    "java.io.Print",
    "java.io.RandomAccessFile",
    "java.net.",
    "java.nio.file.",
    "java.lang.invoke.",
    "java.lang.management.",
    "java.lang.reflect.",
    "java.security.",
    "java.util.concurrent.",
    "javax.management.",
    "jdk.",
    "sun.",
)
FORBIDDEN_WORDS = (
    "Class",
    "ClassLoader",
    "File",
    "FileInputStream",
    "FileOutputStream",
    "Files",
    "MethodHandles",
    "ModuleLayer",
    "Path",
    "Paths",
    "PrintStream",
    "Process",
    "ProcessBuilder",
    "ProcessHandle",
    "RandomAccessFile",
    "Runtime",
    "SecurityManager",
    "ServiceLoader",
    "System",
    "Unsafe",
    "native",
)
FORBIDDEN_MARKERS = (
    "StreamLensBenchmark",
    "org.openjdk.jmh",
    "highCardinality",
    "mostlyFiltered",
    "build/results/jmh",
    "getResource",
    "setAccessible",
)


def mask_literals_and_comments(source: str) -> str:
    """Replace comments and literals with spaces while preserving newlines."""
    output = list(source)
    i = 0
    state = "code"
    while i < len(source):
        if state == "code":
            if source.startswith("//", i):
                output[i : i + 2] = "  "
                i += 2
                state = "line-comment"
            elif source.startswith("/*", i):
                output[i : i + 2] = "  "
                i += 2
                state = "block-comment"
            elif source.startswith('\"\"\"', i):
                output[i : i + 3] = "   "
                i += 3
                state = "text-block"
            elif source[i] == '"':
                output[i] = " "
                i += 1
                state = "string"
            elif source[i] == "'":
                output[i] = " "
                i += 1
                state = "char"
            else:
                i += 1
        elif state == "line-comment":
            if source[i] == "\n":
                state = "code"
            else:
                output[i] = " "
            i += 1
        elif state == "block-comment":
            if source.startswith("*/", i):
                output[i : i + 2] = "  "
                i += 2
                state = "code"
            else:
                if source[i] != "\n":
                    output[i] = " "
                i += 1
        elif state == "text-block":
            if source.startswith('\"\"\"', i):
                output[i : i + 3] = "   "
                i += 3
                state = "code"
            else:
                if source[i] != "\n":
                    output[i] = " "
                i += 1
        else:
            if source[i] == "\\":
                output[i] = " "
                if i + 1 < len(source):
                    if source[i + 1] != "\n":
                        output[i + 1] = " "
                    i += 2
                else:
                    i += 1
            elif (state == "string" and source[i] == '"') or (
                state == "char" and source[i] == "'"
            ):
                output[i] = " "
                i += 1
                state = "code"
            else:
                if source[i] != "\n":
                    output[i] = " "
                i += 1
    if state in {"block-comment", "text-block", "string", "char"}:
        raise ValueError(f"unterminated Java {state}")
    return "".join(output)


def fail(message: str) -> None:
    print(f"source policy: {message}", file=sys.stderr)
    raise SystemExit(1)


def audit(path: Path) -> None:
    if not path.is_file() or path.is_symlink():
        fail("Analyzer.java must be a regular file")
    raw = path.read_bytes()
    if len(raw) > MAX_BYTES:
        fail(f"Analyzer.java exceeds {MAX_BYTES} bytes")
    try:
        source = raw.decode("utf-8")
    except UnicodeDecodeError as exception:
        fail(f"Analyzer.java is not UTF-8: {exception}")
    if re.search(r"\\u+[0-9a-fA-F]{4}", source):
        fail("Java Unicode escapes are not allowed in candidate source")

    try:
        code = mask_literals_and_comments(source)
    except ValueError as exception:
        fail(str(exception))

    packages = re.findall(r"(?m)^\s*package\s+([\w.]+)\s*;", code)
    if packages != [EXPECTED_PACKAGE]:
        fail(f"expected exactly one `package {EXPECTED_PACKAGE};` declaration")

    imports = re.findall(r"(?m)^\s*import\b([^;]*);", code)
    for declaration in imports:
        imported = re.sub(r"\s+", "", declaration)
        if imported.startswith("static"):
            fail(f"static import is not allowed: {imported}")
        if imported.startswith(FORBIDDEN_IMPORT_PREFIXES):
            fail(f"prohibited import: {imported}")
        if imported not in ALLOWED_IMPORTS and not imported.startswith(ALLOWED_PREFIXES):
            fail(f"import is outside the documented safe subset: {imported}")

    thread_call = re.compile(r"\bThread\s*\.\s*currentThread\s*\(\s*\)\s*\.\s*isInterrupted\s*\(\s*\)")
    without_allowed_thread = thread_call.sub("true", code)
    if re.search(r"\bThread\b", without_allowed_thread):
        fail("Thread is allowed only as Thread.currentThread().isInterrupted()")

    for word in FORBIDDEN_WORDS:
        if re.search(rf"\b{re.escape(word)}\b", code):
            fail(f"prohibited API or keyword: {word}")
    qualified_forbidden = re.compile(
        r"\b(?:java\s*\.\s*(?:net|nio\s*\.\s*file|lang\s*\.\s*(?:invoke|management|reflect)|security|util\s*\.\s*concurrent)"
        r"|javax\s*\.\s*management|jdk\s*\.|sun\s*\.)"
    )
    if qualified_forbidden.search(code):
        fail("prohibited fully-qualified package reference")
    for marker in FORBIDDEN_MARKERS:
        if marker in source:
            fail(f"prohibited benchmark/runtime marker: {marker}")

    print("source policy: Analyzer.java accepted")


def main() -> None:
    if len(sys.argv) != 2:
        print("usage: source_policy.py PATH_TO_ANALYZER", file=sys.stderr)
        raise SystemExit(2)
    audit(Path(sys.argv[1]))


if __name__ == "__main__":
    main()
