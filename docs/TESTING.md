# Test concept and quality gates

The app's features, layout and wording are final and validated. The entire test suite
exists to **freeze that state**, not to specify a desired one. That distinction drives
everything below.

## The governing rule

> If a test goes red after a change to production code, **the change is wrong, not the
> test.**

The only exception is a demonstrably faulty *new* test written in the same change. In
particular: never re-record a golden snapshot to make a failing test pass. Re-record only
for an intentional, explicitly approved visible change — and then verify the diff
contains nothing but that change.

These are **characterization tests**: they capture the behavior as it is, including
quirks. A test asserting something awkward (the `0L` sentinel in `DoubleTapDetector`, the
`pressFollowsTap` latching) is doing its job.

## The one gate

```sh
./tools/verify.sh
```

Use this, never a bare `./gradlew`. The script resolves JDK 17 itself — it is not on
`PATH` on the author's machine — and runs the aggregate `verify` task, which depends on:

| Step | What it proves |
|---|---|
| `lintDebug` | No new lint findings (18 pre-existing ones are baselined; `warningsAsErrors = true`) |
| `testDebugUnitTest` | All 326 JVM tests pass |
| `jacocoCoverageVerification` | Per-class line coverage has not dropped |
| `assembleDebug` | The debug variant builds |
| `assembleRelease` | The release variant builds (unsigned locally) |

One command is the acceptance criterion for any change. It runs in three places, all
identical:

- **Locally**, on demand.
- **`pre-push` hook**, before anything reaches the remote.
- **CI** (`.github/workflows/ci.yml`), on every push and PR to `main`.

A faster subset runs in the **`pre-commit`** hook: compile plus unit tests.

Hooks activate through `core.hooksPath`, which the `installGitHooks` Gradle task sets on
the first build — so they work right after a clone, with no manual step. Never bypass
them with `--no-verify`.

> **A green local gate does not mean green CI.** This has bitten the project before: with
> `warningsAsErrors` enabled, a CI runner carrying a newer Android platform raised lint's
> `OldTargetApi` while a machine with only `android-34` installed never saw it. CI was red
> for roughly fifteen commits while the local gate stayed green. Check the actual CI run.

## The pyramid

### Base — JVM tests (Robolectric + JUnit), 55 classes, 326 tests

Everything the gate can verify without hardware.

| Area | Covered by |
|---|---|
| Preferences: defaults, accessors | `PrefsDefaultsTest` |
| Preferences: one-time migrations, factory shoulder defaults, update safety, reset | `PrefsMigrationTest` |
| D-pad defaults, level targets, focus target/indicator/position/duration, change listener | `DpadPrefsTest` |
| Controller-focus target resolution and routing (toggle, fixed top/bottom) | `ControllerFocusTest` |
| Top-Recents helper protocol and Quickstep state/TaskView geometry parsing | `PadNavigationModeTest`, `TopRecentsStateTest` |
| Duplicate shoulder-key events from the two mirrored devices | `PhysicalKeyTrackerTest` |
| Settings actions: Unbind optionals, the indicator-appearance popup (dots, switch, display-time slider, opening from the pictogram) | `SettingsActionsTest` |
| Volume/brightness steps, hat-to-direction mapping | `LevelStepsTest` |
| Back-press decisions, all branches | `BackGestureMachineTest` |
| Press-interval detection | `DoubleTapDetectorTest` |
| Activation shortcut (pause/resume) and its interception gate | `ServiceToggleGestureTest`, `ServiceToggleSettingsTest`, `KeyInterceptionGateTest` |
| Per-app exceptions: rules, storage, settings page | `AppExceptionTest`, `ExceptionSettingsPageTest` |
| Active App Control: target resolution, defaults, snapshots | `AppControlTest` |
| Bottom Recents: cards, carousel, theme, style, badges, memory, empty state, selection, thumbnail cache, task state and query | `PanelCardsTest`, `RecentsCarouselTest`, `RecentsThemeTest`, `RecentsCardStyleTest`, `RecentsBadgesTest`, `RecentsDisplayBadgeTest`, `RecentsMemoryTest`, `RecentsEmptyViewTest`, `RecentsSelectionTest`, `RecentsThumbnailCacheTest`, `RecentsTaskStateTest`, `RecentTaskQueryTest`, `SwitcherPreviewTest`, `SwitcherCloseAllTest`, `SwitcherTaskSyncTest` |
| Top Recents navigation and state parsing | `TopRecentsNavigationTest`, `TopRecentsStateTest`, `TopRecentsBackTest` |
| Task moves, frontend parking and bottom panels | `TaskMovePolicyTest`, `ParkingPolicyTest`, `BottomPanelsTest`, `PanelWindowsTest` |
| Pad helper lifecycle, navigation mode | `PadHelperLifecycleTest`, `PadNavigationModeTest` |
| Shell quoting and package validation | `ShellTest` |
| Settings navigation and scoped change previews | `SettingsNavigationTest`, `SettingsChangesTest`, `SettingsChangeDialogTest` |
| Card touch arbitration | `CardTouchTest` |
| Recent-app bookkeeping, thumbnail cache | `AccessibilityAppSourceTest` |
| Release parsing, semver ordering, legacy tags | `UpdateCheckerParseTest` |
| UI-kit helpers | `SettingsUiKitTest` |
| EN/DE string parity | `ResourceIntegrityTest` |
| Infrastructure itself | `SmokeTest`, `support/ViewTreeSmokeTest` |

### UI protection — deterministic View-tree snapshots

Rather than pixel screenshots, `support/ViewTree.kt` renders a View hierarchy to
**text**: visibility, padding, margins, clickability, text, text size, color, bold, and
`checked` for compound buttons. `support/Golden.kt` compares that against a file in
`app/src/test/resources/golden/`.

Why text and not pixels: font rendering differs between macOS and the Linux CI runner, so
pixel goldens would make the gate unreliable. Text snapshots are platform-independent and
still catch any change to layout, wording, sizing or color.

The **44** goldens cover six-page navigation across locale/theme/window sizes,
button states, change previews and accessibility. The main groups are:

| Golden | Scope |
|---|---|
| `settings_main_en_light`, `settings_main_en_dark`, `settings_main_de_light` | The whole settings page across theme and locale |
| `shoulder_all_unassigned`, `shoulder_defaults_flick_no_toggle`, `shoulder_l1_sentinel_with_toggle`, `shoulder_l2_app_with_toggle`, `shoulder_l2_volume_with_target`, `shoulder_r1_screenshot_no_toggle`, `dpad_all_unassigned` | Every shoulder-row state, including whether the screen toggle shows. All but `shoulder_defaults_flick_no_toggle` start from L2/R2 unassigned, the state before the factory shortcuts existed; that one shows the real fresh-install defaults |
| `dialog_single_action_chooser`, `dialog_reset_settings_confirm`, `dialog_repair_service` | Three dialog flows |
| `a11y_inventory` | Accessibility properties of every clickable row |

These genuinely bite: a single visible top-bar change touches **38** of them at once
(verified when the app icon was added to the top bar).

To re-record after an approved visible change:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  UPDATE_GOLDENS=1 ./gradlew :app:testDebugUnitTest --rerun-tasks
git diff app/src/test/resources/golden/   # must contain only the intended change
```

### Tip — instrumented tests

`RecentsMemoryDeviceTest` checks on a real device that the launcher's memory helper can be read. `SettingsActivitySmokeTest` starts `SettingsActivity` on a real device to catch manifest,
resource and framework-integration failures that a JVM model cannot reproduce. It needs an
attached device and is **not** part of the gate:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew connectedDebugAndroidTest
```

## Quality ratchets

Two mechanisms that may tighten but never loosen.

### Lint

`warningsAsErrors = true` and `abortOnError = true`, with `app/lint-baseline.xml`
freezing the **18** findings that predate the suite (`DataExtractionRules`,
`ObsoleteSdkInt`, 5× `IconColors`, 5× `IconLauncherShape`, `MonochromeLauncherIcon`,
5× `GradleDependency`). Those are deliberately **not** fixed — each fix would alter a
visible asset, which the freeze forbids. Any *new* warning fails the build.

Lint also analyzes test sources, so a warning in new test code fails the gate too. Fix
the test code; do not disable the flag and do not add the finding to the baseline.

`OldTargetApi` is explicitly disabled, because it only fires in environments that have a
platform newer than `targetSdk` installed — a baseline entry would be the wrong tool for a
finding that does not exist everywhere. Re-enable it when `targetSdk` is deliberately
moved forward.

### Coverage

`jacocoCoverageVerification` enforces a per-class **line**-coverage minimum. Each rule
sets `counter = "LINE"` and `value = "COVEREDRATIO"` explicitly — without those JaCoCo
measures instructions instead, which is not what these numbers mean.

| Class | Minimum | Measured |
|---|---|---|
| `Prefs` | 1.00 | 1.0000 |
| `DoubleTapDetector` | 1.00 | 1.0000 |
| `LevelSteps` | 1.00 | 1.0000 |
| `DpadDirection` (and its companion) | 1.00 | 1.0000 |
| `CardTouch` | 1.00 | 1.0000 |
| `BackGestureMachine` | 0.98 | 0.9890 |
| `AccessibilityAppSource` | 0.61 | 0.6250 |
| `UpdateChecker` | 0.46 | 0.46–0.64 (network-dependent) |

A further 26 classes (settings, navigation, app exceptions, Recents routing/state, panels, key gate,
activation toggle) are ratcheted at 0.95–1.00 through a name→minimum map at the end of
the rules in `app/build.gradle.kts`.

Raise a minimum when tests grow; never lower one. Several sit exactly at the measured
value, so coverage must not drop at all.

Read these per JaCoCo **class**, which counts nested classes separately: `UpdateChecker`
is measured on its own while `UpdateChecker$Version` is its own entry. Aggregating a class with its
nested types overstates what the rule actually enforces.

Coverage requires `enableUnitTestCoverage = true` in the debug build type and
`isIncludeNoLocationClasses = true` for the Robolectric-loaded classes — without the
latter the report silently shows 0 % for everything while the build stays green.

## What is deliberately not automated

Known and accepted, because the alternatives are worse than the gap.

| Not covered | Why | Substitute |
|---|---|---|
| `SwitcherOverlay` — **36 %** (127/357 lines) | Needs a real `TYPE_ACCESSIBILITY_OVERLAY` window on a second display | Manual device testing |
| `SwitcherService` — **15 %** (165/1127) | Needs a live accessibility connection and real hardware key events | `BackGestureMachine` tests cover the decisions; the side effects are manual |
| `windowsOnAllDisplays` paths | Unavailable under Robolectric — may return empty, null, or throw | Partial `AccessibilityAppSourceTest` coverage |
| Dual-screen behavior as a whole | No emulator image exists for it | Manual device testing |
| `UpdateChecker` network, threading, notification | Would require restructuring production code the freeze protects | `parseVersion`/`parseRelease` are tested; the rest is manual |
| Service state `ON` in tests | `SwitcherService.isConnected` has a `private set` | Manual verification |
| `ControllerFocus` root sequence, `FocusIndicatorOverlay` (0/93) | Need pservice and a window on the top display; only target resolution and the settings around them are tested | MANUAL-TESTING C23–C29 |
| Root input helper, `PadHelper`, `DpadOverlay`, `LevelControl`, `BrightnessControl` | Need pservice, `/dev/input` and real displays | `LevelSteps`, `DpadDirection` and `Prefs` are tested; the rest is MANUAL-TESTING C17a–C17h |

Do **not** restructure production code to close these gaps. The freeze outranks coverage.

## When a device pass is required

Scale the effort to the change rather than always running everything. The tiers, the
file-to-tier mapping and the step-by-step procedures live in
[`MANUAL-TESTING.md`](MANUAL-TESTING.md); in short:

| Tier | Change | Manual effort |
|---|---|---|
| S0 | Docs, CI config, tests, goldens | None |
| S1 | `Prefs`, `SettingsUiKit`, `SettingsActivity`, `UpdateChecker`, strings, version bump | Settings smoke, ~3 min |
| S2 | `BackGestureMachine`, `DoubleTapDetector`, `CardTouch` | + full gesture pass, ~12 min |
| S3 | `SwitcherService`, `SwitcherOverlay`, `AccessibilityAppSource`, manifest, or a Kotlin/AGP/Gradle bump | + overlay and shoulder pass, ~30 min |
| S4 | Publishing a release | + acceptance on the signed artifact, ~40 min |

Run the applicable tier **before pushing**. For changes that are meant to be
behavior-neutral, use the gesture-trace comparison described there instead of relying on
an impression — it diffs the actual logged decisions before and after.

## Suite stability

Before the suite was accepted as a baseline it was run five times independently, green
every time. Note that `SettingsActivity.onCreate` starts a real network thread, so every
Robolectric activity test genuinely contacts `api.github.com` and may wait out a 5 s
timeout. Failures are swallowed and the tests stay green — leave it alone. Only report it
if an activity test becomes *flaky* with `UpdateChecker`, `HttpURLConnection` or `Thread`
in the trace.

## Reference: other environment traps

- Unit tests run with **`app/`** as the working directory, not the repo root.
- Robolectric qualifier order matters: `en-night-xxhdpi`, not `en-xxhdpi-night`.
- A locally built release APK is **unsigned** and Android refuses to install it
  (`INSTALL_PARSE_FAILED_NO_CERTIFICATES`). Measure on the debug build.
- `adb shell am force-stop` cannot kill the app while its accessibility service is
  enabled, so `am start -W` reports `HOT`/`WARM` instead of `COLD`. A true cold-start
  measurement needs the service disabled in Android settings first.
