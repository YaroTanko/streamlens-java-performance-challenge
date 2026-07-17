# Candidate Task

## Objective

You have up to 30 minutes to profile and improve StreamLens performance without
changing observable behavior. Java 21, AI assistants, JFR, other profilers, and
source-analysis tools are allowed. Correctness is mandatory.

The timer starts after a clean checkout and Java 21 are ready. It ends at 30:00
or when you record the final candidate commit SHA. Clone/toolchain setup, pushing
that SHA, opening the pull request, CI runtime, and reading the report are untimed.

## Accepted submission modes

Change at least one and no more than these two files:

- `src/main/java/com/streamlens/analyzer/Analyzer.java`
- `OPTIMIZATION.md`

The evaluator records one of three modes:

- `implementation-only`: assess the analyzer; notes are reported as not submitted.
- `notes-only`: validate the explanation; no implementation performance credit.
- `implementation-and-notes`: assess both implementation and explanation.

Everything else is protected, including tests, benchmark code, fixtures, Gradle
metadata and wrapper, scripts, documentation, and workflows. Do not move the
implementation into another source file.

## Required behavior

- Preserve `Analyzer.analyze(Reader, Config)` and all records and JSON fields.
- Preserve parsing, validation, filtering, ordering, interruption, and error
  behavior in [PRD.md](PRD.md).
- Preserve sequential IEEE-754 `double` addition in input order; do not reorder
  sums, use approximations, or skip valid input work.
- Interpret only the last occurrence of an exact required field name. Unknown
  fields remain syntax-checked but are not converted to application values.
- Do not identify, special-case, or weaken tests and benchmark workloads.
- Keep the optimization ordinary, reviewable, and achievable within the exercise.

## Candidate source policy

`Analyzer.java` may use the protected Jackson-core parser API and ordinary
`java.io` reader/error classes plus `java.math`, `java.time`, and `java.util`.
The source guard rejects filesystem, network, process, native, reflection,
management, JFR/diagnostic, class-loading, direct-output, JVM-global, and
benchmark-recognition APIs. Java Unicode escapes and static imports are also
rejected. `Thread` is allowed only as
`Thread.currentThread().isInterrupted()` for cooperative interruption.

The guard is a review aid, not a proof of benign behavior. The interviewer still
reviews the exact source. Profilers and process tools outside `Analyzer.java`
remain unrestricted; do not move them into the analyzer to evade this boundary.

## Suggested workflow

1. Run `make check`, `make benchmark`, and `make profile-jfr` (or another profiler).
2. Inspect the profile and source before selecting an optimization.
3. Edit only the permitted deliverable or deliverables.
4. Rerun correctness and the directional local benchmark.
5. If submitting notes, replace `OPTIMIZATION.md` with 5–10 concise bullets. Include
   `Profile evidence:` with the real command/tool and observed hotspot.
6. Inspect `git diff --name-only` and record the candidate commit SHA before time.

## Scoring

The authoritative evaluator runs the immutable baseline and candidate overlay on
the same Java 21 runner. It compares medians for JMH average time (`ns/op`) and
normalized allocation (`B/op`) across three scenarios, then computes a geometric
mean for each metric.

| Improvement | Reported optimization tier |
| --- | --- |
| Less than 20% | Below target |
| 20%–49.99% | Middle |
| 50%–74.99% | Senior |
| 75% or more | Staff |

At least one metric must improve by 20%. No metric geometric mean may regress by
more than 20%, and no scenario/metric pair may regress by more than 30%.
Correctness and scope/source gates remain mandatory. The tier describes this
optimization result, not the candidate's seniority or hiring outcome.

Local numbers are directional. JIT warm-up, GC, runner noise, and allocation
sampling affect individual runs; the same-run private comparison is authoritative.
