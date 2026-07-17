# Interviewer Guide

## What this exercise can establish

StreamLens is a short, structured performance-engineering work sample. When run
consistently, it can provide useful evidence about profiling discipline, contract
preservation, Java implementation judgment, verification, and the candidate's
ability to direct and review AI assistance.

It cannot, alone, establish job level, general software-engineering competence,
authorship, long-term performance, or a hiring decision. Benchmark tiers are
relative to one baseline and workload. Use this guide and `SCORECARD.md` together
with the role's other interview evidence.

## Release precondition

Do not run a scored interview while the upstream protected default branch says
**pending activation**. Read current status and pins only from that branch's
`RELEASES.md` and trusted assessment workflow, never from the candidate branch or
the immutable `baseline-v3` tag. Those snapshots intentionally retain their
historical status prose and `PENDING` runner values after a later activation
commit. Before scheduling, verify from current release evidence—not memory—that:

- `baseline-v3` exists and its tag resolves to the workflow's full pinned SHA;
- the workflow pins an immutable container digest;
- the real-runtime canary passed using that exact digest;
- retained calibration evidence and the required private-evaluator pre-score
  review are available for `java-v3`;
- the Java 21 patch version and documented commands match the release;
- the branch is clean and contains no reference solution; and
- a recent maintainer smoke run produced complete evidence artifacts.

If any item is missing, use the repository only for a dry run and do not report a
candidate tier.

## Before the timed session

1. Record candidate, interviewer, assessment version, baseline SHA, start branch,
   and environment in the scorecard. Confirm that the candidate branch starts from
   the immutable `baseline-v3` snapshot (**B**), and that it has not merged,
   rebased, or updated from the upstream default branch. Its `B..candidate` diff
   must contain only `Analyzer.java` and `OPTIMIZATION.md`.
2. Provide a clean checkout. Confirm `java -version` and `make check` can start.
   Tool installation and clone time are not candidate time.
3. Explain the boundary exactly: 30 minutes; AI and profilers allowed; only
   `Analyzer.java` and `OPTIMIZATION.md` may change; correctness is mandatory.
4. Explain that the candidate must record the final commit SHA by 30:00. Push,
   PR creation, CI, and debrief happen afterward.
5. Ask the candidate to think aloud enough that you can follow hypotheses and
   decisions. Do not require disclosure of private AI account history or unrelated
   prompts. They may use a fresh session.
6. Do not provide a known bottleneck, reference patch, target data structure, or
   expected profile result.

Record whether starting checks passed. If repository infrastructure is broken,
stop or restart the session; do not charge repair time to the candidate and do not
silently change the contract.

## During the 30 minutes

Use observation, not coaching. Useful factual notes include:

- which profile/benchmark was run and what the candidate read from it;
- the hypothesis formed before editing;
- constraints the candidate identified without prompting;
- how an AI suggestion was constrained, checked, revised, or rejected;
- whether checks were targeted and then broadened;
- how the candidate handled a failure or changed hypothesis; and
- what was explicitly left as a trade-off due to time.

AI use is not a negative signal. The relevant signal is whether the candidate
drives the tool with the contract and evidence, understands the resulting code,
and verifies it. A candidate may work mostly alone or heavily with AI and still
produce equivalent strong evidence.

Do not infer understanding from typing speed, amount of code, familiarity with a
particular profiler UI, or whether the solution resembles another submission.
Public exercises and common optimizations converge. Resolve uncertainty through
technical follow-up, not accusation.

### Permitted interviewer interventions

- Clarify logistics, remaining time, file scope, or wording already in the docs.
- Fix or exclude verified environment failure and record it.
- Remind the candidate once that they must record a SHA and complete
  `OPTIMIZATION.md`.

Do not interpret a PRD rule for the candidate, point at the hot function, suggest
an algorithm, debug their patch, or approve a source-policy workaround.

## At 30:00

1. Stop implementation and note editing.
2. Ask the candidate to commit if they have not already; do not permit new content
   after time. Record the exact SHA and time. If the worktree is uncommitted at
   30:00, record that fact under process deviation rather than extending time.
3. Capture `git status --short` and the complete diff/stat for the submitted SHA.
4. Push/open the PR after the timer. Confirm PR head equals the recorded SHA.
5. Approve fork Actions only after a quick scope/source sanity review.

An infrastructure rerun uses the exact same SHA. A candidate code or note change
requires a new, explicitly non-comparable session; it is not the original score.

## CI evidence review

Review in this order:

1. exact candidate and baseline revisions;
2. protected two-file scope and regular-file checks;
3. safe-JDK source-policy result;
4. functional/contract result;
5. `OPTIMIZATION.md` format and plausibility;
6. per-scenario baseline/candidate `ns/op` and `B/op`;
7. geometric means and both regression guards;
8. independent JFR summaries; and
9. artifact manifest hashes, completeness, and infrastructure errors.

Never skip failed correctness because performance is high. Never average a severe
scenario regression away. Treat infrastructure/comparator errors as no result,
not candidate failure.

If a geometric mean is within two percentage points of 20%, 50%, or 75%, rerun
the same SHA once. Retain both runs and use the lower tier or fail outcome if they
differ.

## Required debrief (10–15 minutes, untimed)

The debrief is essential, especially when AI was used or a public solution may be
known. Ask the candidate to navigate their exact diff and answer in their own
words. Use at least the first four prompts plus two diff-specific prompts.

1. **Evidence chain:** “Show the profile evidence that selected this change. What
   alternative hotspot or change did you reject?”
2. **Contract:** “Name three behaviors your optimization could easily have broken
   and show how the code preserves them.”
3. **Counterfactual:** “If the workload had ten times more distinct users per
   group / mostly filtered events / very long ignored JSON, how would your change
   behave and what would you measure next?”
4. **AI ownership:** “What did the AI propose, if anything? Which assumption did
   you verify or change, and how would you detect a subtly wrong suggestion?”
5. **Duplicates/ignored fields:** “Why can we not eagerly convert every occurrence
   or every number while parsing?”
6. **Floating point:** “Why is a parallel reduction or reordered top-K
   accumulation not contract-equivalent? How are signed-zero ties ordered?”
7. **Time/text:** “Why is the window anchor year one rather than the Unix epoch?
   What ordering does Java `String.compareTo` provide, and why not locale
   sorting?”
8. **Interruption:** “Where is interruption observed, and why can a blocked
   arbitrary `InputStream` remain blocked?”
9. **Measurement:** “What does JMH `B/op` tell you that sampled JFR allocation
   events do not, and what noise remains?”
10. **Trade-off:** “What got more complex or could regress? What production
    telemetry or test would you add outside this protected exercise?”

A memorized or AI-generated patch can still yield valid evidence if the candidate
understands, evaluates, and owns it. Conversely, a green/high-tier patch with
inability to explain its data flow, invariants, or measurement is weak evidence.

## Signals when AI is used well

- The candidate gives the tool a precise scope and relevant contract instead of
  asking only for “maximum speed.”
- They request or inspect evidence before accepting an optimization.
- They notice unsafe API use, sum reordering, duplicate-field errors, or benchmark
  overfitting in a suggestion.
- They can explain every material line and adapt the idea to a counterfactual.
- They distinguish generated confidence from measured facts.
- They use tests/profiles to falsify a suggestion and revise course when needed.

These signals measure engineering control of AI, not prompt style or vendor.

## Red flags requiring evidence, not assumptions

- A claimed profile or percentage has no matching command/output and cannot be
  explained.
- The implementation recognizes workload names/data, weakens validation, skips
  valid input, or reaches forbidden external/process APIs.
- The candidate treats passing tests as proof that all PRD behavior is preserved.
- They cannot explain complexity, key invariants, a changed data structure, or a
  comparator in their own diff after focused prompts.
- `OPTIMIZATION.md` contradicts the code or confuses JFR samples with exact counts.
- A narrow benchmark win hides a material regression the candidate does not
  acknowledge.
- They describe the numeric “Staff” label as proof of their seniority.

One stumble, language difference, or nervous answer is not decisive. Record the
specific evidence, ask one clarifying follow-up, and score consistently.

## Interpretation and decision

Complete `SCORECARD.md` before discussing a hiring recommendation. Keep three
facts separate:

1. **Assessment gates:** scope, policy, correctness, response, performance.
2. **Numeric optimization tier:** relative time/allocation outcome for this SHA.
3. **Human evidence quality:** profiling, reasoning, implementation judgment,
   verification, communication/ownership, and trade-offs.

A strong positive work-sample signal requires all gates plus strong human evidence
and successful debrief. A numeric tier alone is insufficient. A candidate who
misses the 20% gate but demonstrates sound evidence may still contribute useful
interview information, but must be recorded as not meeting this assessment's
performance target rather than quietly passed.

## Maintainer calibration

Before activation and periodically afterward:

- run repeated baseline/reference comparisons on the pinned environment;
- retain noise distributions, per-scenario results, and reference diffs;
- dry-run with at least two representative Java engineers using the exact timer;
- compare independent interviewer ratings and refine ambiguous anchors;
- rotate to a new version if known solutions overwhelm diagnostic value; and
- audit adverse impact and accessibility concerns rather than assuming the task is
  universally fair.

The rubric is structured for consistency, but it is not a published predictive-
validity study. Do not make stronger claims than the retained calibration and
candidate evidence support.
