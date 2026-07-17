# StreamLens Java Performance Challenge

StreamLens is a Java 21 CLI and in-repository analyzer that reads strict UTF-8
NDJSON, filters events, groups them into fixed UTC-aligned windows, and produces
deterministic aggregate JSON. The repository is also a 30-minute, AI-assisted
performance exercise: preserve the contract, improve the analyzer, and let CI
compare the result with an immutable baseline.

> **Live release authority:** read activation state and pins only from
> `RELEASES.md` and the trusted assessment workflow on the upstream protected
> default branch. The immutable `baseline-v5` tag and every candidate branch are
> snapshots, not live release records: their status prose and intentionally
> `PENDING` runner values are historical and non-authoritative after activation.
>
> Before scoring, verify in that upstream record that the immutable `baseline-v5`
> and image pins, exact-image Docker canary, calibration, and private-evaluator
> pre-score gate are complete. `PRD.md` contains the complete gate.

`PRD.md` is the product and assessment source of truth. Candidates read
`TASK.md` before changing code; interviewers use `INTERVIEWER_GUIDE.md` and
`SCORECARD.md` rather than treating a benchmark tier as a hiring decision.

## Requirements

- Java 21
- the checked-in Gradle Wrapper
- GNU Make for the short commands (Gradle tasks remain available underneath)
- Docker only for maintainer-side isolated assessment/release checks

The production analyzer uses only the Java 21 standard library. JUnit, JMH, and
JFR integration belong to protected assessment infrastructure.

## Quick start

```sh
make check
make benchmark
make profile-cpu
make profile-alloc
```

`make check` builds and runs protected correctness/policy tests. `make benchmark`
runs the deterministic local JMH scenarios. Profile targets create diagnostic JFR
recordings and readable summaries; they do not contribute to the score.

Run the CLI against an NDJSON file:

```sh
./gradlew run --args="--input examples/events.ndjson \
  --from 2026-01-15T12:00:00Z \
  --to 2026-01-15T13:00:00Z \
  --types purchase,click \
  --window 1m \
  --top-k 3"
```

Omit `--input` to read standard input. Run `./gradlew run --args='--help'` for
the complete CLI help. `--window` accepts ISO-8601 duration text or Go-like
nanosecond-exact unit sequences such as `1m30s`. Canonical example input and
output live under `examples/`.

## Candidate challenge

Use any AI assistant and any local profiling/analysis tools. Change exactly:

- `src/main/java/com/streamlens/analyzer/Analyzer.java`
- `OPTIMIZATION.md`

Keep all observable behavior unchanged. Replace the note template with 5–10
truthful bullets, including the measured profiler/tool and observed hotspot. Open
a pull request containing the commit SHA recorded before the 30-minute timer
ended. Start from `baseline-v5` and do not merge, rebase, or otherwise update the
candidate branch from the upstream default branch; that branch later carries
mutable activation metadata. Scope validation compares `baseline-v5..candidate`
and permits only these two paths.

CI first checks scope, the safe-JDK source policy, and correctness. It then builds
a fresh immutable-baseline tree with only those two candidate overlays and runs
fixed trusted commands in a restricted, digest-pinned container. Each assessment
creates a randomized, trusted public corpus and authenticates its expected results
with the immutable baseline before measurement. The comparative report scores
`ns/op` and `B/op` across Balanced, HighCardinality, and MostlyFiltered. This
public evidence is necessary but not sufficient: the separately maintained private
evaluator and human debrief are pre-score gates. See `TASK.md` for thresholds and
regression guards.

Local benchmarks are directional. A reported Middle, Senior, or Staff
optimization tier labels only this measured change against this baseline; it is
not a Java level or hiring outcome.

## Maintainer activation boundary

The protected upstream default branch becomes an active `java-v5` release only
after evidence proves the full gate in `PRD.md`, including:

- clean Java 21 build and contract tests;
- calibrated baseline and attainable reference tiers;
- full immutable baseline SHA and image digest pins;
- exact two-file candidate overlay;
- restricted no-network execution with read-only baseline mount, private tmpfs,
  and resource/output/deadline bounds;
- real exact-image canaries and validated container-ID cleanup; and
- deterministic evidence manifests; and
- completed calibration plus a pinned, reviewed private-evaluator result.

Development tests or a mock runner do not justify changing the status banner to
active. Activation is a release decision over one pinned evidence set. The
immutable candidate baseline remains unchanged: a later protected activation
commit records the live status and pins on the default branch rather than editing
`baseline-v5`.

## Documentation

- `PRD.md` — authoritative product and assessment contract
- `TASK.md` — candidate scope, workflow, and scoring
- `DESIGN.md` — architecture and invariants
- `PROFILING.md` — measured profiling workflow
- `AGENTS.md` — instructions for AI coding agents
- `INTERVIEWER_GUIDE.md` — setup, timing, observation, and review
- `SCORECARD.md` — structured behavioral rubric and decision limits
- `CONTRIBUTING.md` — fork/branch/pull-request workflow
- `OPTIMIZATION.md` — candidate response template
