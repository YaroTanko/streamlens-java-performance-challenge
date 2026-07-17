# StreamLens Java Performance Challenge — Product Requirements

**Status:** Approved for assessment version 1

**Runtime:** Java 21

**Source of truth:** This document wins if another repository document conflicts.

## 1. Product and assessment

StreamLens accepts UTF-8 NDJSON events, filters them, groups them into fixed UTC
windows, and emits deterministic aggregate JSON. The repository is a 30-minute,
AI-assisted Java performance exercise. Its starter must be correct and plausibly
inefficient, with independent opportunities in parsing, lookup, allocation, and
top-K selection.

The immutable `baseline-v1` package defines the runtime, tests, workloads,
dependencies, and starter implementation. The starter workflow pins the full
baseline commit SHA; the human-readable tag is not a moving source of truth.

## 2. Candidate journey and timing

The candidate forks the public repository, prepares Java 21, reads the task and
contract, runs correctness, benchmark, and profile commands, edits only permitted
deliverables, records a final SHA within 30 minutes, and opens a pull request.
Environment setup, push/PR creation, CI queue/runtime, and report reading are
outside the timer. AI and profiling tools are allowed and must be used honestly.

## 3. Event input

Each non-empty line is one JSON object:

```json
{"timestamp":"2026-01-15T12:34:56.123Z","tenant_id":"acme","user_id":"user-42","type":"purchase","value":19.95}
```

| Field | Required value |
| --- | --- |
| `timestamp` | RFC3339 timestamp with explicit offset and at most nanosecond precision |
| `tenant_id` | non-empty JSON string |
| `user_id` | non-empty JSON string |
| `type` | non-empty JSON string |
| `value` | finite, non-negative IEEE-754 Java `double` JSON number |

Field names are case-sensitive. Differently cased fields are unknown. Unknown
values must be syntactically valid JSON but are otherwise ignored without
application conversion. For an exact duplicate required field, only the last
value is interpreted. Empty and whitespace-only lines are ignored.

Processing follows input-line order. Malformed or invalid data stops processing
with `AnalysisException` containing the one-based line number. An aggregation
overflow on an earlier line takes precedence over any later parse error. Lines
may be larger than traditional scanner defaults.

## 4. Configuration and filtering

`Config` supports optional inclusive `from`, optional exclusive `to`, an optional
event-type allow-list, a positive fixed window, and positive top-K. A null window
defaults to one minute; top-K zero defaults to three. Invalid values fail before
input processing. Filtering occurs before aggregation.

## 5. Aggregation

The group key is:

1. UTC window start;
2. `tenant_id`;
3. event `type`.

Window start mirrors Go's fixed-window `time.Time.Truncate` anchor: it is the
mathematical floor of nanoseconds elapsed since `0001-01-01T00:00:00Z` by the
positive window length, represented as an `Instant`. This matters for windows
that do not evenly divide a day and also covers timestamps before that anchor.

Each group contains exact event count, sequential `double` value sum, exact unique
user count, and up to K users with the largest sequential per-user sums. Group and
user additions occur in input order. Non-finite addition results are processing
errors. Reordering additions or approximate arithmetic is prohibited.

Top users order by descending value and ascending user ID for ties. Groups order
by ascending window start, tenant ID, and event type. No matches return an empty,
non-null list and CLI JSON `[]`.

Canonical output fields and order are:

```json
[{"window_start":"2026-01-15T12:34:00Z","tenant_id":"acme","type":"purchase","count":2,"sum":30.0,"unique_users":2,"top_users":[{"user_id":"user-42","value":19.95},{"user_id":"user-7","value":10.05}]}]
```

## 6. API, CLI, and interruption

The protected in-repository API is:

```java
Analyzer.analyze(Reader input, Config config)
```

Null input/config are rejected. Interruption is observed before reading and
between completed reads and aggregation steps through the thread interruption
flag. The analyzer need not interrupt a `Reader` blocked inside `read()`.

The CLI reads a file or standard input, writes JSON to standard output, writes
diagnostics to standard error, and exits non-zero for invalid input/configuration.
It supports inclusive `--from`, exclusive `--to`, repeatable `--type`, ISO-8601
`--window`, and positive `--top-k`.

## 7. Dependencies and source boundary

The runtime dependency is the protected Jackson-core version pinned in Gradle.
Candidate `Analyzer.java` may import its `JsonFactory`, `JsonParser`, and
`JsonToken`, ordinary reader/error classes from the documented `java.io` subset,
and `java.math`, `java.time`, or `java.util` APIs.

The active version-1 guard rejects filesystem, network, process, native,
reflection/method-handle, class-loading, security, concurrent executor,
management, JFR/diagnostic/internal-JDK, direct-output, JVM-global, and protected
benchmark-recognition APIs. Static imports and Java Unicode escapes are rejected.
The only permitted `Thread` expression is
`Thread.currentThread().isInterrupted()`. The guard is intentionally local to
candidate `Analyzer.java` and is not a complete language sandbox.

## 8. Verification and workloads

JUnit tests cover valid parsing, unknown and duplicate fields, blank/large lines,
line-numbered validation, filters, UTC alignment, exact aggregation/order,
sequential sums, overflow, interruption, and CLI behavior.

JMH data is generated deterministically outside measured invocations. Scenarios:

1. `balanced`: representative groups and users;
2. `highCardinality`: many group/user keys;
3. `mostlyFiltered`: valid input mostly removed by configuration.

JMH JSON retains primary average time in `ns/op` and secondary
`gc.alloc.rate.norm` in `B/op`. The authoritative private evaluator alternates at
least five baseline/candidate samples per scenario on the same runner, uses
medians, and reports geometric-mean improvement per metric. JFR is diagnostic and
never mixed into scored samples.

## 9. Scoring and gates

Correctness, candidate input safety, allowed scope, and source policy are
mandatory. Changed notes require 5–10 Markdown bullets and a non-empty truthful
`Profile evidence:` bullet. Unchanged notes are reported as not submitted for
`implementation-only`. A `notes-only` submission cannot earn performance credit.

Metric tiers are Below target below 20%, Middle from 20%, Senior from 50%, and
Staff from 75%. The overall optimization tier is the highest reached metric.
Passing performance requires one metric at or above 20%, no geometric-mean metric
regression beyond 20%, and no individual scenario/metric regression beyond 30%.

Near a threshold (within two percentage points), an interviewer may rerun the
same SHA once and retain the lower inconsistent result. The optimization tier is
not a hiring or candidate-seniority determination.

## 10. Trust and versioning

The private evaluator constructs a clean immutable-baseline tree and overlays
only regular Git blobs for `Analyzer.java` and `OPTIMIZATION.md`. Candidate tests,
build metadata, scripts, generated files, submodules, and workflows are not used.
Trusted commands run with fixed toolchain, bounds, and retained manifests.

Public CI is a convenience and uses a read-only token with trusted workflow and
baseline scripts. It is not the authoritative security boundary. The exercise is
non-adversarial and exact source review remains required. Any material contract,
test, workload, dependency, toolchain, runner, or policy change creates a new
immutable assessment version.
