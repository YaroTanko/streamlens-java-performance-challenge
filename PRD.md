# StreamLens Java Performance Challenge — Product Requirements Document

**Live release authority:** Current activation status and pins are determined
only by `RELEASES.md` and the trusted assessment workflow on the upstream
protected default branch. Immutable baseline tags and candidate branches are
snapshots, not live release records.

**Version:** 3.0

**Implementation language:** Java 21
**Source of truth:** If another repository document conflicts with this PRD,
this PRD wins.

## 1. Product summary

StreamLens is a small command-line application with an in-repository Java
analyzer. It reads UTF-8 newline-delimited JSON (NDJSON), filters events, groups
them into fixed UTC-aligned windows, and emits deterministic aggregate results.

The repository is also a 30-minute, AI-assisted Java performance exercise. The
supplied implementation is correct but leaves several realistic CPU and
allocation opportunities. A candidate profiles it, improves only the analyzer,
documents the reasoning, and opens a pull request. CI checks correctness and
compares the candidate against an immutable baseline on the same runner.

## 2. Assessment purpose

The exercise is designed to collect evidence that a candidate can:

1. orient themselves in an unfamiliar, documented Java codebase;
2. use profiling, benchmarks, source analysis, and AI responsibly;
3. preserve observable behavior while changing implementation details;
4. identify and reduce a real CPU or allocation cost; and
5. explain the evidence, change, and trade-offs concisely.

The task is one structured work sample. Its numeric result is not, by itself, a
validated job-level classification or hiring decision. See `SCORECARD.md` and
`INTERVIEWER_GUIDE.md` for the required human review.

## 3. Users and timing

The candidate is a Java engineer completing timed work with any AI assistant,
editor, profiler, or local analysis tool. The interviewer supplies a clean
checkout and reviews the process, final diff, explanation, and CI evidence.

The timer begins only after the clean checkout is available and Java 21 can run
the project. It ends at 30:00 or when the candidate records the final local
commit SHA, whichever comes first. Clone and JDK setup, pushing that exact SHA,
pull-request creation, Actions approval/queue/runtime, and reading the final CI
report are untimed. No implementation or notes may be changed after recording
the SHA, except rerunning infrastructure against that same SHA.

## 4. Goals

- Provide a complete Java project rather than an isolated algorithm puzzle.
- Keep the contract understandable and the change reviewable in 30 minutes.
- Support multiple legitimate CPU and allocation optimization paths.
- Make functional output deterministic and comparison noise low enough for a
  same-run baseline decision.
- Require a truthful profile observation before accepting an optimization.
- Allow AI assistance openly while retaining meaningful human-review signals.
- Keep fork pull-request CI read-only and avoid executing candidate-owned tooling.

## 5. Non-goals and integrity model

- The exercise does not assess networking, persistence, distributed systems, or
  framework expertise.
- The optimization tier does not establish the candidate's seniority.
- Passing does not guarantee a hire; failing one noisy run does not prove lack of
  engineering ability.
- The baseline contains no sleeps, deliberate deadlocks, broken behavior, test
  detection, or misleading benchmark shortcuts.
- Approximate aggregation, skipped valid work, and reordered sums are forbidden.
- The public repository is not a secret or adversarial security challenge.

Protected paths and a source audit reduce accidental or straightforward
interference. A synthetic baseline overlay and restricted container reduce trust
in candidate repository contents. None is a complete sandbox for same-process
Java code, so interviewers must inspect the exact diff and explanation.

### 5.1 `java-v6` activation

A protected upstream default branch may call `java-v6` active only when all of
the following exist as one immutable, verifiable release unit:

- a `baseline-v6` tag that points to an immutable baseline commit;
- a workflow pin to that full commit SHA;
- an immutable container image pinned by digest;
- a successful real-runtime canary using that exact image; and
- retained evidence showing the canary covered the documented restrictions;
- retained calibration evidence for the published thresholds; and
- a pinned, reviewed private-evaluator pre-score gate.

Until then, local tests and benchmarks are development evidence only, the
protected-default-branch release record must say **pending activation**, and no
candidate should be scored under `java-v6`. The immutable `baseline-v6` tag is
the candidate snapshot (**B**), so its pre-activation prose and `PENDING` runner
values remain historical after a later protected activation commit (**A**); they
are intentionally not status authority. A activates the release by pinning B and
the image on the default branch without moving B. Candidate branches start from B
and must not merge, rebase, or update from the upstream default branch: scope is
checked as `B..candidate` and may change only the two allowed files. Changing the
contract, workloads, JDK, source policy, runner, or image after activation
requires a new version and baseline rather than moving an existing tag or pin.

## 6. Candidate journey

1. Fork the repository and create a branch from the immutable `baseline-v6` commit.
2. Do not merge, rebase, or update that candidate branch from the upstream default
   branch; it can contain later mutable activation metadata outside candidate scope.
3. Read `README.md` and `TASK.md`; consult `PRD.md`, `DESIGN.md`, and `AGENTS.md`.
4. Run correctness, a local benchmark, and at least one measured profile.
5. Use the evidence and any AI assistant to choose an optimization.
6. Change only `Analyzer.java` and `OPTIMIZATION.md`.
7. Preserve the full contract and rerun the checks.
8. Record 5–10 concise bullets, including the actual profiler and hotspot.
9. Commit and record the SHA before the timer ends.
10. Push that SHA, open an upstream PR, and review the CI result.

## 7. Event input

Input is strict UTF-8 NDJSON. A malformed UTF-8 byte sequence is an error; it is
never silently replaced. Each non-empty line contains exactly one JSON object:

```json
{"timestamp":"2026-01-15T12:34:56.123Z","tenant_id":"acme","user_id":"user-42","type":"purchase","value":19.95}
```

A line is ignored when trimming Java whitespace (`Character.isWhitespace` or
`Character.isSpaceChar`, plus U+0085) makes it empty. Ignored lines still count
toward physical line numbers. Lines are processed in input order and may be larger
than the default buffer of common line-reading APIs.

### 7.1 Required fields

| Field | JSON type | Requirements |
| --- | --- | --- |
| `timestamp` | string | strict four-digit year `0000`–`9999`, valid calendar/time with seconds `00`–`59`, explicit `Z` or `±HH:MM` offset (`00`–`23` hours, `00`–`59` minutes), and 0–9 fractional-second digits |
| `tenant_id` | string | non-empty Java string |
| `user_id` | string | non-empty Java string |
| `type` | string | non-empty Java string |
| `value` | number | parses to a finite Java `double` and is numerically greater than or equal to zero |

Field names are compared after JSON string decoding and are case-sensitive. Thus
an escaped spelling that decodes to `timestamp` is the same known name, while a
differently cased decoded name is unknown.
The complete object must be syntactically valid JSON. Unknown values may contain
any valid nested JSON and are ignored without application-level conversion; for
example, an out-of-`double`-range number in an unknown field is not a validation
error.

If the same decoded field name appears more than once, only its last value is interpreted
and application-validated. Earlier duplicate values still must be syntactically
valid JSON, but are not converted. JSON string escapes and hex digits must be
syntactically valid and raw controls are rejected. When a known name/value is
decoded, an unpaired escaped UTF-16 surrogate is replaced with U+FFFD, matching
the fixed baseline JSON contract; a valid high/low pair becomes its code point.
Unknown values are syntax-checked structurally without application decoding.

The JSON number grammar is the RFC 8259 grammar. `NaN` and infinities are not JSON
numbers. A required `value` that parses to infinity is rejected. `-0` is accepted
because Java numeric comparison considers it non-negative; subsequent arithmetic
uses its actual `double` value.

### 7.2 Error precedence

A malformed or invalid event stops analysis with an error containing the one-based
physical input line number. Each accepted event is filtered and, when applicable,
aggregated before the next line is processed. Therefore an aggregation overflow
on an earlier line wins over malformed input on a later line.

## 8. Configuration and filtering

The analyzer and CLI support:

- optional inclusive `from` instant;
- optional exclusive `to` instant;
- optional event-type allow-list;
- a positive fixed window, default one minute; and
- a positive top-K user count, default three.

Filtering occurs after event validation but before aggregation. Events outside the
time interval or type allow-list do not contribute to output. A null or empty
type list means that no type filter is applied. Invalid configuration is rejected
before input is consumed.

## 9. Java-specific time semantics

Input timestamps represent an instant plus an explicit offset. Calendar text is
parsed without using the host locale or default time zone, normalized to
`java.time.Instant`, and compared as an instant. Equivalent offsets therefore
group together. Leap-second text (`:60`) is not accepted by `java-v6`.

Window alignment is fixed-duration and UTC-based, not local-calendar-based. To
preserve the original StreamLens contract, the anchor is
`0001-01-01T00:00:00Z` (the absolute-zero anchor used by Go
`time.Time.Truncate`), not the Unix epoch. The window start is the greatest anchor
plus integer multiple of the positive duration that is less than or equal to the
event instant. Calculation uses mathematical floor and nanosecond precision and
never depends on the process default time zone.

Any positive `Duration` supported by `AnalyzerConfig` is accepted. If aligning a
particular accepted event would place the window start outside Java `Instant`'s
representable range or the four-digit output range, analysis returns a checked
line-numbered `AnalysisException`; it does not wrap or clamp the timestamp.

CLI `window_start` is UTC text with a mandatory seconds field and `Z` suffix.
When fractional seconds are non-zero, trailing zeroes are removed. Identical
inputs and configuration must produce byte-for-byte identical JSON on Java 21.

## 10. Aggregation and ordering

Events are grouped by window start, `tenant_id`, and event `type`. Every group
contains:

- exact event count as a Java `long`;
- a sequential Java `double` sum of `value`;
- exact unique-user count; and
- up to K users with the highest sequential per-user sum.

Group and per-user sums use ordinary IEEE 754 binary64 addition in input order.
Implementations must not sort, batch, compensate, parallel-reduce, use decimal
arithmetic, or otherwise change the sequence of additions. If an addition becomes
positive infinity, analysis fails on that input line. (`NaN` and negative values
cannot originate from a valid `value`.)

Top users are ordered by descending numeric sum and then ascending `user_id`.
Numeric equality, including `-0.0` and `+0.0`, is a tie resolved by user ID; do
not use `Double.compare` in a way that changes that rule. Groups are ordered by
ascending instant, then ascending tenant ID, then ascending event type.

All string ordering is exactly Java `String.compareTo`: lexicographic ordering of
UTF-16 code units, independent of locale, collation settings, or Unicode
normalization. Strings are not normalized; canonically equivalent code-point
sequences remain distinct identifiers.

No matching events produce an empty, non-null result list and CLI JSON `[]`. A
canonical result is:

```json
[{"window_start":"2026-01-15T12:34:00Z","tenant_id":"acme","type":"purchase","count":2,"sum":30.0,"unique_users":2,"top_users":[{"user_id":"user-42","value":19.95},{"user_id":"user-7","value":10.05}]}]
```

Finite output numbers use the repository's deterministic Java serializer and the
same representation as `Double.toString` for non-integral and integral `double`
values. Field order, array order, escaping, and timestamp formatting are fixed.

## 11. Java API, interruption, and CLI

The in-repository API is:

```java
Analyzer.analyze(InputStream input, AnalyzerConfig config)
    throws AnalysisException, InterruptedException
```

`AnalyzerConfig` contains optional `Instant from`, optional `Instant to`, a
possibly-null list of allowed types, a `Duration window`, and `int topK`. A null
or zero API window defaults to one minute; API `topK == 0` defaults to three.
Negative values are invalid. A null input or config is rejected without reading.

The analyzer observes the current thread's interrupt status before processing,
after every completed input read, before aggregation, and before result
finalization. It throws `InterruptedException` without clearing the interrupt
status. It need not make an arbitrary `InputStream` blocked inside `read()`
interruptible. The analyzer never closes the caller-supplied stream.

The `streamlens` CLI reads a file or standard input and supports `--input`,
`--from`, `--to`, `--types`, `--window`, and `--top-k`. CLI timestamps follow the
same strict timestamp syntax. CLI durations accept ISO-8601 `Duration` text or
nanosecond-exact Go-like sequences using `ns`, `us`/`µs`/`μs`, `ms`, `s`, `m`,
and `h`. Unlike the API default sentinels, an explicitly supplied zero duration or
top-K flag is invalid. JSON goes to standard output, diagnostics to standard
error, and any processing error exits non-zero.

Production application and analyzer code use Java 21 and only the JDK. JUnit,
JMH, and profiling/build tools are trusted assessment infrastructure, not
production dependencies available to candidate `Analyzer.java`.

## 12. Correctness verification

Protected tests cover at least:

- strict UTF-8, JSON syntax, nested ignored values, and large lines;
- exact duplicate fields and differently cased unknown fields;
- required-field presence, type, and validation with line numbers;
- required out-of-range numbers versus ignored out-of-range numbers;
- strict timestamps, non-UTC offsets, negative instants, and nanoscale windows;
- inclusive `from`, exclusive `to`, and type allow-list filtering;
- exact count, sequential sums, overflow, unique users, and top-K;
- Java UTF-16 group/user tie ordering and deterministic JSON escaping;
- interruption timing and preservation of interrupt status;
- empty output, API defaults/validation, and CLI success/failure behavior; and
- earliest-line error precedence.

All correctness and source-policy checks must pass before any performance result
is accepted.

## 13. Benchmark design

Deterministic input is generated outside the timed region. At least three JMH
scenarios exercise different workload shapes:

1. **Balanced** — representative tenants, types, users, and windows.
2. **HighCardinality** — many group keys and users.
3. **MostlyFiltered** — most valid input is excluded by configuration.

The authoritative scored metrics are:

- execution time in `ns/op`; and
- normalized allocation volume in `B/op` (`gc.alloc.rate.norm`).

Java's standard JMH/JFR toolchain does not provide an equally stable exact
`objects/op` measurement across these runs. Object-allocation events and counts
may be published as diagnostics, but `java-v6` must not score an allocation-count
tier unless a separately validated, reproducible method is added in a new
assessment version.

CI runs baseline and candidate in separate, equivalently configured JVM forks on
the same runner. Warmup is never treated as a scored sample. Samples alternate
baseline and candidate order, each scenario retains at least five valid samples
(normally seven), and the report uses the median per scenario followed by a
geometric mean across scenarios for each metric.

For every assessment, the trusted parent creates a fresh randomized public corpus
from per-assessment entropy. The immutable baseline first computes the complete
expected-result record for that corpus; candidate verification and measurement use
that same authenticated record. The corpus generator and verifier are protected
release assets, not candidate-controlled files. This limits fixed-fixture special
cases, but the public corpus is not a secret and does not replace the private
evaluator.

Every retained sample is strictly framed by the trusted parent with a per-run
random token and sequential sample number. Its JMH JSON contains exactly one
average-time row for each protected scenario, the expected `ns/op` primary unit,
and `gc.alloc.rate.norm` in `B/op`; output outside the frame, token mismatch,
duplicate/missing/unexpected scenarios, non-finite values, or inconsistent sample
counts fail comparison as infrastructure error.

Near a 20%, 50%, or 75% boundary, normal hosted-runner noise can change a tier. If
an aggregate is within two percentage points of a boundary, the interviewer
reruns the exact same candidate SHA once. If tier or pass/fail differs, the lower
result is used and both reports are retained. No code or note changes are allowed
for the rerun.

### 13.1 Baseline quality

The baseline must be correct and its costs must follow from plausible Java
data-processing choices, not artificial delay or deliberately broken behavior.
It contains several independent opportunities so one focused improvement can be
meaningful and combined algorithm/data-flow improvements can reach higher tiers.
Source comments do not label bottlenecks or prescribe a solution.

Before activation, maintainers retain profiles naming actual hotspots, repeated
noise measurements, and at least one independently reviewed ordinary solution
for every published tier. Reference solutions must preserve the entire contract,
pass the same source policy, and contain no fixture/scenario recognition. They are
maintainer calibration artifacts and are never present in a candidate checkout.

## 14. Scoring policy

For each scored metric:

```text
improvement = (baseline - candidate) / baseline * 100
```

| Geometric-mean improvement | Reported optimization tier |
| --- | --- |
| less than 20% | Below target |
| 20%–49.99% | Middle |
| 50%–74.99% | Senior |
| 75% or more | Staff |

The overall numeric result is the highest tier reached by either scored metric.
The performance gate passes only when:

- at least one scored metric improves by 20% or more;
- neither scored metric's geometric mean regresses by more than 20%; and
- no scenario/metric pair regresses by more than 30%.

Exactly -20.00% aggregate or -30.00% per-scenario is allowed; a larger regression
fails. Correctness, scope, source-policy, or response-format failure always fails
the assessment before performance is interpreted.

The public assessor is a necessary gate, not the final score decision. Before a
candidate is assigned a score, maintainers must run the separately maintained,
pinned private evaluator for the same committed candidate SHA and review its
result together with the human debrief. The private evaluator is intentionally
outside the candidate repository and is a release precondition. Whether that gate
has been completed for a session is determined by the live protected-default-
branch release record, not by a baseline or candidate snapshot.

These labels describe this implementation's measured result against this
baseline. They do not prove Java level, general seniority, authorship, learning
ability, communication quality, or hiring suitability. The interviewer must use
the behavioral rubric in `SCORECARD.md`.

## 15. Profiling evidence

Candidates run at least one measured CPU or allocation profile before choosing a
change. The repository provides repeatable JFR-based targets, while another
profiler is equally valid. `OPTIMIZATION.md` names the actual command/tool and a
hotspot that was observed; source inspection alone is not described as a profile.

CI captures independent candidate diagnostics outside the scored sample loop.
Those artifacts help a reviewer test plausibility but cannot prove which tool the
candidate used, who authored a change, or whether the candidate understands it.
The interviewer verifies understanding through targeted follow-up questions.

## 16. Candidate scope and safe-JDK policy

Candidate pull requests may modify exactly:

- `src/main/java/com/streamlens/analyzer/Analyzer.java`; and
- `OPTIMIZATION.md`.

Tests, fixtures, benchmark sources, build and module metadata, scripts, all other
production sources, documentation other than `OPTIMIZATION.md`, generated files,
submodules, and workflows are protected. The candidate may not move analyzer
implementation into another file.

The submitted `Analyzer.java` is limited to a reviewable subset of Java 21 APIs
needed for in-memory parsing, time calculation, collections, and exact
aggregation. It must not use or reach APIs for:

- filesystem access or arbitrary output;
- sockets, URLs, networking, IPC, or subprocesses;
- environment, system properties, JVM shutdown, process-wide runtime mutation,
  garbage-collection requests, or new threads/executors;
- reflection, method handles, dynamic proxies, class loading, service loading,
  instrumentation, management, JFR control, native access, serialization hooks,
  or test frameworks; or
- logging, standard-stream writes, protected benchmark recognition, or
  workload-specific constants/markers.

The source guard parses and type-checks the exact committed blob with the JDK
compiler API, resolves imports and referenced types, and permits only its explicit
safe list. It reports a reviewable offending construct. It is a defense-in-depth
workflow aid, not proof that arbitrary same-process Java is harmless. Human diff
review remains mandatory. Local profilers, debuggers, shell tools, IDEs, and AI
assistants outside `Analyzer.java` are not restricted by this policy.

For transparent, deterministic parsing, the blob must be valid UTF-8, non-empty,
at most 256 KiB, retain package `com.streamlens.analyzer` and one public top-level
`Analyzer`, and contain no raw Java Unicode-escape spelling (`backslash-u`),
static/wildcard import, native method, or annotation other than `@Override` and
`@SuppressWarnings`. Helper types may be nested in `Analyzer`; additional
top-level types are not allowed. These mechanical rules prevent ambiguous
pre-tokenization source and keep the one-file review boundary.

## 17. Baseline overlay and isolated execution

The authoritative assessor does not build or execute the candidate repository as
submitted. It:

```text
immutable baseline tree
  + committed candidate Analyzer.java
  + committed candidate OPTIMIZATION.md
  -> scope and source-policy validation
  -> fresh baseline-owned synthetic tree with only those two overlays
  -> fixed correctness/benchmark/profile commands in a restricted container
  -> bounded trusted-parent capture and validated container-ID cleanup
  -> deterministic evidence hashes plus volatile run metadata
```

Both deliverables must be regular files. Candidate tests, scripts, build files,
module metadata, generated files, symlinks, submodules, and workflows are neither
copied into nor executed from the synthetic tree.

The immutable digest-pinned container runs as a non-root user with a read-only
root and read-only baseline input mount. A build copy exists only in bounded
private tmpfs and is never mounted writable to the host. The container has no
network or IPC access, dropped Linux capabilities, no privilege escalation, and
explicit CPU, memory, process, file, deadline, and combined-output limits. The
trusted parent owns commands, framing, artifacts, and cleanup. It removes only the
validated container ID produced by that invocation; deadline, output-limit,
invalid-ID, or cleanup failures fail closed.

The exact release image must pass a real-runtime canary for host-write and network
restrictions, fixed-command execution, resource boundaries, and normal validated
CID cleanup. Separate bounded canaries cover deadline, output overflow, and
cleanup failure. Mock command-construction tests do not satisfy this activation
gate.

## 18. CI trust and evidence

Pull-request assessment uses a workflow definition from the trusted base branch,
explicit read-only repository permissions, no secrets, and an exact candidate
commit materialized only as untrusted data. No host command runs from the
candidate checkout. Candidate analyzer execution begins only inside the
restricted synthetic-tree container.

Every completed assessment report contains:

- scope, source-policy, and functional status;
- baseline and candidate values per scenario and scored metric;
- per-scenario changes and geometric-mean improvements;
- metric tiers, overall numeric tier, and precise pass/fail reason;
- CPU and allocation diagnostic summaries; and
- exact baseline/candidate revisions and assessment version.

Raw benchmark/profile outputs are retained as artifacts. A deterministic core
manifest records sorted revisions and assessment parameters plus SHA-256 and size
for every retained artifact. A separate envelope adds generation time and runner
metadata. Re-creating the core manifest from identical inputs must produce
identical bytes. This evidence chain improves reproducibility; it does not
cryptographically establish authorship or benign analyzer behavior.

## 19. Documentation set

Repository-facing text is English and includes:

- `PRD.md` — product and assessment source of truth;
- `TASK.md` — candidate rules, workflow, and scoring;
- `DESIGN.md` — components and behavioral invariants;
- `PROFILING.md` — profiler workflow and interpretation;
- `AGENTS.md` — canonical AI-agent constraints;
- `OPTIMIZATION.md` — candidate response template;
- `INTERVIEWER_GUIDE.md` — timed-session and evidence-review procedure;
- `SCORECARD.md` — behavioral rubric and decision boundaries;
- `CONTRIBUTING.md` — fork/branch/pull-request workflow; and
- `README.md` — short orientation and quick start.

## 20. Release acceptance criteria

`java-v6` is interview-ready only when current, retained evidence proves all of
the following:

1. a clean checkout builds and tests on the pinned Java 21 patch release;
2. all contract, CLI, source-policy, and protected-scope tests pass;
3. seed-deterministic Balanced, HighCardinality, and MostlyFiltered JMH workloads
   run from fresh per-assessment entropy;
4. the baseline is measurably but plausibly inefficient in both scored metrics;
5. independent, contract-preserving reference solutions demonstrate attainable
   20%, 50%, and 75% tiers without benchmark recognition;
6. repeated same-host comparisons characterize noise and support the thresholds;
7. the workflow pins the full immutable `baseline-v6` SHA and container digest;
8. the candidate tree is constructed from exactly two regular-file overlays;
9. no candidate-owned command or file other than those overlays is executed;
10. the exact image passes the complete real-runtime canary suite;
11. deadline, output, resource, and validated-CID cleanup paths fail closed;
12. evidence manifests reproduce for identical core inputs;
13. CPU and allocation profiles name real baseline hotspots;
14. every documented command, path, flag, metric, and artifact matches reality;
15. at least two dry-run candidates can understand and attempt the task in the
    timed boundary, with interviewer observations retained; and
16. the `java-v6` release is not called active until all preceding evidence is
    reviewed on the protected default branch;
17. the private evaluator is pinned, exercised for the candidate SHA, and reviewed
    as a pre-score gate; and
18. retained calibration demonstrates that the public thresholds are stable and
    attainable on the pinned environment.

Passing these criteria establishes assessment readiness and measurement hygiene.
It does not establish that the exercise alone predicts job performance; hiring
decisions require the structured human evidence in `SCORECARD.md` and other
role-relevant interview signals.
