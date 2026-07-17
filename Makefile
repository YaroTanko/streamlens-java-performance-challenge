SHELL := /bin/bash

.PHONY: check benchmark profile-cpu profile-alloc

check:
	./gradlew --no-daemon --console=plain test
	bash scripts/source-audit.sh src/main/java/com/streamlens/analyzer/Analyzer.java src/main/java
	bash scripts/source-audit-test.sh
	bash scripts/check-protected-test.sh
	bash scripts/prepare-candidate-test.sh
	bash scripts/benchmark-compare-test.sh
	bash scripts/evidence-manifest-test.sh
	bash scripts/isolation-test.sh
	bash scripts/assess-test.sh
	bash scripts/calibrate-test.sh
	bash scripts/jmh-contract-test.sh

benchmark:
	bash scripts/benchmark.sh

profile-cpu:
	bash scripts/profile.sh cpu

profile-alloc:
	bash scripts/profile.sh alloc
