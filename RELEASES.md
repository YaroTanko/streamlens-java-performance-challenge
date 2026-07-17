# StreamLens Java assessment releases

> **Live-status authority.** Current activation state and exact pins are
> authoritative only in this file and the trusted assessment workflow as read
> from the upstream protected default branch. A candidate branch or immutable
> baseline tag is a snapshot and never establishes live release state.

## Current protected upstream default branch — `java-v3` pending activation

At this protected-default-branch revision, `java-v3` is not active and must not
be used to score candidates until the full release gate in `PRD.md` is met: an
immutable `baseline-v3` SHA, exact image digest, real Docker-runtime canary,
calibration evidence, and a reviewed private-evaluator pre-score gate.

### Immutable baseline snapshot

`baseline-v3` is the immutable candidate starting snapshot (**B**). It captures
the pre-activation text and the runner's `PENDING` values by design. A later
protected activation commit (**A**) updates this default-branch record and the
trusted workflow with the live pins; it does not alter B. Therefore, when this
file is read from the `baseline-v3` tag, its pending wording is historical and
non-authoritative. Candidate branches must remain based on B and must not merge,
rebase, or update from the upstream default branch; the submission scope is
checked as `B..candidate` and may contain only `Analyzer.java` and
`OPTIMIZATION.md`.

## Historical labels

`java-v1` and `java-v2`, including any similarly named baseline tags, are
historical labels only. They are not the current public contract and this file
makes no claim about their activation, calibration, or evidence status. In
particular, a pre-existing remote `baseline-v2` belongs to a different historical
contract and must not be used as the base for `java-v3` submissions.
