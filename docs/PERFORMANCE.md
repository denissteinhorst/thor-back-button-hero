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

## Measuring again

- **Do not use `uiautomator dump`** or any other `UiAutomation` client. Android 13 then
  unbinds and rebinds the accessibility service, which restarts `SwitcherService` and the
  pad helper and distorts every number. See the LeakCanary note in
  [`MAINTENANCE.md`](MAINTENANCE.md).
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
