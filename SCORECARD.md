# StreamLens Java Interview Scorecard

Use one copy per candidate. Record observable evidence and exact artifacts, not
impressions such as “seemed senior.” Complete the scorecard before combining this
exercise with other hiring evidence.

## Session record

- Candidate:
- Interviewer:
- Date/time and time zone:
- Assessment version:
- Baseline full SHA:
- Candidate branch confirmed to descend from `baseline-v4`:
- Candidate recorded SHA:
- Pinned Java version:
- Pinned container digest:
- CI run/artifact link:
- AI/tooling used (candidate-described; no vendor preference):
- Timer start / final SHA time:
- Environment or process deviations:

## Activation validity

- [ ] Live status was **Active**, not Pending, on the upstream protected default
  branch at session start (not inferred from the baseline tag or candidate branch).
- [ ] `baseline-v4` tag, trusted upstream-workflow full SHA pin, and image digest
  agreed.
- [ ] Exact-image real-runtime canary evidence was available.
- [ ] Calibration evidence and the required private-evaluator pre-score review
  were available for this assessment version.
- [ ] Starting checkout was clean and checks could run.

If any item is unchecked, label the session **dry run / no valid score**. Do not
infer a candidate tier from development infrastructure.

## Hard gates

| Gate | Evidence | Result |
| --- | --- | --- |
| Submitted PR head equals SHA recorded by 30:00 | SHA/time/PR head | Pass / Fail / Infra |
| Diff contains exactly `Analyzer.java` and `OPTIMIZATION.md` | scope artifact | Pass / Fail / Infra |
| Safe-JDK typed source audit | guard artifact | Pass / Fail / Infra |
| Full functional/contract suite | test artifact | Pass / Fail / Infra |
| `OPTIMIZATION.md` has 5–10 truthful-format bullets | committed file + review | Pass / Fail |
| Performance: ≥20% in one metric, aggregate/scenario guards hold | comparison report | Pass / Fail / Infra |
| Evidence artifacts and manifests complete | artifact inventory/hash result | Pass / Fail / Infra |

An infrastructure result is no candidate result. A correctness or integrity
failure cannot be offset by benchmark speed. Record suspected deliberate
benchmark recognition separately and escalate for review; do not speculate about
intent from similarity alone.

## Numeric performance evidence

| Metric | Baseline geometric mean | Candidate geometric mean | Improvement | Tier |
| --- | ---: | ---: | ---: | --- |
| `ns/op` |  |  |  | Below / Middle / Senior / Staff |
| `B/op` |  |  |  | Below / Middle / Senior / Staff |

- Overall numeric tier:
- Worst scenario/metric regression:
- Aggregate regression guard:
- Within two points of a boundary? If yes, rerun link and lower outcome used:
- Candidate's measured local result (if any, explicitly directional):
- CI profile summary consistent with claimed call path? Evidence:

### What the numeric tier supports

It supports only this statement: on the pinned workload/environment and submitted
SHA, the reported metric changed by the stated relative amount against the pinned
baseline, subject to measured run noise and the regression guards.

It does **not** establish:

- Middle/Senior/Staff job level;
- performance on production distributions, hardware, JDKs, or GC configurations;
- authorship or whether AI was used;
- understanding of the patch;
- correctness outside the tested contract;
- maintainability, security, or broader engineering judgment; or
- exact allocation object count (`B/op` is allocation volume; JFR events are
  sampled diagnostics).

A 75% result may exploit one large baseline cost. A 20% result may reflect an
excellent, low-risk diagnosis. Interpret magnitude with scenario breadth,
complexity, regressions, and the behavioral evidence below.

## Behavioral ratings

For each dimension select one 0–3 anchor and cite concrete evidence from commands,
diff lines, notes, or debrief answers.

### 1. Contract comprehension — critical

- **0 — Contradictory:** Cannot state key behavior or proposes/defends an invalid
  shortcut such as skipped validation, reordered sums, or locale ordering.
- **1 — Partial:** Names obvious output behavior but misses important edge cases
  in the exact changed path even after one clarification.
- **2 — Sound:** Correctly explains the affected parsing/filtering/aggregation
  invariants and shows how the patch preserves them.
- **3 — Deep:** Proactively identifies non-obvious boundaries (duplicates/ignored
  conversion, earliest error, signed zero, year-one window anchoring, strict UTF-8, or
  interruption) relevant to the design and reasons through a counterexample.

Score: 0 / 1 / 2 / 3

Evidence:

### 2. Profiling discipline and evidence — critical

- **0 — Fabricated/absent:** Claims a profile or gain that was not run, or cannot
  connect any observation to an artifact/tool.
- **1 — Weak:** Runs a tool but selects a change from intuition without correctly
  interpreting the result.
- **2 — Sound:** Names the actual profiler/command, identifies a relevant hotspot,
  and connects it to the chosen change without overstating precision.
- **3 — Strong:** Validates call path/workload, distinguishes JFR sampling from
  JMH measurement, considers an alternative hypothesis, and uses before/after
  evidence appropriately.

Score: 0 / 1 / 2 / 3

Evidence:

### 3. Optimization reasoning and complexity

- **0 — No rationale:** Cannot explain why the change should affect cost.
- **1 — Superficial:** Gives a generic “fewer objects/faster” rationale without
  input-shape or complexity reasoning.
- **2 — Sound:** Explains the affected operation/allocation, expected workload
  benefit, and why the implementation preserves semantics.
- **3 — Strong:** Compares viable alternatives, reasons across all three scenario
  shapes, and predicts limits or crossover points that match evidence.

Score: 0 / 1 / 2 / 3

Evidence:

### 4. Implementation quality — critical

- **0 — Unsafe/unreviewable:** Uses prohibited effects, benchmark recognition,
  approximation, or code the candidate cannot explain.
- **1 — Fragile:** Works on obvious cases but adds unjustified complexity,
  duplication, or hidden assumptions in the changed path.
- **2 — Sound:** Focused, readable, JDK-only change with appropriate data
  structures and directly reviewable control flow.
- **3 — Strong:** Achieves meaningful improvement with disciplined scope, clear
  invariants, careful edge handling, and a favorable complexity/risk trade-off.

Score: 0 / 1 / 2 / 3

Evidence:

### 5. Verification and measurement — critical

- **0 — Disregards failures:** Does not run relevant checks or dismisses a
  correctness/regression failure.
- **1 — Narrow:** Relies on one test/benchmark number and treats green tests as
  complete proof.
- **2 — Sound:** Runs correctness plus relevant before/after measurement, inspects
  the diff, and distinguishes local direction from authoritative CI.
- **3 — Strong:** Uses tests/profile/benchmark for different purposes, investigates
  scenario regressions/noise, and describes an additional falsifying test or
  production measurement.

Score: 0 / 1 / 2 / 3

Evidence:

### 6. Communication, ownership, and AI control — critical

- **0 — No demonstrated ownership:** Cannot explain material lines or reasoning
  in the submitted patch after focused questions.
- **1 — Limited:** Repeats generated/general claims but struggles to test an
  assumption or adapt the solution to a small counterfactual.
- **2 — Sound:** Explains the exact code and evidence in their own words, states
  uncertainty, and verifies AI suggestions when used.
- **3 — Strong:** Directs AI with constraints/evidence, catches or corrects a bad
  assumption, can adapt the idea, and communicates concise causal reasoning
  regardless of how much AI authored mechanically.

Score: 0 / 1 / 2 / 3

Evidence:

### 7. Trade-offs and generalization

- **0 — Denies trade-offs:** Claims universal improvement or cannot identify a
  risk/limit.
- **1 — Generic:** Names only vague readability/memory trade-offs.
- **2 — Sound:** Identifies a concrete complexity, memory, or workload trade-off
  and proposes an appropriate next measurement.
- **3 — Strong:** Relates trade-offs to scenario distributions, JVM behavior, and
  production observability/rollback without claiming assessment numbers transfer
  directly.

Score: 0 / 1 / 2 / 3

Evidence:

## Debrief evidence

Record concise answers, not a transcript:

- Profile → hypothesis → change chain:
- Three contract risks and how preserved:
- Counterfactual workload answer:
- AI suggestion checked/rejected/changed (or independent reasoning path):
- Diff-specific question 1 and answer:
- Diff-specific question 2 and answer:
- Candidate-stated uncertainty/trade-off:

## Evidence-quality band

Behavioral total (0–21):

Use these provisional consistency bands only after all hard gates pass:

- **Strong positive work-sample signal:** 17–21, every critical dimension ≥2,
  and debrief answers are consistent with the exact diff/evidence.
- **Positive but bounded signal:** 13–16, every critical dimension ≥2; combine
  with other role evidence and note the specific limits.
- **Mixed signal:** 9–12, any critical dimension at 1, or meaningful contradiction
  between measured evidence, notes, and explanation.
- **Insufficient/negative exercise signal:** 0–8, a critical dimension at 0, or a
  candidate-caused correctness/integrity failure.

These bands are not psychometric norms and must not be mapped to job titles.
Maintain calibration data and reviewer agreement; revise only in a new documented
rubric version if anchors prove unreliable.

Selected band:

Evidence supporting band:

Evidence limiting confidence:

## Final recording

- Assessment result: Met target / Did not meet target / No valid result
- Numeric optimization tier (if valid):
- Human evidence-quality band:
- Strongest observed signal:
- Most important risk/gap:
- Follow-up evidence needed from other interviews:
- Role recommendation (made from the full interview loop, not this task alone):
- Interviewer confidence and why:

Do not write “candidate is Senior/Staff because CI says Senior/Staff.” A defensible
conclusion names the exact behavior observed, the limits of the workload, and how
this evidence combines with the rest of the hiring process.
