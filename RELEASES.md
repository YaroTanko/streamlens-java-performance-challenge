# Assessment releases

## Version 2 — active

`baseline-v2` and `starter-v2` retain the Java 21 API, contract, workloads, and
scoring policy introduced for version 1. Version 2 adds the complete dependency
verification metadata observed from a clean Gradle cache. Its exact Ubuntu
baseline canary is the activation gate.

## Version 1 — not activated

The first published baseline package passed warm-cache local validation but its
first clean Ubuntu canary failed before compilation because three trusted Maven
metadata checksums were absent. The immutable `baseline-v1` and `starter-v1` tags
remain as historical evidence and must not be used for candidate assessment.
