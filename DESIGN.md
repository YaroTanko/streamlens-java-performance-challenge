# StreamLens Java Design

`PRD.md` defines behavior. This document describes component boundaries and the
performance assessment architecture.

## Runtime flow

```text
Reader
  -> one non-empty NDJSON line at a time
  -> Jackson token parsing and field validation
  -> from/to/type filters
  -> UTC epoch-aligned fixed window
  -> exact group and user aggregates
  -> deterministic group and top-user ordering
  -> List<Group>
  -> CLI JSON generator
```

- `com.streamlens.analyzer` owns parsing, validation, filtering, aggregation, and
  deterministic records.
- `com.streamlens.cli` owns arguments, input selection, output, diagnostics, and
  exit status.
- `src/jmh` owns deterministic protected workload generation and JMH framing.
- `scripts` owns source/scope/notes validation, synthetic-tree preparation, and
  JFR profiling.

The candidate may replace internals of only `Analyzer.java`. `Config`, result
records, CLI, tests, benchmark source, Gradle metadata, and scripts are protected.

## Invariants

- Required exact duplicate fields use only the last value.
- Unknown fields are syntax-checked and skipped without application conversion.
- Input, error, group-addition, and user-addition order is preserved.
- Filtering precedes aggregation.
- Windows use mathematical floor division from `0001-01-01T00:00:00Z`, matching
  the Go StreamLens zero-time truncation anchor.
- Every count and user membership is exact; every sum is sequential `double`.
- Top-user and group ordering are total and deterministic.
- Interruption is checked without mutating the thread flag.
- Empty output is a non-null list and serialized `[]`.

## Assessment construction

```text
baseline-v1 runtime + exact candidate Analyzer.java + exact candidate notes
  -> input/scope/source/notes gates
  -> protected JUnit correctness
  -> alternating protected JMH samples
  -> independent JFR diagnostics
  -> summary + raw JSON/JFR + evidence manifest
```

Scope is computed between the upstream starter/base commit and candidate commit.
Execution is different: it always begins with the pinned immutable baseline and
overlays only the two candidate blobs. This distinction prevents starter workflow
files from appearing as candidate changes and prevents candidate build/test code
from entering the assessment tree.

The baseline intentionally uses plausible but costly choices: whole-input line
retention, a new parser factory per event, linear group/user lookups, and full user
sorting. The source does not label individual solutions; candidates are expected
to profile and choose a reviewable optimization.

JMH primary scores use average-time `ns/op`; the GC profiler supplies normalized
`B/op`. The evaluator compares medians and geometric means. JFR records are for
hotspot explanation only because profiling changes runtime behavior.

The source guard closes common same-process interference paths but cannot prove
semantics. The synthetic overlay, fixed commands, bounded isolated evaluator, and
human review are independent layers.
