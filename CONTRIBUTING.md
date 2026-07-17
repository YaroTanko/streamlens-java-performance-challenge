# Contributing

Candidate pull requests must follow [TASK.md](TASK.md) and change at least one of
the two permitted deliverables. Maintainer changes to the contract, dependencies,
tests, workloads, runner, or source policy require a new immutable assessment
version rather than moving `baseline-v2` or `starter-v2`.

Before a maintainer release:

```bash
make check
make benchmark-smoke
make profile-jfr
git diff --check
```

Tags are immutable. The activation workflow must pin the full baseline commit SHA,
not only its tag.
