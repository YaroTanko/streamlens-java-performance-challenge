# StreamLens Java Performance Challenge

StreamLens reads newline-delimited JSON events, filters them, groups them into
fixed UTC windows, and returns deterministic aggregates. This repository is a
30-minute, AI-assisted Java 21 performance exercise: the supplied implementation
is correct but leaves several realistic CPU and allocation improvements open.

## Candidate quick start

Prerequisites: a Java 21 JDK. The checked-in Gradle wrapper downloads the pinned
Gradle distribution and the protected Jackson, JUnit, and JMH dependencies.

```bash
make check
make benchmark
make profile-jfr
```

Read [TASK.md](TASK.md) before editing. A submission may change the implementation,
the notes, or both, but no other path:

- `src/main/java/com/streamlens/analyzer/Analyzer.java`
- `OPTIMIZATION.md`

The three accepted modes are `implementation-only`, `notes-only`, and
`implementation-and-notes`. A notes-only submission cannot receive implementation
performance credit. If notes are submitted, replace the template with 5–10 bullets
including a truthful, non-empty `Profile evidence:` bullet.

Run the example application with:

```bash
./gradlew run --args='--input examples/events.ndjson'
```

## Assessment model

The public repository provides local correctness tests, JMH workloads, JFR
profiling, and a read-only pull-request workflow. The authoritative interviewer
evaluation runs separately in a private evaluator. It constructs a fresh tree
from the immutable `baseline-v1` runtime and overlays only the two candidate
deliverables. Candidate tests, build files, scripts, and workflows are neither
copied nor executed.

JMH reports average time (`ns/op`) and normalized allocation (`B/op`) for balanced,
high-cardinality, and mostly-filtered workloads. The private evaluator alternates
baseline and candidate samples on the same runner and retains the JSON evidence.
See [PRD.md](PRD.md) for the full contract and [DESIGN.md](DESIGN.md) for component
boundaries.

AI assistants and profiling tools are explicitly allowed. Do not claim benchmark
or profile results that you did not observe.
