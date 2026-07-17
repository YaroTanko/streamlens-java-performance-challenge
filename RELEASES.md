# StreamLens Java assessment releases

> **Live-status authority.** Current activation state and exact pins are
> authoritative only in this file and the trusted assessment workflow as read
> from the upstream protected default branch. A candidate branch or immutable
> baseline tag is a snapshot and never establishes live release state.

## Current protected upstream default branch — `java-v5` pending activation

At this protected-default-branch revision, `java-v5` is not active and must not
be used to score candidates until the full release gate in `PRD.md` is met: an
immutable `baseline-v5` SHA, exact image digest, real Docker-runtime canary,
calibration evidence, and a reviewed private-evaluator pre-score gate.

### Immutable baseline snapshot

`baseline-v5` is the immutable candidate starting snapshot (**B**). It captures
the pre-activation text and the runner's `PENDING` values by design. A later
protected activation commit (**A**) updates this default-branch record and the
trusted workflow with the live pins; it does not alter B. Therefore, when this
file is read from the `baseline-v5` tag, its pending wording is historical and
non-authoritative. Candidate branches must remain based on B and must not merge,
rebase, or update from the upstream default branch; the submission scope is
checked as `B..candidate` and may contain only `Analyzer.java` and
`OPTIMIZATION.md`.

## Historical labels

`java-v4` / `baseline-v4` is an immutable, **unactivated** release attempt. Its
exact GHCR image was built and provenance-checked, but the real Docker canary
correctly stopped before functional work: release evidence had been created as
a `0700` directory inside the source checkout, which the fixed non-root runtime
UID could not copy. The same investigation found that the public assessor put
prepared source trees below a private `0700` parent, so real candidate runs
would fail for the same reason. `java-v5` keeps release evidence outside the
checkout, materializes only a Git-archived synthetic source tree beneath a
random owner-controlled `0755` runtime parent, and verifies that its contents
are readable/traversable but never group- or world-writable. v4 must never be
used to score candidates.

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
contract and must not be used as the base for `java-v5` submissions.
