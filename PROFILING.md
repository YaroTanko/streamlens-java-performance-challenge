# Profiling with Java Flight Recorder

Run:

```bash
make profile-jfr
```

The command executes short versions of all protected JMH scenarios with Java
Flight Recorder enabled. It writes:

- `.bench/profiles/balanced.jfr`
- `.bench/profiles/highCardinality.jfr`
- `.bench/profiles/mostlyFiltered.jfr`
- `.bench/profiles/jfr-summary.txt`
- `.bench/profiles/hot-methods.txt`

Open the recording in JDK Mission Control or inspect it with the JDK `jfr` tool.
Focus on execution samples, allocation samples, parser construction, collection
growth, lookup behavior, and sorting. A different profiler is equally acceptable.

Profiling is diagnostic. Do not compare JFR-instrumented times with authoritative
JMH scores. Record the actual tool/command and observed hotspot in the
`Profile evidence:` note; do not invent evidence.
