# Candidate Task

> **Release-state authority:** Only `RELEASES.md` and the trusted assessment
> workflow on the upstream protected default branch establish current activation
> status and pins. The immutable `baseline-v6` tag and a candidate checkout are
> snapshots; their release wording and intentionally `PENDING` runner values are
> historical and non-authoritative after activation. Do not start or score a
> session until the live release record identifies the baseline/image pins,
> exact-image Docker canary, calibration evidence, and private-evaluator
> pre-score review.

## Objective and timebox

You have up to 30 minutes to profile and improve StreamLens performance without
changing observable behavior. You may use any AI assistant, profiler, IDE,
editor, or local analysis tool. Correctness and honest reporting are mandatory.

The interviewer starts the timer only after you have a clean checkout and can run
the Java 21 project. The timer stops at 30:00 or when you record your final local
commit SHA, whichever comes first. Clone/JDK setup, pushing that SHA, opening the
pull request, CI queue/runtime, and reading the report are untimed. Do not alter
the submitted implementation or notes after recording the SHA.

For a release the interviewer has verified as active from the upstream default
branch, start from the immutable `baseline-v6` commit identified there. Do not
merge, rebase, or otherwise update the candidate branch from the upstream default
branch after starting it. CI checks `baseline-v6..candidate`, so activation
metadata and any other upstream change make the submission out of scope.

## Allowed changes

Your complete pull request may modify exactly:

- `src/main/java/com/streamlens/analyzer/Analyzer.java`
- `OPTIMIZATION.md`

Everything else is protected: tests, fixtures, benchmarks, scripts, build files,
other sources, documentation, generated files, module metadata, and workflows.
Do not add a file or move analyzer logic into another file. CI rejects a wider
diff.

## Required behavior

- Preserve `Analyzer.analyze(InputStream, AnalyzerConfig)` and its checked error
  and interruption behavior.
- Preserve strict UTF-8 and JSON parsing, validation, filtering, deterministic
  ordering, line-numbered errors, and earliest-line precedence from `PRD.md`.
- Syntax-check but do not application-convert ignored fields; compare names after
  JSON string decoding and interpret only the final value of a duplicate.
- Preserve exact counts/membership and input-order Java `double` sums. Do not
  reorder additions, approximate results, or silently accept infinity.
- Preserve year-one-anchored nanosecond time windows, UTC timestamp output, Java
  `String.compareTo` ordering, and deterministic CLI JSON.
- Keep production analyzer code Java 21 JDK-only.
- Never hard-code, recognize, or special-case protected scenarios or fixtures.

## Safe-JDK source policy

`Analyzer.java` may use only the explicit safe Java 21 types accepted by the
repository source guard for input parsing, character decoding, time arithmetic,
collections, and ordinary language operations. The guard parses and type-checks
the exact committed source.

Do not use filesystem/output, network/IPC, subprocess, reflection/method-handle,
dynamic class-loading, native, instrumentation, management, JFR-control, logging,
serialization-hook, test-framework, environment/system-property, JVM shutdown,
garbage-collection, process-wide runtime, or thread/executor APIs. The only
`Thread` access needed by the analyzer is reading the current thread's interrupt
status without clearing it; do not create, start, stop, sleep, or mutate threads.
Do not write to standard streams or add source strings/markers that identify the
protected tests, benchmarks, flags, packages, or fixture content.

The policy applies only to submitted `Analyzer.java`; it does not restrict your
external profiler, debugger, IDE, shell, or AI assistant. Passing the audit is not
proof of harmlessness. Keep the code straightforward enough for the interviewer
to understand from the diff.

Keep the file valid UTF-8 and under 256 KiB, retain its package and sole public
top-level `Analyzer` class, and put any helpers inside that class. Static/wildcard
imports, native methods, raw Java Unicode-escape spellings (`backslash-u`), and
annotations other than `@Override`/`@SuppressWarnings` are rejected. The supplied
baseline already follows these mechanical rules.

## Suggested workflow

1. Read `README.md`, this file, and the relevant invariants in `PRD.md` and
   `DESIGN.md`. Give `AGENTS.md` to your AI assistant.
2. Establish a starting point:

   ```sh
   make check
   make benchmark
   make profile-cpu    # or make profile-alloc / another measured profiler
   ```

3. Inspect the observed hotspot and choose one or more reviewable improvements.
4. Edit `Analyzer.java`, then rerun correctness and the relevant measurements.
5. Replace `OPTIMIZATION.md` with 5–10 concise bullet lines. Include a non-empty
   `Profile evidence:` bullet naming the command/tool and hotspot you actually
   observed, plus the change, effects, correctness, trade-off, and verification.
6. Inspect the complete diff, commit both deliverables, and record the commit SHA
   before the timer ends.
7. After the timer, push that exact SHA, open the pull request, and inspect CI.

## Scoring

CI constructs a fresh tree from the immutable baseline and overlays only your two
deliverables. It compares baseline and candidate in separate equivalently warmed
JVM forks on the same runner for Balanced, HighCardinality, and MostlyFiltered.

`java-v6` scores two geometric-mean metrics:

- execution time (`ns/op`); and
- normalized allocation volume (`B/op`).

| Improvement in one metric | Reported optimization tier |
| --- | --- |
| less than 20% | Below target |
| 20%–49.99% | Middle |
| 50%–74.99% | Senior |
| 75% or more | Staff |

The overall numeric tier is the higher of the two. Passing performance requires
at least 20% improvement in one metric, no more than 20% geometric-mean regression
in the other, and no more than 30% regression in any scenario/metric pair.
Correctness, scope, source-policy, or response-format failure always fails first.

Java allocation-event/object-count profiles are diagnostic in `java-v6`, not a
third score, because they have not been established as equally reproducible
`objects/op` measurements. CPU and allocation profiles are collected separately
from scored samples.

Local numbers are directional; CI is authoritative. If an aggregate is within
two points of a tier boundary, the interviewer may rerun the exact same SHA once
and use the lower inconsistent result. Numeric tiers label this optimization
only. They are not your job level and cannot replace human review.

For a release verified as active from the upstream default branch, CI creates a
fresh randomized corpus for the assessment, uses the immutable baseline as the
trusted oracle for complete-result verification, and then measures the candidate
against the same corpus. The public corpus and its evidence do not replace the
separate private evaluator or the interviewer debrief; both are pre-score gates
maintained outside candidate scope.

## Deliverables

- A contract-preserving implementation change in
  `src/main/java/com/streamlens/analyzer/Analyzer.java`.
- A truthful 5–10 bullet `OPTIMIZATION.md`.
- A pull request whose submitted head contains the SHA recorded before 30:00.
