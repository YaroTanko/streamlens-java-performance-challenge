# Instructions for AI Coding Agents

This repository is a timed Java performance assessment. Help the candidate
analyze and improve the supplied implementation while preserving the contract.
AI use is explicitly allowed. Never conceal uncertainty or claim a benchmark or
profile result that was not measured.

## Authority and release status

1. `PRD.md` is the product and assessment source of truth.
2. `TASK.md` defines candidate scope and scoring.
3. `DESIGN.md` summarizes architecture and invariants.

Read current `java-v6` activation status and pins only from `RELEASES.md` and the
trusted assessment workflow on the upstream protected default branch. The
immutable `baseline-v6` tag and candidate checkouts are snapshots: their
pre-activation prose and intentionally `PENDING` runner values are historical and
non-authoritative after activation. A live release may be called active only when
maintainers publish the baseline/image pins, pass the documented real
Docker-runtime canary, complete calibration, and review the private-evaluator
pre-score gate. Do not infer live status from a baseline tag.

## Candidate scope

For a candidate pull request, edit exactly:

- `src/main/java/com/streamlens/analyzer/Analyzer.java`
- `OPTIMIZATION.md`

Do not edit or generate tests, fixtures, benchmark code, scripts, build files,
other documentation, workflows, or additional implementation files. If a
requested change needs a protected path, explain the conflict instead of making
the change.

Start from the immutable `baseline-v6` snapshot and do not merge, rebase, or
update the candidate branch from the upstream default branch. That branch may
contain later protected activation metadata; the scope guard compares
`baseline-v6..candidate` and rejects every path other than the two above.

## Candidate source policy

Keep `Analyzer.java` within the safe JDK subset documented in `TASK.md` and
`PRD.md`. In particular, do not add filesystem, network, subprocess, environment,
native, reflective, class-loading, logging, JVM-management, shutdown, profiling,
or test-hook behavior to the measured analyzer. Do not write to standard streams,
change process-wide state, start threads, invoke garbage collection, terminate
the JVM, or detect protected tests and benchmark workloads.

The source audit is a workflow aid, not a complete Java sandbox or proof of benign
behavior. Keep every change ordinary, small, and directly reviewable within the
30-minute exercise. Profiling and analysis tools used outside `Analyzer.java`
remain unrestricted.

## Working rules

- Inspect and profile before selecting an optimization. Use the provided Gradle
  targets or another profiler.
- Preserve parsing, validation, filtering, aggregation, ordering, error, and
  interruption behavior.
- Do not convert ignored fields. For exact duplicate field names, interpret only
  the last value while still requiring the whole JSON object to be syntactically
  valid.
- Preserve input-order Java `double` addition and fail on infinite aggregate
  results. Do not reorder additions or use approximate arithmetic.
- Preserve strict UTF-8 handling, timestamp semantics, UTF-16 `String.compareTo`
  ordering, the in-repository API, and deterministic CLI JSON.
- Use only the Java 21 standard library in production analyzer code.
- Never weaken, bypass, special-case, or recognize tests or benchmark inputs.
- Never skip valid input work or replace exact results with approximations.
- Record measured or expected effects and trade-offs in `OPTIMIZATION.md`.
- Include a non-empty `Profile evidence:` bullet naming the actual command or
  tool and an observed hotspot.

## Verification

Run the commands documented by the repository, normally:

```sh
make check
make benchmark
# Run at least one profiling target, or another measured profiler:
make profile-cpu
make profile-alloc
```

Treat local benchmark changes as directional; the same-run baseline comparison in
CI is authoritative. Before finishing, inspect the complete branch diff, confirm
that only the two allowed files changed, and confirm that `OPTIMIZATION.md`
contains 5–10 concise bullets covering profile evidence, approach, expected
effect, trade-offs, and verification.
