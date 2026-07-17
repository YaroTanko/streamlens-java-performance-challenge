# StreamLens Java Design

## Purpose and status

StreamLens transforms strict UTF-8 NDJSON into deterministic aggregates. It is
small enough to understand during a 30-minute exercise, while the supplied
implementation leaves multiple realistic Java CPU and allocation trade-offs.

`PRD.md` defines observable behavior; this document explains component boundaries
and invariants. Live `java-v4` activation status and pins are authoritative only
in `RELEASES.md` and the trusted assessment workflow on the upstream protected
default branch. `baseline-v4` is the immutable candidate snapshot (**B**), so its
pre-activation status prose and intentionally `PENDING` runner values remain
historical and non-authoritative after a later protected activation commit (**A**).
Candidates remain on B and never merge, rebase, or update from the default branch;
the scope guard compares `B..candidate`.

## Components

- `com.streamlens.StreamLens` owns CLI flags, file/stdin selection, deterministic
  JSON output, diagnostics, and exit status.
- `com.streamlens.analyzer.Analyzer` owns strict decoding, JSON parsing,
  validation, filtering, windowing, aggregation, and result ordering. It is the
  sole candidate-editable Java source.
- `AnalyzerConfig` carries optional bounds/type filtering plus window and top-K.
- `Group`, `TopUser`, and `AnalysisException` define the fixed in-repository API.
- Protected tests own contract and CLI coverage.
- Protected JMH sources own deterministic Balanced, HighCardinality, and
  MostlyFiltered workloads; input construction occurs outside timed operations.
- Trusted scripts/workflows own scope/source checks, synthetic-tree construction,
  isolated execution, comparison, profiles, and evidence manifests.

The analyzer entry point is:

```java
static List<Group> Analyzer.analyze(InputStream input, AnalyzerConfig config)
    throws AnalysisException, InterruptedException
```

## Processing model

```text
InputStream
  -> strict UTF-8 line reader
  -> for each non-empty physical line, in input order:
       parse one complete JSON object
       remember only the final occurrence of each exact known field
       validate the final known values
       apply instant and type filters
       compute year-one-anchored UTC window
       update exact counts and sequential double sums
  -> check interruption
  -> order groups and top users
  -> fixed API result records and ordered lists
  -> CLI deterministic JSON serializer
```

The complete JSON line is syntax-checked even when it is filtered or contains
ignored fields. Unknown values are skipped structurally rather than converted to
application values. An error terminates immediately with its one-based physical
line number. Aggregation of one accepted event completes before the next read, so
earlier overflow wins over later malformed input.

## API defaults and validation

`AnalyzerConfig` contains `Instant from`, `Instant to`, `List<String> types`,
`Duration window`, and `int topK`.

- null `from`/`to` means unbounded;
- null or empty type list means no type filter;
- null or zero API window defaults to one minute;
- API `topK == 0` defaults to three;
- negative/otherwise non-positive explicit values are invalid; and
- invalid configuration and null API arguments fail before input is consumed.

The CLI distinguishes omitted defaults from explicit zero flags and rejects the
latter.

## Behavioral invariants

- The byte decoder reports malformed UTF-8; it never substitutes U+FFFD for bad
  input bytes. Separately, decoded known JSON strings replace syntactically valid
  but unpaired escaped UTF-16 surrogates with U+FFFD.
- Blank/whitespace-only lines are ignored, but still count toward physical line
  numbers.
- Required JSON names are compared after JSON string decoding and are
  case-sensitive; lexically different escaped spellings may decode to one name.
- Only the final exact duplicate known field is application-converted; all values
  remain subject to JSON syntax validation.
- Filtering occurs after event validation and before aggregation.
- `from` is inclusive and `to` is exclusive.
- Timestamp parsing is strict, locale-free, and requires an explicit offset.
- Window alignment uses mathematical floor from `0001-01-01T00:00:00Z` at
  nanosecond precision, including instants before the Unix epoch.
- Any positive `Duration` is valid; an unrepresentable aligned instant is a
  checked, line-numbered window-alignment error rather than wraparound/clamping.
- The grouping key is instant window start, tenant ID, and event type.
- Counts and membership are exact. Group and user sums perform ordinary Java
  `double` addition in input order; infinity is an error.
- Top users order by numeric descending sum then `String.compareTo(userId)`.
- Groups order by instant, tenant ID, then type; strings use raw UTF-16 code-unit
  ordering with no locale or normalization.
- Empty results are non-null and serialize as `[]`.
- Identical Java 21 inputs/configuration produce byte-identical CLI JSON.
- The current thread's interrupt status is preserved when interruption aborts.
- An arbitrary `InputStream` blocked inside `read()` need not be interruptible.
- The analyzer never closes the caller-owned `InputStream`; the CLI closes only a
  file stream that it opened itself.

## Java-specific choices

### Strict text and JSON

Default `InputStreamReader` replacement behavior is insufficient for this
contract. The analyzer uses a decoder configured to report malformed/unmappable
input, and the protected tests cover sequences split across input-buffer reads.

The JDK has no general JSON parser in Java 21, so the analyzer contains its own
small parser. That parser must accept the RFC 8259 value grammar, decode valid
escapes and surrogate pairs, reject raw controls and malformed escapes, replace
unpaired escaped surrogates with U+FFFD for decoded known strings, and skip nested
unknown arrays/objects without coercing their numeric values.

### Time

Accepted timestamp text has exactly the documented four-digit RFC 3339 shape,
offset range `±00:00` through `±23:59`, and up to nine fractional digits. It is
parsed under strict resolver rules, rejects leap-second `:60`, and converts to
`Instant`. Windowing is
duration/year-one-anchor arithmetic, not
`ZonedDateTime`/default-zone calendar arithmetic. UTC output always includes
seconds and `Z`; fractional trailing zeroes are removed.

### Floating point

Required `value` text is converted with Java binary64 semantics and rejected if
non-finite or numerically negative. `-0` is valid. Accumulator updates are
sequential and checked after each addition. Numeric top-user ties, including
opposite signed zeroes, fall through to user ID.

CLI numeric JSON uses the fixed serializer and a `Double.toString`-compatible
representation, so locale and formatting defaults cannot affect output.

## Performance model

JMH reports `ns/op` and `gc.alloc.rate.norm` (`B/op`). Each scenario gets JVM
warmup and multiple isolated measurements. CI alternates baseline/candidate
samples on one runner, takes per-scenario medians, then geometric means across
the three shapes. Aggregate and per-scenario regression guards prevent a narrow
win from hiding a material loss.

The trusted parent derives a fresh seed for each assessment, materializes the
public corpus from that seed, and asks the immutable baseline oracle for complete
result digests before candidate measurement. The public corpus is therefore
repeatable within one assessment but not a fixed release fixture. It remains a
public guard, not a replacement for the separately maintained private evaluator
and human review required before scoring.

JFR CPU and allocation profiles run separately because recording changes runtime
conditions. They guide diagnosis and review but never enter scored samples.
Exact `objects/op` is not a `java-v4` scored metric.

## Candidate integrity boundary

Candidates change only `Analyzer.java` and `OPTIMIZATION.md`. A compiler-API
source audit parses and type-checks the exact analyzer blob against an explicit
safe JDK allow-list. It rejects external effects, process/JVM control, dynamic
code access, instrumentation, output/logging, test hooks, and benchmark markers.
This makes common violations reviewable; it is not a complete Java sandbox.

The assessor constructs both measured trees from the immutable baseline and
overlays only the candidate's two committed regular files. All tests, workloads,
build logic, scripts, and workflows therefore come from the baseline. The
candidate branch must contain no default-branch activation update: the trusted
scope audit examines `baseline-v4..candidate` and permits only those two files.

## Restricted execution and evidence

```text
trusted baseline + two candidate overlays
  -> committed-scope and typed-source audit
  -> fresh synthetic trees
  -> fixed commands in exact digest-pinned restricted container
  -> bounded trusted-parent capture and validated CID cleanup
  -> benchmark/profile artifacts
  -> deterministic manifest core + volatile environment envelope
```

The container is non-root with a read-only root and baseline input mount; its
build copy exists only in bounded private tmpfs and has no candidate-writable host
mount. It has no network or IPC, dropped capabilities, no privilege escalation,
and bounded CPU, memory, PIDs, files, wall time, and output. Only the trusted
parent frames output and writes artifacts. Cleanup accepts only the invocation's
validated container ID and fails closed.

A real canary against the exact image must demonstrate those runtime properties
before public `java-v4` activation; mocks are not release evidence. The manifest
hashes retained artifacts and records exact revisions/parameters. It improves
traceability without proving candidate authorship or eliminating same-process
risk.
