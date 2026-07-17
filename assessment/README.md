# Assessment runtime image

The authoritative assessment runs in a repository-owned image containing JDK
21, the Gradle Wrapper distribution, and the exact JUnit/JMH dependency cache.
The image is built from a digest-pinned Temurin base. Candidate execution uses
the resulting image by digest, offline, with no network and a read-only root.

## Snapshot and live activation records

`baseline-v6` is the immutable candidate snapshot (**B**), not an activation
record. Its pre-activation status prose and runner `PENDING` values are frozen
with the candidate tree and intentionally remain non-authoritative after a later
protected activation commit (**A**). Current activation status and exact pins are
authoritative only in `RELEASES.md` and the trusted assessment workflow on the
upstream protected default branch. Candidate branches stay based on B and must
not merge, rebase, or update from that branch; scope is checked as `B..candidate`.

The live `java-v6` release is eligible only when all of these facts are committed
to the protected-default-branch release record:

1. an immutable baseline commit and `baseline-v6` tag;
2. an exact `ghcr.io/...@sha256:<64-hex>` assessment image in the workflow;
3. a successful real-runtime canary using that exact image and baseline;
4. a workflow pin to the immutable baseline commit;
5. calibration evidence for the published thresholds; and
6. a pinned, reviewed private-evaluator pre-score gate.

The manual `build-assessment-image.yml` workflow accepts the full baseline SHA
and Temurin base digest, verifies that the SHA is exactly `baseline-v6`, builds
and pushes from that tree, then runs the complete exact-image canary. It retains
the canary evidence and prints the immutable runtime digest. The reviewed SHA
and digest must still be pinned in the trusted PR workflow. A tag alone is never
accepted by the assessment runner.

For each assessment, the trusted public runner uses fresh entropy to create a
randomized corpus and obtains the complete expected-result record from the
immutable baseline oracle before it measures the candidate. This public
verification does not make the corpus secret and does not replace the private
evaluator. A candidate result may be used only after the separately maintained
private evaluator is pinned, run for the submitted SHA, and reviewed; the
baseline snapshot does not claim that this has happened.
