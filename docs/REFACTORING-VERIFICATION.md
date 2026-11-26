# Refactoring verification (F1)

The final verification of the structural refactoring on branch `refactor/main`: one complete run
on the final candidate, against the frozen baseline from before the refactoring. The refactoring
changed structure only; behavior, look, wording and stored data were to stay identical. Nothing
was pushed, tagged or released.

## Candidate

| Item | Value |
|---|---|
| Commit | `713ed4583ad88783c5fb54981d88bce4311fcd24` (tree `1ef40577`) |
| Baseline | `f3f8dbf` (its frozen artifacts were kept locally and removed after acceptance) |
| Toolchain | JDK 17.0.21, Kotlin 2.4.21, AGP 9.5.1, Gradle 9.9.2, NDK 29.0.14206865 |
| Debug APK | sha256 `c6784619…b0ed` |
| Release APK (unsigned) | 4 270 691 bytes, sha256 `e37dedd3…cb68` |
| Device | AYN Thor, firmware `TKQ1.231222.001` (eng.Thor.20260206), same as the baseline runs |

## Results

| # | Check | Result |
|---|---|---|
| 1 | `./tools/verify.sh` | Green: 868 JVM tests in 131 classes, 0 failed/skipped; lint 0 errors (20 baselined warnings); 92 coverage ratchets; both builds and androidTest compile |
| 2 | Contract check `check.sh --phase R` and its negative self-tests | 11/11 PASS; 23/23 self-test faults rejected |
| 3 | In-place update (`upgrade.py`) | 3/3 stores (populated, pre-1.1 legacy, fresh) kept service binding and every stored entry |
| 4 | Device suite `run.py --full`, both layers, and instrumented tests | 95/96 and 2/2; the one failure is explained below |
| 5 | Gesture traces vs the baseline build | 8/8 scenarios identical |
| 6 | Visual frames vs the baseline (`visual.py`) | 124/124 states identical |
| 7 | Resources, latency, lifecycle | PSS median 60.4 MB (baseline 60.6–62.6), threads 17 = 17, fds 100 (+4–5, within budget), latency cold 249 ms / warm 256 ms (baseline 244–255), all within the budgets fixed before the refactoring; lifecycle leaves no helper or recorder behind, LeakCanary shows only the known binder-stub signature of the baseline |
| 8 | Release artifact | Same entries, resources, manifest and native helper as the baseline; only `classes.dex` grew (+82 KB, the extracted classes); release classpath still `kotlin-stdlib` + `annotations`, no LeakCanary or test hooks |
| 9 | Licenses and provenance | No new dependency; all added files are project-owned and fall under the repository's 0BSD license; Kotlin stdlib (Apache-2.0) and debug-only LeakCanary unchanged |
| 10 | Mutation drills | Contract drills all killed; each extracted seam was drilled in its own phase (audio mix, helper protocol, describers, routers) |

### The one device-suite failure

`appcontrol` "Force-close ends the top app" fails when it runs after the earlier areas of a full run
(Settings keeps its process), and passes when the area runs alone. The pre-refactoring baseline build
fails in exactly the same way with the same area order (A/B on one device, same session), so it is a
property of the suite's order or of the device, not of the refactoring. It stays a harness follow-up.

## Known limits

- The release build was inspected, not run: a locally built release APK is unsigned and cannot be
  installed. The release-only update offer stays covered by its JVM tests and the owner's release check.
- The move-only drills now fail for a structural reason (the file they mutate is new against their
  base), so they no longer prove their original intent; that tool only guarded the package move.
- Live overlay behavior on the bottom screen, recording audio by ear and the feel of timing remain
  for the owner's final device test.
