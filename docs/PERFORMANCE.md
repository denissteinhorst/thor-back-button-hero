# Performance

Measured runtime cost of the hot paths, the optimizations that were tried, and why most of
them were not kept. Read this before "speeding up" anything here: several obvious ideas
have already been measured and shown to gain nothing.

## 1.4.0 baseline

Measured 2026-10-30 on the AYN Thor (Android 13) with the 1.4.0 debug build
(`e49617e`), using temporary `System.nanoTime()` logging that was never committed.
Five real apps sat in bottom Recents and "show apps from both screens" was on. A debug
build includes LeakCanary, so memory figures are higher than in a release build.

| Measurement | Result |
|---|---|
| CPU while idle | 0.0 % (`top`); there are no polling loops, everything is event-driven and debounced |
| Memory | 66 MB PSS, of which about 9 MB is Java heap (debug build) |
| `onAccessibilityEvent` on the main thread (n = 53) | median 5.6 ms, p90 10.7 ms, max 17 ms |
| Opening bottom Recents, `SwitcherOverlay.show` (n = 32) | 20–44 ms, alternating fast/slow between openings |
| Frames (`dumpsys gfxinfo`, 220 frames) | p50 5 ms, p90 14 ms, p99 57 ms; 17 janky frames |
| Logcat | no Choreographer "Skipped frames", slow-dispatch, or ANR entries for the process |

The janky frames are essentially the first frame of each Recents opening and app launch.
Scrolling and swiping in the carousel do not miss deadlines.

### Where the time goes

**Per window event**, the median split was: top-display `record` 1.3 ms, bottom-display
`record` 0.8 ms, `currentException` 1.1 ms, `bottomDisplay()` 0.2 ms, D-pad passthrough
0.02 ms. The remaining ~2 ms is mostly the RetroArch `topApp` checks. Each part fetches
`windowsOnAllDisplays` and the window roots again, about five passes per event.

**Opening bottom Recents** splits into roughly:

| Phase | Fast opening | Slow opening |
|---|---|---|
| Display/window context | 1.6–2.4 ms | 2.4–3.8 ms |
| Recorded apps: `record` + labels + icons (both displays) | ~1.6 ms | ~10–13 ms |
| Recents theme, card style, badges | ~3 ms | ~4.5 ms |
| Building the views | ~5.5 ms | ~6 ms |
| `addView` | ~4 ms | ~4 ms |
| `onShown` (controller focus, pad capture, task-state query) | ~4.5 ms | ~8.5 ms |

The slow openings alternate with the fast ones and coincide with the openings where
`onShown` takes longer, i.e. where the controller focus is moved. On those openings,
PackageManager label and icon lookups cost about 3.5 + 5 ms instead of 0.1 + 0.4 ms.
The likeliest cause is that the focus switch invalidates the PackageManager and
Resources caches. That is part of the controller-focus logic and is deliberately left
alone.

## Optimizations tried or considered

| Idea | Expected gain | Outcome |
|---|---|---|
| Strong per-package icon cache in `AccessibilityAppSource`, keyed by APK path and configuration | ~5 ms on slow openings | **Built, measured, reverted.** No measurable change: the configuration in the key changes on exactly the slow openings, so the cache missed. Dropping the configuration from the key could save ~8 ms (icons + labels) on every second opening, but would serve stale icons after a night-mode or density change, and 8 ms of 36 ms is under half a frame. |
| Build the launcher context once per opening instead of three times (`RecentsTheme`, `RecentsCardStyle`, `RecentsBadges`) | ~0.7 ms | Not done; too small to matter. |
| One window snapshot per accessibility event instead of ~five passes | ~3 ms per event | Not done. It would restructure the window queries that parking, bottom panels, RetroArch detection and app exceptions depend on, for a gain nobody can feel. |
| Debounce the D-pad passthrough update | — | Rejected: the helper decides at the instant a direction is pressed, so it must be current on every event. |
| Pre-build the overlay views to shorten the first frame | ~10–15 ms | Not done: a large change to device-only code for a single frame per opening. |

## Fixed in 1.4.0

`SwitcherOverlay.dismiss()` kept `memoryLabel`, `emptyView` and both action buttons, and
each of them held the whole detached Recents tree through its parents until the next
opening. LeakCanary reported this as `SwitcherService.overlay → … → FrameLayout`. The
leak did not grow, but the tree stayed in memory for no reason. Since `e49617e` the
overlay releases those views on every close.

## Refactoring baseline (S7, 2026-11-22)

Measured on the Thor (Android 13) with a debug build of `refactor/main` whose production inputs are
identical to the baseline `a032d080` (LeakCanary included, so memory is higher than in a release),
using `tools/devicetest/resources.py`; raw files stay in `app/build/devicetest/`. The 1.4.0 numbers
above were taken with a different method and five Recents apps; they are history, not a comparison.

**Workload (F1):** a freshly restarted service in a warm process, Settings and Clock on top, Gallery on
the bottom, then ten openings and closings of the bottom switcher with a real Back combo on the pad.
Every run completed all ten. Three independent sets of five runs:

| Metric | Set A | Set B | Set C |
|---|---|---|---|
| PSS (KB), median / MAD | 61309 / 1066 | 62622 / 2036 | 60578 / 753 |
| Java heap (KB) | 4176 / 88 | 4200 / 36 | 4088 / 16 |
| Native heap (KB) | 12056 / 872 | 13628 / 492 | 11552 / 528 |
| Graphics (KB) | 16920 / 188 | 16724 / 132 | 16944 / 184 |
| Own threads (framework pools excluded) | 17 / 0 | 17 / 0 | 17 / 0 |
| Descriptors | 96 / 3 | 95 / 2 | 96 / 0 |

Single runs reach 69 MB PSS (one Graphics spike of 22 MB); the median and MAD absorb that. The binder
pool grows on demand from 5 towards 15 threads across runs, which is why framework pools are not
counted as the app's own threads; executor threads are recreated with each service start and the old
ones end.

**Latency (F2):** from the switcher button's DOWN (kernel monotonic stamp read in the press script,
±5 ms) to the completion of the app's first frame begun after it (gfxinfo framestats, same clock):

| | Median | MAD | p90 | Range | Missing |
|---|---|---|---|---|---|
| Cold (first opening after a service start), n = 5 ×2 | 255 / 244 ms | 7 ms | 259 / 254 ms | 237–288 ms | 0 |
| Warm, n = 15 ×2 | 244 / 244 ms | 5 ms | 254 / 256 ms | 228–303 ms | 0 |

This spans the whole path: the helper reading the pad, the clone, `onKeyEvent`, building and adding
the overlay, and its first frame. It is not a photon measurement; composition adds up to one vsync.

**Budgets**, fixed before any candidate was measured: a candidate's median may exceed the baseline's by
at most max(3 × MAD, floor), floors PSS 3 MB, Java 1 MB, native 3 MB, Graphics 1 MB, own threads 2,
descriptors 6; latency median max(3 × MAD, 20 ms), p90 max(3 × MAD, 30 ms); every workload cycle must
complete, no opening may lack a frame, no thread role may be new. All pairwise comparisons of the sets
above pass (`resources.py --compare`).

**Lifecycle and leaks (F3/F4):** five real disable/enable cycles through Android and one process kill.
The pad helper ends with the disabled service and exactly one comes back; no thread role or descriptor
kind is left over or grows. LeakCanary watched all five destroyed services and completed its heap
analysis every time with one application leak of a single signature (`bd017897…`):
`AccessibilityService$IAccessibilityServiceClientWrapper.mContext` holding the destroyed
`SwitcherService`, the binder-stub effect described in `BUILD-AND-RELEASE.md`. It is recorded as the
accepted baseline path; a candidate may add no other path.

**Limitations:** debug build only (release differs by LeakCanary); one device, room temperature, on
USB power; Recents holds the three workload apps, not five; the first frame is the app's own, which
may be the focus pictogram rather than the switcher when both draw.

## Measuring again

- **Do not use `uiautomator dump`** or any other `UiAutomation` client. Android 13 then
  unbinds and rebinds the accessibility service, which restarts `SwitcherService` and the
  pad helper and distorts every number. See the LeakCanary note in
  [`BUILD-AND-RELEASE.md`](BUILD-AND-RELEASE.md).
- `adb shell input keyevent 4` does not trigger the Back gestures. To inject a physical
  Back press, write to the pad clone's evdev node, which is world-writable. Look up its
  node with `adb shell getevent -pl`; it was `/dev/input/event11` here, but the number
  changes:
  ```sh
  D=/dev/input/event11
  adb shell "sendevent $D 1 158 1; sendevent $D 0 0 0; sendevent $D 1 158 0; sendevent $D 0 0 0"
  ```
  Two press/release pairs in one `adb shell` call make a double tap.
- Fill Recents with real apps first (`am start --display <id> -n <component>`). After
  every install the history is empty again, because it only contains apps seen since the
  service started.
- Every install recreates the pad clone; bounce RetroArch afterwards if it is running.
