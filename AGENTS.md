# Instructions for AI Coding Agents

This is a timed Java 21 performance assessment. `PRD.md` is authoritative;
`TASK.md` defines candidate scope; `DESIGN.md` records architecture and invariants.
AI use is explicitly allowed.

A candidate submission may edit one or both of exactly:

- `src/main/java/com/streamlens/analyzer/Analyzer.java`
- `OPTIMIZATION.md`

Do not edit tests, benchmarks, build files, scripts, docs, workflows, or add source
files for a candidate solution. Preserve all parsing, validation, filtering,
ordering, interruption, error, and sequential `double` sum behavior. Never detect
tests/workloads, approximate results, or claim unmeasured gains.

Inspect/profile before optimizing. Run `make check`, `make benchmark`, and
`make profile-jfr` or another profiler. Changed notes require 5–10 bullets with a
truthful non-empty `Profile evidence:` bullet. Respect the active source subset in
`TASK.md` and keep implementation changes reviewable within 30 minutes.
