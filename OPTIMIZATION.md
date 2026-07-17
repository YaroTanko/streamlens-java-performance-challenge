# Optimization Notes

- Profile evidence: a 4-second `scripts/profile.sh cpu HighCardinality` JFR run sampled `LineReader.readLine`, `decodeLine`, and `addEvent`.
- Bottleneck: the baseline linearly searched every group user's list, which grows substantially in the high-cardinality workload.
- Change: index user totals by `HashMap` instead of linearly scanning a group's user list for every accepted event.
- CPU effect: same-host directional JMH reduced HighCardinality from 225.4 ms/op to 53.7 ms/op, while Balanced and MostlyFiltered stayed near baseline.
- Allocation effect: retains one hash entry per distinct user instead of growing a list; parsing allocation is deliberately unchanged.
- Correctness: preserves validation order, input-order `double` sums and overflow precedence, then sorts all users before output.
- Trade-off: more explicit map-backed aggregation and hash-table overhead for very small groups.
- Verification: `make check` passed, including source audit and complete-result contracts; local JMH is directional only, not a release score.
