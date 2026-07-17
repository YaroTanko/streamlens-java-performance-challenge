# StreamLens Java assessment releases

> **Live-status authority.** Current activation state and exact pins are
> authoritative only in this file and the trusted assessment workflow as read
> from the upstream protected default branch. A candidate branch or immutable
> baseline tag is a snapshot and never establishes live release state.

## Current protected upstream default branch — `java-v6` active

This trusted release pins the production scorer and records the completed gates
below. The post-activation pull-request route passed for the exact candidate
recorded here, so `java-v6` is active and candidate sessions may be scored from
this protected upstream default branch.

### Immutable candidate baseline (**B**)

- Tag: `baseline-v6` (protected immutable tag rule `19117933`)
- Commit: `82f3c24c7cf344a25d6486f933c26e83dbb01122`
- Candidate diff contract: only `src/main/java/com/streamlens/analyzer/Analyzer.java`
  and `OPTIMIZATION.md`, compared as `B..candidate`.

`B` retains its intentional pre-activation `PENDING` wording. The activation
state is determined only by this file and `.github/workflows/assessment.yml` on
the protected upstream default branch; a baseline tag or candidate branch never
activates a release.

### Pinned runtime (**I**)

- Image: `ghcr.io/yarotanko/streamlens-java-assessment@sha256:359fa35b58fc7b2f1bd90ac27bb422995aebfe08305e9fdff36c6e6f5b064c48`
- Builder/canary run: `29604683998`; evidence artifact `8416442910`,
  `sha256:899882275ef89fb74dafde96aadb1e9f32a8a9bfee5bf5893fde53dff3450262`.
- The public scorer verifies the pinned digest's B6 revision, `java-v6` label,
  source repository label, and runs a real exact-image Docker canary before any
  candidate execution.

### Calibration and pre-score evidence

- Calibration archive: private evaluator release
  [`private-evaluator-v6.1`](https://github.com/YaroTanko/streamlens-java-performance-evaluator/releases/tag/private-evaluator-v6.1),
  asset SHA-256 `47cba880a844dfae4f2a2c29e2ccc2c59295042719ddaa3e87ebac39b2b52262`.
  The archive records two same-host A/A runs and a direct-B6 reference exercise;
  it contains no hidden tests.
- Private pre-score gate: evaluator tag `private-evaluator-v6.1`, evaluator
  commit `f5dc2f5fb0ddaa6afdf269fb3eb60cfb8baf7520`, run
  [`29612637533`](https://github.com/YaroTanko/streamlens-java-performance-evaluator/actions/runs/29612637533),
  candidate `878ece6bc85ae0fc67d2cc8c98910523cbf28a60`, private artifact
  `8419307640`,
  `sha256:31afa5c0a0161742467722e63bd097ea927806bb25d2c886ae1e8c904dcbc6b2`.
  It passed exact scope/blob/provenance checks and offline public plus private
  contracts; hidden test contents are intentionally not published.
- Public pre-activation scorer: trusted workflow commit
  `88cb1c3b58d6f77a2370fac8702ba48372a1ee34`, run
  [`29612717801`](https://github.com/YaroTanko/streamlens-java-performance-challenge/actions/runs/29612717801),
  candidate `878ece6bc85ae0fc67d2cc8c98910523cbf28a60`, public artifact
  `8419633147`,
  `sha256:9d8873e5ae72bfb2fd64ec2c73311be029c649bd99ca9ff85f9c24b6eb13bf98`.
  It passed every gate with aggregate `+46.96% ns/op` and `+28.06% B/op`
  (reported level: Middle).
- Public post-activation PR-route scorer: trusted upstream workflow commit
  `e38e3bad68be4d8e1976698b3b6e6b6fc05d493b`, candidate PR
  [#6](https://github.com/YaroTanko/streamlens-java-performance-challenge/pull/6),
  run
  [`29613812931`](https://github.com/YaroTanko/streamlens-java-performance-challenge/actions/runs/29613812931),
  candidate `878ece6bc85ae0fc67d2cc8c98910523cbf28a60`, public artifact
  `8419977517`,
  `sha256:ad7c46ff19d9d928639629ff075c8dcf8be96c29d71367ded91fb4b4f3fe39a7`.
  It passed every gate with aggregate `+49.14% ns/op` and `+29.14% B/op`
  (reported level: Middle).

### Post-activation pull-request route

The governing scorer evidence is the real `pull_request_target` route above,
executed after activation from the protected upstream default branch. Candidate
PR #6 is score evidence only and must never be merged into `main`; it will be
closed after this ledger update is merged.

The failed workflow run on activation PR #7,
[`29613749405`](https://github.com/YaroTanko/streamlens-java-performance-challenge/actions/runs/29613749405),
is expected and is not candidate evidence: `pull_request_target` read the
workflow from that PR's pre-activation base, whose pins were deliberately
`PENDING`. The successful run `29613812931` is the governing live-v6 evidence.

## Historical labels

`java-v5` / `baseline-v5` is an immutable, **unactivated** release attempt. Its
image workflow was rejected by GitHub before execution because `runner.temp` is
not available in job-level `env`; therefore no v5 image or scoring evidence was
produced. `java-v6` derives `$RUNNER_TEMP` inside each shell step instead and
copies release evidence into the checkout only after the runtime canary has
finished. v5 must never be used to score candidates.

`java-v4` / `baseline-v4` is an immutable, **unactivated** release attempt. Its
exact GHCR image was built and provenance-checked, but the real Docker canary
correctly stopped before functional work: release evidence had been created as
a `0700` directory inside the source checkout, which the fixed non-root runtime
UID could not copy. The same investigation found that the public assessor put
prepared source trees below a private `0700` parent, so real candidate runs
would fail for the same reason. The successor runtime path materializes only a
Git-archived synthetic source tree beneath a random owner-controlled `0755`
parent, and verifies that its contents are readable/traversable but never
group- or world-writable. v4 must never be used to score candidates.

`java-v3` / `baseline-v3` is an immutable, **unactivated** release attempt. A
clean image prewarm first exposed an incomplete JUnit BOM verification record;
the subsequent clean baseline smoke test exposed a JMH jar packaging omission:
the jar lacked required production classes. The tag remains preserved for
traceability and must never be used to score candidates. Those two fixes and a
clean-jar regression test defined `java-v4`; they required a new baseline rather
than repinning or modifying v3.

`java-v1` and `java-v2`, including any similarly named baseline tags, are also
historical labels only. They are not the current public contract and this file
makes no claim about their activation, calibration, or evidence status. In
particular, a pre-existing remote `baseline-v2` belongs to a different historical
contract and must not be used as the base for `java-v6` submissions.
