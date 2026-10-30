# Project structure

Every tracked file and folder, and what it is for. Repository-root paths throughout.
Generated artifacts (`build/`, `.gradle/`, `local.properties`, `release.keystore`) are
gitignored and therefore absent here.

## Top level

| Path | Purpose |
|---|---|
| `README.md` | The user-facing page: what the app does, gestures, install steps, permissions and privacy, troubleshooting. |
| `CLAUDE.md` | Working instructions for Claude Code — gate command, expected results, toolchain traps, release and signing-key procedures. |
| `LICENSE` | 0BSD. Chosen deliberately: the release APK ships no third-party code beyond the Kotlin stdlib (see [`MAINTENANCE.md`](MAINTENANCE.md)). |
| `.gitignore` | Excludes build output, IDE files, `local.properties`, and — importantly — `*.keystore`/`*.jks` so a signing key can never be committed. |
| `settings.gradle.kts` | Gradle settings: declares the single `:app` module and the plugin/dependency repositories. |
| `build.gradle.kts` | Root build file. Declares the AGP/Kotlin plugin versions, the **`verify`** aggregate task (the one quality gate), and the `installGitHooks` task. |
| `gradle.properties` | JVM and Gradle flags for the build (heap, AndroidX, caching). |
| `gradlew`, `gradlew.bat` | Gradle wrapper launchers. Always build through these, never a system Gradle. |
| `gradle/wrapper/gradle-wrapper.properties` | Pins the Gradle version (9.8.2). |
| `gradle/wrapper/gradle-wrapper.jar` | The wrapper bootstrap jar. |

## `app/` — the single module

| Path | Purpose |
|---|---|
| `app/build.gradle.kts` | All module configuration: SDK levels, `versionCode`/`versionName`, environment-driven release signing, lint settings (baseline + `warningsAsErrors`), the JaCoCo report and the per-class coverage ratchets, and the test/debug-only dependencies. |
| `app/lint-baseline.xml` | Freezes the **18** pre-existing lint findings so only *new* ones fail the build. Deliberately not "fixed" — each fix would change a visible asset. |

### `app/src/main/` — production code

| Path | Purpose |
|---|---|
| `AndroidManifest.xml` | Declares the two entry points (`SettingsActivity` as launcher, `SwitcherService` as accessibility service), the four permissions, and `allowBackup="false"`. |

#### Kotlin sources — `app/src/main/java/dev/steinhorst/backbuttonhero/`

One flat package by design; the app is small enough that package boundaries would add
ceremony without adding clarity. See [`ARCHITECTURE.md`](ARCHITECTURE.md) for how they
collaborate.

| File | Lines | Purpose |
|---|---|---|
| `SwitcherService.kt` | 479 | The accessibility service and runtime coordinator. Intercepts Back and the four shoulder keycodes, receives Back + D-pad from `PadHelper`/`DpadOverlay`, owns the Android side effects (global actions, display-aware launches, screenshots, controller focus, screen swap), captures switcher thumbnails, and creates `SwitcherOverlay`. Delegates Back-key *decisions* to `BackGestureMachine`. |
| `BackGestureMachine.kt` | 136 | The Back-press state machine, free of Android side effects: single tap vs. double tap vs. hold vs. tap-then-hold. Clock, scheduling and actions enter through small interfaces so the timing logic is unit-testable on the JVM. |
| `DoubleTapDetector.kt` | 18 | Standalone press-interval tracker. Fires when two presses fall inside the configured window. Uses `0L` as a "no previous press" sentinel — relevant when writing tests. |
| `AccessibilityAppSource.kt` | 89 | The only source of "which apps are running". A normal app cannot read other apps' tasks, so it reconstructs a most-recently-used list per display from the windows the accessibility service observes, filters a fixed ignore-set of launcher/system/OEM packages, and caches thumbnails in memory. |
| `RunningApp.kt` | 12 | The `RunningApp` data class (package, label, icon, thumbnail) and the `AppSource` interface that `AccessibilityAppSource` implements. |
| `SwitcherOverlay.kt` | 228 | The bottom-screen app switcher, drawn as a `TYPE_ACCESSIBILITY_OVERLAY` window bound to a specific display. Scroll row of cards, memory readout, bottom-screen Screenshot and Close all actions, and the empty state. Built from framework Views in code. |
| `SystemScreenshot.kt` | 129 | Short-lived privileged entry point and Binder handshake for Android's native, display-targeted screenshot flow. Captures the bottom display while SystemUI shows its action strip on the top display. |
| `CardTouch.kt` | 45 | Touch arbitration for a switcher card: tap opens, upward swipe past a threshold closes, horizontal drags are handed to the scroller. Extracted so the gesture maths is testable without a window. |
| `SettingsActivity.kt` | 817 | The launcher UI, assembled programmatically. Reads and writes everything through `Prefs`, reports accessibility-service health with a "repair service" flow, triggers the update check, offers "Unbind optionals" and "Reset settings", and hosts the indicator-appearance popup (also opened directly from the focus pictogram). |
| `SettingsUiKit.kt` | 576 | The hand-built UI toolkit behind the settings screen: `dp()`, service-status helpers (`ServiceStatus`, `currentServiceStatus()`), the `SettingsPage` builder that produces cards, rows, sliders and choosers, and `positionGrid()`, the 3×3 dot picker on a 16:9 outline. |
| `SettingsNavigation.kt` | 211 | Adaptive six-page navigation, lazy page cache and scroll state. |
| `SettingsChanges.kt` | 91 | Immutable settings snapshots and scoped defaults/unbind change plans. |
| `ExceptionSettingsPage.kt` | 428 | Per-app exception editor: app search, allow/block start choice, twelve-column matrix with diagonal labels, active switch, trash and all-box. |
| `AppExceptions.kt` | 58 | `ExceptionControl` columns and `AppException` rules, plus their stored line format. |
| `Prefs.kt` | 256 | `SharedPreferences` wrapper: schema, defaults, typed accessors, and the one-time **migrations** in its `init` block. The single place where stored-preference changes belong. |
| `ShoulderButton.kt` | 42 | The `ComboButton` interface and its two enums: the four shoulder buttons (preference key, Android keycode) and the four D-pad directions (preference key, default action, hat-axis mapping). |
| `PhysicalKeyTracker.kt` | 31 | Collapses duplicate shoulder-key events from the Thor's two mirrored controller input devices into one physical press/release cycle. |
| `PadHelper.kt` | 116 | Starts the native root helper through `PService`, accepts only a uid-0 peer on its abstract socket, and turns its reports into D-pad presses. See [`HELPER.md`](HELPER.md). |
| `DpadOverlay.kt` | 71 | Fallback without the helper: a focusable 1×1 overlay that exists only while Back is held and catches the D-pad's hat events, at the cost of the foreground app's focus. |
| `LevelControl.kt` | 69 | The volume and brightness actions for both screens, the top or the bottom one. |
| `AppControl.kt` | 158 | The Active App Control action: `AppControl.targets` resolves the foreground apps of the chosen screen (top, bottom, both) without ever returning a launcher, system UI or this app, and `AppControlPrompt` is the small focusable overlay behind "Ask" and the optional force-close confirmation. It takes window focus on the controller's display because hat and stick events only reach the focused window. |
| `ControllerFocus.kt` | 61 | Routes the controller to the top or bottom screen with the same `PService` sequence as AYN's own tool (property, setting, `keyevent 60` without `-d`), and resolves the toggle target from `Settings.System`. |
| `FocusIndicatorOverlay.kt` | 125 | Brief top-screen accessibility overlay with a handheld pictogram showing which screen has the controller. Nine positions (half size away from the centre, equal margins to the physical display edges), configurable display time, optional tap-through to the settings. |
| `ScreenshotSaver.kt` | 40 | Saves a screenshot of one display to `Pictures/Screenshots` through MediaStore (no storage permission). The system action only reaches the top screen, so the bottom one is captured by the service and saved here. |
| `LevelSteps.kt` | 17 | Pure step arithmetic for volume and brightness (15 equal brightness steps). |
| `BrightnessControl.kt` | 55 | Root `app_process` entry point: steps the brightness of one or more displays via `IDisplayManager`. |
| `PService.kt` | 120 | Bridge to AYN's root daemon `pservice` (`PServerBinder`), and `runEntryPoint` for root `app_process` entry points. |
| `TaskRemover.kt` | 67 | Root entry point: removes recent tasks like Recents' "Clear all". |
| `Shell.kt` | 15 | Validation and quoting for everything that reaches a root shell. |

#### Native helper — `app/src/main/cpp/`

| File | Lines | Purpose |
|---|---|---|
| `padhelper.c` | 352 | The root input helper: hides AYN's pad behind an identical uinput clone, captures D-pad for Back combos and the bottom switcher, and captures only Up for top-Recents card dismissal. Kernel UAPI and libc only; compiled by the Gradle task `buildPadHelper` with the NDK's clang and shipped as `lib/arm64-v8a/libpadhelper.so`. See [`HELPER.md`](HELPER.md). |
| `UpdateChecker.kt` | 141 | Polls the GitHub Releases API with `HttpURLConnection` and manual JSON parsing (no HTTP or JSON library), compares the semver tag against the installed `versionName`, and posts a notification. No in-app APK install by design. |

#### Resources — `app/src/main/res/`

| Path | Purpose |
|---|---|
| `values/strings.xml` | English strings (**137**, source of truth). |
| `values-de/strings.xml` | German translations (**136**; the difference is `app_name`, which is `translatable="false"` — this gap is correct and asserted by a test). |
| `values/colors.xml` | The few named colors used by the hand-built UI. |
| `xml/accessibility_config.xml` | The accessibility-service declaration: requested event types and the capability flags the app depends on — key-event filtering, interactive-window retrieval, gesture performing and screenshot taking. |
| `drawable/ic_clear_all.xml`, `ic_screenshot.xml` | Vector icons for the switcher's system-Recents-style actions. |
| `drawable-nodpi/focus_top_active.webp`, `focus_bottom_active.webp` | The two controller-focus pictograms (top or bottom screen active), with the area outside the handheld transparent. |
| `drawable-*/ic_launcher_foreground.png` | Launcher foreground at five densities; also reused as the notification icon. |
| `mipmap-*/ic_launcher.png` | Launcher icon at five densities. |
| `mipmap-anydpi-v26/ic_launcher.xml` | Adaptive-icon definition for API 26+. |

There are **no layout XML files** and no Compose: every view is constructed in Kotlin.

### `app/src/test/` — JVM tests (Robolectric + JUnit)

The automated safety net. 22 test classes, **140 tests**. Run by the gate.

| File | Purpose |
|---|---|
| `SmokeTest.kt` | Minimal proof the test infrastructure itself works. |
| `PrefsDefaultsTest.kt` | Every default value and typed accessor in `Prefs`. |
| `DpadPrefsTest.kt` | D-pad defaults, level and screenshot targets, `hasDpadAction`, the change listener. |
| `AppControlTest.kt` | Target resolution for every screen choice, the defaults of the per-button app-control settings, and their round trip through snapshots. |
| `ControllerFocusTest.kt` | Target resolution for toggle, fixed top, and fixed bottom controller focus, including the route read from `Settings.System`. |
| `SettingsActionsTest.kt` | Unbind optionals (confirm and cancel) and the indicator-appearance popup: dots, show switch, display-time slider, opening it from the pictogram. |
| `PhysicalKeyTrackerTest.kt` | Duplicate-device key-down deduplication, release tracking, and reset behavior. |
| `LevelStepsTest.kt` | Volume and brightness step arithmetic and the hat-to-direction mapping. |
| `PrefsMigrationTest.kt` | The one-time preference migrations, the factory shoulder defaults and their update safety, `resetToDefaults()` and `unbindOptionals()` — the highest-risk code for existing installs. |
| `DoubleTapDetectorTest.kt` | Press-interval behavior, including the clock-at-zero sentinel. |
| `BackGestureMachineTest.kt` | Every branch of the Back decision logic with a controlled clock and scheduler. |
| `CardTouchTest.kt` | Tap vs. swipe-up vs. horizontal-drag arbitration. |
| `AccessibilityAppSourceTest.kt` | Recent-app bookkeeping and the thumbnail cache, within what Robolectric can reach. |
| `SettingsNavigationTest.kt`, `ExceptionSettingsPageTest.kt` | Navigation/scroll state, aligned controls, exception picker, matrix, persistence and remove-all. |
| `SettingsChangesTest.kt`, `SettingsChangeDialogTest.kt`, `ServiceToggleSettingsTest.kt` | Scoped change previews, atomic application and activation-shortcut removal. |
| `SettingsUiKitTest.kt` | The UI-kit helpers (`dp`, service-status mapping). |
| `UpdateCheckerParseTest.kt` | Release-payload parsing, semver ordering, the legacy tag forms, and the inputs that must be rejected. |
| `ResourceIntegrityTest.kt` | EN/DE string parity and resource consistency. |
| `SettingsActivitySnapshotTest.kt` | View-tree snapshots of the settings page across locale and theme. |
| `ShoulderRowSnapshotTest.kt` | View-tree snapshots of every shoulder-row state, a level action with its screen row, and all D-pad directions unassigned. |
| `SettingsDialogSnapshotTest.kt` | View-tree snapshots of three dialogs. |
| `AccessibilityInventoryTest.kt` | Freezes an inventory of every clickable row's accessibility properties. |
| `support/ViewTree.kt` | Renders a View hierarchy to deterministic text — the snapshot format. Platform-independent, unlike pixel screenshots. |
| `support/Golden.kt` | Compares against a golden file, or rewrites it when `UPDATE_GOLDENS=1`. See the golden rule in [`TESTING.md`](TESTING.md). |
| `support/ViewTreeSmokeTest.kt` | Verifies the snapshot renderer itself. |

| Path | Purpose |
|---|---|
| `resources/robolectric.properties` | Pins Robolectric to `sdk=34`, matching `compileSdk`. |
| `resources/golden/*.txt` | The **44** golden snapshots: six settings pages across locales/themes/window widths, shoulder/D-pad states, change-preview dialogs and accessibility inventory. |

### `app/src/androidTest/` — instrumented test

| File | Purpose |
|---|---|
| `SettingsActivitySmokeTest.kt` | Starts `SettingsActivity` on a real device to catch manifest, resource and framework-integration failures a JVM model cannot reproduce. Requires an attached device and is **not** part of the gate. |

## `.github/` — automation

| Path | Purpose |
|---|---|
| `workflows/ci.yml` | Runs the same `verify` gate as locally on every push and pull request to `main`, with Gradle caching, and uploads lint, test and coverage reports as artifacts. |
| `workflows/release.yml` | Fires on `v*` tags: verifies the tag matches `versionName`, decodes the keystore from secrets, builds and signs the release APK, publishes it as `thor-back-button-hero-v<versionName>.apk`, and deletes the keystore afterwards. |
| `dependabot.yml` | Weekly update checks for Gradle dependencies and GitHub Actions. |
| `ISSUE_TEMPLATE/bug_report.yml` | Structured bug-report form that collects the device state a dual-screen gesture bug needs: which app was on which screen, the attempted gesture, relevant settings, and logcat. |

## `.githooks/` — local quality gates

Activated through `core.hooksPath`, which the `installGitHooks` Gradle task sets on the
first build, so they work right after a clone without a manual step.

| File | Purpose |
|---|---|
| `pre-commit` | Resolves JDK 17, then compiles and runs the unit tests — fast feedback on every commit. |
| `pre-push` | Runs the full gate via `tools/verify.sh`. |

## `tools/`

| File | Purpose |
|---|---|
| `verify.sh` | **The gate.** Resolves JDK 17 itself (it is not on `PATH` on the author's machine) and runs `./gradlew verify`. Use this rather than a bare Gradle call. |

## `docs/`

| File | Purpose |
|---|---|
| `FAQ.md` | Reader-facing answers: device compatibility beyond the tested Thor, how the update check behaves, and the privacy and trust questions an accessibility service invites. Linked from the README's Compatibility section. |
| `ARCHITECTURE.md` | Components, their responsibilities, how a gesture flows through them, and the display-targeting model. |
| `TESTING.md` | The test concept and every quality gate, plus what is deliberately not automated. |
| `MANUAL-TESTING.md` | Severity tiers S0–S4 and the step-by-step device procedures each one requires before pushing. |
| `PROJECT-STRUCTURE.md` | This file. |
| `MAINTENANCE.md` | Setup, building, releasing, dependency policy, and recipes for common changes. |
| `PERFORMANCE.md` | Measured hot-path costs, the optimizations tried and why they were not kept, and how to measure on the device. |
| `gesture-explainer.html` | Interactive page demonstrating each gesture on a device diagram. Fully self-contained: `thor-base.png` is embedded as a base64 data URI so the single file can be downloaded and opened anywhere, offline. The master image stays in `assets/` — re-embed it there if it ever changes. |

### `docs/assets/`

Every image the documentation and the app identity are built from. The shipped app icons
live in `app/src/main/res/` and are generated from the masters here — these files are not
read at build time.

| File | Purpose |
|---|---|
| `Back Button Hero 1.4.0.mp4` | Source of the 60 fps video, recorded frame by frame from `gesture-explainer.html?autoplay`. The README and the landing page embed the copy hosted on GitHub. |
| `thor-base.png` | Device outline used by the explainer and the animation. |
| `launcher-icon.png` | The working export the density variants in `res/` were produced from. |
| `launcher-icon-original.png` | The unscaled icon master. |
| `screenshot-*.png` | Settings screenshots used by the README, including D-pad assignments and per-action screen targeting. |
