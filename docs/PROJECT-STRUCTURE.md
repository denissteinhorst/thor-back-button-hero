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
| `gradle/wrapper/gradle-wrapper.properties` | Pins the Gradle version (8.7). |
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
| `SwitcherService.kt` | 306 | The accessibility service and runtime coordinator. Intercepts Back and the four shoulder keycodes, owns the Android side effects (global actions, display-aware launches, screenshots, screen swap), captures switcher thumbnails, and creates `SwitcherOverlay`. Delegates Back-key *decisions* to `BackGestureMachine`. |
| `BackGestureMachine.kt` | 136 | The Back-press state machine, free of Android side effects: single tap vs. double tap vs. hold vs. tap-then-hold. Clock, scheduling and actions enter through small interfaces so the timing logic is unit-testable on the JVM. |
| `DoubleTapDetector.kt` | 18 | Standalone press-interval tracker. Fires when two presses fall inside the configured window. Uses `0L` as a "no previous press" sentinel — relevant when writing tests. |
| `AccessibilityAppSource.kt` | 89 | The only source of "which apps are running". A normal app cannot read other apps' tasks, so it reconstructs a most-recently-used list per display from the windows the accessibility service observes, filters a fixed ignore-set of launcher/system/OEM packages, and caches thumbnails in memory. |
| `RunningApp.kt` | 12 | The `RunningApp` data class (package, label, icon, thumbnail) and the `AppSource` interface that `AccessibilityAppSource` implements. |
| `SwitcherOverlay.kt` | 207 | The bottom-screen app switcher, drawn as a `TYPE_ACCESSIBILITY_OVERLAY` window bound to a specific display. Scroll row of cards, "Close all" with a both-screens toggle, and the empty state. Built from framework Views in code. |
| `CardTouch.kt` | 45 | Touch arbitration for a switcher card: tap opens, upward swipe past a threshold closes, horizontal drags are handed to the scroller. Extracted so the gesture maths is testable without a window. |
| `SettingsActivity.kt` | 339 | The launcher UI, assembled programmatically. Reads and writes everything through `Prefs`, reports accessibility-service health with a "repair service" flow, and triggers the update check. |
| `SettingsUiKit.kt` | 319 | The hand-built UI toolkit behind the settings screen: `dp()`, service-status helpers (`ServiceStatus`, `currentServiceStatus()`), and the `SettingsPage` builder that produces cards, rows, sliders and choosers. |
| `Prefs.kt` | 141 | `SharedPreferences` wrapper: schema, defaults, typed accessors, and the one-time **migrations** in its `init` block. The single place where stored-preference changes belong. |
| `ShoulderButton.kt` | 11 | Enum of the four shoulder buttons, mapping each to its preference key and Android keycode. |
| `UpdateChecker.kt` | 141 | Polls the GitHub Releases API with `HttpURLConnection` and manual JSON parsing (no HTTP or JSON library), compares the semver tag against the installed `versionName`, and posts a notification. No in-app APK install by design. |

#### Resources — `app/src/main/res/`

| Path | Purpose |
|---|---|
| `values/strings.xml` | English strings (**87**, source of truth). |
| `values-de/strings.xml` | German translations (**86**; the difference is `app_name`, which is `translatable="false"` — this gap is correct and asserted by a test). |
| `values/colors.xml` | The few named colors used by the hand-built UI. |
| `xml/accessibility_config.xml` | The accessibility-service declaration: requested event types and the capability flags the app depends on — key-event filtering, interactive-window retrieval, gesture performing and screenshot taking. |
| `drawable/ic_clear_all.xml` | Vector icon for the switcher's "Close all" button. |
| `drawable-*/ic_launcher_foreground.png` | Launcher foreground at five densities; also reused as the notification icon. |
| `mipmap-*/ic_launcher.png` | Launcher icon at five densities. |
| `mipmap-anydpi-v26/ic_launcher.xml` | Adaptive-icon definition for API 26+. |

There are **no layout XML files** and no Compose: every view is constructed in Kotlin.

### `app/src/test/` — JVM tests (Robolectric + JUnit)

The automated safety net. 15 test classes, **87 tests**. Run by the gate.

| File | Purpose |
|---|---|
| `SmokeTest.kt` | Minimal proof the test infrastructure itself works. |
| `PrefsDefaultsTest.kt` | Every default value and typed accessor in `Prefs`. |
| `PrefsMigrationTest.kt` | The one-time preference migrations and `resetToDefaults()` — the highest-risk code for existing installs. |
| `DoubleTapDetectorTest.kt` | Press-interval behavior, including the clock-at-zero sentinel. |
| `BackGestureMachineTest.kt` | Every branch of the Back decision logic with a controlled clock and scheduler. |
| `CardTouchTest.kt` | Tap vs. swipe-up vs. horizontal-drag arbitration. |
| `AccessibilityAppSourceTest.kt` | Recent-app bookkeeping and the thumbnail cache, within what Robolectric can reach. |
| `SettingsUiKitTest.kt` | The UI-kit helpers (`dp`, service-status mapping). |
| `UpdateCheckerParseTest.kt` | Release-payload parsing, semver ordering, the legacy tag forms, and the inputs that must be rejected. |
| `ResourceIntegrityTest.kt` | EN/DE string parity and resource consistency. |
| `SettingsActivitySnapshotTest.kt` | View-tree snapshots of the settings page across locale and theme. |
| `ShoulderRowSnapshotTest.kt` | View-tree snapshots of every shoulder-row state. |
| `SettingsDialogSnapshotTest.kt` | View-tree snapshots of three dialogs. |
| `AccessibilityInventoryTest.kt` | Freezes an inventory of every clickable row's accessibility properties. |
| `support/ViewTree.kt` | Renders a View hierarchy to deterministic text — the snapshot format. Platform-independent, unlike pixel screenshots. |
| `support/Golden.kt` | Compares against a golden file, or rewrites it when `UPDATE_GOLDENS=1`. See the golden rule in [`TESTING.md`](TESTING.md). |
| `support/ViewTreeSmokeTest.kt` | Verifies the snapshot renderer itself. |

| Path | Purpose |
|---|---|
| `resources/robolectric.properties` | Pins Robolectric to `sdk=34`, matching `compileSdk`. |
| `resources/golden/*.txt` | The **12** golden snapshots: 3 settings pages, 5 shoulder-row states, 3 dialogs, 1 accessibility inventory. |

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
| `gesture-explainer.html` | Standalone page that illustrates the gestures. Loads its artwork from `assets/`, so open it from a clone rather than in isolation. |

### `docs/assets/`

Every image the documentation and the app identity are built from. The shipped app icons
live in `app/src/main/res/` and are generated from the masters here — these files are not
read at build time.

| File | Purpose |
|---|---|
| `gestures.gif` | The animation embedded in `README.md`. |
| `thor-base.png` | Device outline used by the explainer and the animation. |
| `launcher-icon.png` | The working export the density variants in `res/` were produced from. |
| `launcher-icon-original.png` | The unscaled icon master. |
