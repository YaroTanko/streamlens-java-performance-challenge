# Profiling StreamLens Java

Profiling should guide the optimization; it is not itself a score. The
authoritative CI comparison scores JMH `ns/op` and normalized allocation `B/op`.
Use the profiler that gives you trustworthy evidence. Java Flight Recorder (JFR)
is provided because it ships with Java 21.

## Provided commands

Capture the default Balanced CPU recording and summary:

```sh
make profile-cpu
```

Capture the default Balanced allocation recording and summary:

```sh
make profile-alloc
```

Run the local comparative benchmark separately:

```sh
make benchmark
```

The Make targets call protected Gradle/JMH infrastructure and write:

```text
build/profiles/cpu/recording.jfr
build/profiles/cpu/summary.txt
build/profiles/cpu/hotspots.txt
build/profiles/cpu/jmh.txt
build/profiles/alloc/recording.jfr
build/profiles/alloc/summary.txt
build/profiles/alloc/hotspots.txt
build/profiles/alloc/jmh.txt
```

Each profile target refreshes its corresponding directory. These are ignored
local diagnostics; copy a directory elsewhere if you need to retain a
before/after pair.

Do not treat profile wall-clock numbers as benchmark results. JFR recording,
event sampling, JVM warmup, and diagnostic settings change execution conditions.

## Reading JFR evidence

`hotspots.txt` is the fastest orientation, `summary.txt` describes the recording,
and `jmh.txt` retains the profiled JMH output. JDK Mission Control can inspect call
trees, hot methods, allocation classes, threads, and recording configuration. The
JDK CLI can inspect the raw file without another dependency:

```sh
jfr summary build/profiles/cpu/recording.jfr
jfr print --events jdk.ExecutionSample,jdk.NativeMethodSample \
  build/profiles/cpu/recording.jfr
jfr print --events jdk.ObjectAllocationSample \
  build/profiles/alloc/recording.jfr
```

CPU samples identify code observed on-CPU, not necessarily the root cause. Check
the caller path and workload before rewriting a hot method. Allocation samples
identify allocation pressure and representative objects; they are sampled events,
not an exact `objects/op` counter. Use JMH `gc.alloc.rate.norm` for quantitative
before/after allocation volume.

Useful hypotheses commonly involve decoding, JSON token/string creation,
timestamp parsing, group-key construction, user lookup, top-K selection, sorting,
or copying result collections. A profile must decide which paths are actually hot
in the run you observed; this list is not a prescribed solution.

## Other profilers are allowed

You may use JDK Mission Control, async-profiler, Java tooling in an IDE,
operating-system sampling, or another profiler. A local tool does not become a
production dependency simply because it was used during development.

Static source inspection and an AI assistant may supplement measured evidence but
do not turn an unmeasured guess into a profile observation. Do not claim a hotspot
or gain you did not observe.

## Writing the evidence note

Keep the exact `Profile evidence:` label in `OPTIMIZATION.md`. Name both the tool
or command and the observed hotspot. A useful concise statement looks like:

```text
- Profile evidence: `make profile-alloc` JFR showed event-field String/Map
  construction dominating sampled allocation pressure in Balanced.
```

Then distinguish measured results from expectations. If you only measured a
local benchmark, report it as local and directional. If no stable percentage was
measured, say what effect you expect and why instead of inventing a number.

## CI diagnostics

CI captures fresh CPU and allocation JFR diagnostics for the submitted analyzer
in a dedicated step and retains recordings plus readable summaries. The reviewer
uses them to test whether the explanation and resulting call paths are plausible.

CI profiles cannot prove what the candidate ran during the timed session, who
authored the code, or whether the candidate understands it. They are never mixed
into alternating baseline/candidate samples and never determine the numeric tier.
