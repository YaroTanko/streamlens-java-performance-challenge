# Optimization Notes

- Profile evidence: a 4-second `scripts/profile.sh cpu HighCardinality` JFR run sampled JSON string scanning, UTF-8 decoding, line reading, and aggregation.
- Bottleneck: per-line buffers/field maps/decoders and exact `BigInteger` window math added work to every event; dense groups also linearly searched user totals.
- Change: reuse a decoder and byte buffer, retain only the five relevant fields, use an exact integer-window fast path, and promote a group from a list to a hash index at 32 users.
- CPU effect: local JMH was directionally faster but variable on the shared host; the high-cardinality update path is constant-time after promotion.
- Allocation effect: local `gc.alloc.rate.norm` fell from roughly 195M/97M/240M to 132M/69M/183M B/op for Balanced/HighCardinality/MostlyFiltered.
- Correctness: the generic window path remains for fractional/overflow cases; promotion preserves validation order, input-order `double` sums, overflow precedence, and sorted output.
- Trade-off: line buffering and field capture are more explicit, and each large group has one representation transition plus map overhead.
- Verification: `make check`, protected-scope validation, local JMH, and a JFR CPU profile were run; CI remains the authoritative score.
