.PHONY: check test benchmark benchmark-smoke profile-jfr clean

check:
	./gradlew clean check
	./scripts/policy-test.sh
	./scripts/submission-policy-test.sh

test:
	./gradlew test

benchmark:
	./gradlew jmh

benchmark-smoke:
	./gradlew benchmarkSmoke

profile-jfr:
	./scripts/profile.sh

clean:
	./gradlew clean
	rm -rf .bench
