# Assessment runtime image

The authoritative assessment runs in a repository-owned image containing JDK
21, the Gradle Wrapper distribution, and the exact JUnit/JMH dependency cache.
The image is built from a digest-pinned Temurin base. Candidate execution uses
the resulting image by digest, offline, with no network and a read-only root.

`baseline-v2` / `java-v2` is **not active** until all of these facts are committed:

1. an immutable baseline commit and `baseline-v2` tag;
2. an exact `ghcr.io/...@sha256:<64-hex>` assessment image in the workflow;
3. a successful real-runtime canary using that exact image and baseline;
4. a workflow pin to the immutable baseline commit;
5. calibration evidence for the published thresholds; and
6. a pinned, reviewed private-evaluator pre-score gate.

The manual `build-assessment-image.yml` workflow accepts the full baseline SHA
and Temurin base digest, verifies that the SHA is exactly `baseline-v2`, builds
and pushes from that tree, then runs the complete exact-image canary. It retains
the canary evidence and prints the immutable runtime digest. The reviewed SHA
and digest must still be pinned in the trusted PR workflow. A tag alone is never
accepted by the assessment runner.

For each assessment, the trusted public runner uses fresh entropy to create a
randomized corpus and obtains the complete expected-result record from the
immutable baseline oracle before it measures the candidate. This public
verification does not make the corpus secret and does not replace the private
evaluator. A candidate result may be used only after the separately maintained
private evaluator is pinned, run for the submitted SHA, and reviewed; this pending
release does not claim that this has happened.
