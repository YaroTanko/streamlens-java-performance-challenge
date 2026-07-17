# Contributing

This repository is primarily designed for candidate optimization pull requests.
Read `TASK.md` before starting; candidate scope is intentionally narrow.

Read current activation status and pins only from `RELEASES.md` and the trusted
assessment workflow on the upstream protected default branch. The immutable
`baseline-v6` tag and candidate branches are snapshots: their status text and
intentionally `PENDING` runner values are historical and non-authoritative after
activation. Maintainers must not schedule a scored session until the live release
record shows the baseline/image pins, real Docker-runtime canary, calibration,
private-evaluator pre-score review, and other acceptance evidence in `PRD.md`.

## Candidate fork and branch workflow

1. Obtain access to the upstream repository and create the permitted fork (for a
   private organization repository, the organization must allow private forks).
2. Clone the fork and add the upstream remote if needed:

   ```sh
   git clone <your-fork-url>
   cd streamlens-java-performance-challenge
   git remote add upstream <upstream-url>
   git fetch upstream --tags
   git switch --detach baseline-v6
   git switch -c optimize-analyzer
   ```

   `baseline-v6` is the immutable candidate snapshot (**B**). Do not merge,
   rebase, or otherwise update `optimize-analyzer` from the upstream default
   branch after this point. That branch can later contain a protected activation
   commit (**A**) and other files outside candidate scope; CI evaluates the exact
   `B..candidate` diff.

3. Before the timer, the interviewer verifies a clean checkout and working Java
   21/Gradle environment. During the timed session, run:

   ```sh
   make check
   make benchmark
   make profile-cpu  # or make profile-alloc / another profiler
   ```

4. Modify only:

   ```text
   src/main/java/com/streamlens/analyzer/Analyzer.java
   OPTIMIZATION.md
   ```

5. Inspect the complete diff, rerun checks, commit, and record the final SHA
   before 30:00:

   ```sh
   git diff --check
   git status --short
   git add src/main/java/com/streamlens/analyzer/Analyzer.java OPTIMIZATION.md
   git commit -m "Optimize event analysis"
   git rev-parse HEAD
   ```

6. After the timer, push that exact commit and open a pull request to the upstream
   default branch:

   ```sh
   git push -u origin optimize-analyzer
   ```

7. Complete the pull-request template and inspect the Actions summary. A forked
   workflow may require interviewer/maintainer approval; approval and queue time
   are outside the candidate timer.

## Submission expectations

- Preserve every invariant in `PRD.md`.
- Use only the safe Java 21 JDK subset accepted for `Analyzer.java`.
- Keep the change ordinary, focused, and reviewable.
- Provide 5–10 concise bullets in `OPTIMIZATION.md`.
- Name the profiler/tool and hotspot actually observed.
- Distinguish measured local results from expected effects.
- Do not modify, bypass, recognize, or weaken protected assessment assets.

The analyzer source audit rejects filesystem/output, networking/IPC,
subprocess/process-wide runtime behavior, dynamic code/reflection, native access,
instrumentation/management/JFR control, logging, test hooks, thread creation, and
protected benchmark markers. This policy does not restrict external profiling or
AI use and does not replace interviewer diff review.

## How CI evaluates a submission

Trusted tooling reads the exact committed candidate blobs, validates that only
the two allowed regular files changed, and type-checks `Analyzer.java` against the
safe allow-list. It then creates a fresh tree from the immutable baseline and
overlays only those files. Candidate tests, build files, scripts, workflows,
generated files, symlinks, and submodules are not executed. The scope comparison
is `baseline-v6..candidate`; a candidate branch must not bring in an upstream
default-branch activation update.

Fixed correctness and JMH commands run in the release's digest-pinned restricted
container. A trusted baseline oracle verifies a fresh per-assessment randomized
public corpus before JMH runs. CI reports `ns/op` and normalized `B/op`;
CPU/allocation JFR output is diagnostic. Public evidence is not the final score:
the pinned private evaluator and human debrief are required pre-score gates. Read
`TASK.md` for thresholds and `INTERVIEWER_GUIDE.md` for how human review uses the
evidence.

## Maintainer changes

A maintenance pull request may change protected files only when it is explicitly
identified as repository maintenance, never as a candidate submission. Any
material change to the contract, Java patch version, workload, baseline analyzer,
source policy, comparator, runner, or container image creates a new assessment
version with a new immutable baseline and canary evidence.

Maintainers must not move a baseline tag or silently repin an active version.
Before activation, complete the acceptance audit in `PRD.md`, retain benchmark
calibration and real-canary artifacts, and reconcile every documented path,
command, metric, and claim against the release tree.
