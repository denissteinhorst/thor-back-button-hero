# Unit-test suite stability run

Date: 2026-10-24

Command used for every run:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew testDebugUnitTest --rerun --console=plain
```

| Run | Result | Exit code | Wall time |
|---|---|---:|---:|
| 1 | BUILD SUCCESSFUL | 0 | 5.97 s |
| 2 | BUILD SUCCESSFUL | 0 | 6.13 s |
| 3 | BUILD SUCCESSFUL | 0 | 6.21 s |
| 4 | BUILD SUCCESSFUL | 0 | 6.15 s |
| 5 | BUILD SUCCESSFUL | 0 | 5.98 s |

Result: 5/5 independent runs green. No flaky test or test-order dependency was observed.
