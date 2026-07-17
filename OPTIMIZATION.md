# Optimization Notes

- Profile evidence: `make profile-cpu` captured baseline JFR data for the Analyzer parsing and aggregation workload.
- Approach: retain the immutable baseline implementation as the A/A calibration control.
- Contract: no parsing, validation, ordering, cancellation, or floating-point behavior changes.
- Expected effect: no intentional CPU or allocation improvement; measured variance is the calibration signal.
- Trade-off: this submission is useful only as a release-control reference, not as a candidate optimization.
- Verification: `make check`, benchmark, CPU JFR, and allocation JFR passed on the baseline.
