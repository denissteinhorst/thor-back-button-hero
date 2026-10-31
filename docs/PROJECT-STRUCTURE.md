# Project structure

Every tracked file and folder, and what it is for. Repository-root paths throughout.
Generated artifacts (`build/`, `.gradle/`, `local.properties`, `release.keystore`) are
gitignored and therefore absent here.

## Top level

| Path | Purpose |
|---|---|
| `README.md` | The user-facing page: what the app does, gestures, shortcuts, defaults, install steps, permissions and privacy, troubleshooting. |
| `AGENTS.md` | The shared, owner-maintained instructions for every coding agent: invariants, working agreement and pointers into `docs/`. |
| `CLAUDE.md` | Working instructions for Claude Code — gate command, expected results, toolchain traps, release and signing-key procedures. |
| `FEEDBACK.md` | Community feedback on 1.3.0 and the follow-up status of each topic. Context, not a queue. |
| `IMPROVMENTS.md` | Audit notes on memory, performance, code quality and docs. Context, not a queue. |
| `WIP_BOTTOMSCREEN_OVERWRITES.md` | Investigation note on a rejected and reverted attempt to raise the app's windows above AYN's assistant. Read before retrying. |
| `art/settings-icons/` | SVG sources of the settings navigation icons that became the `ic_settings_*` vector drawables. |
| `site/` | The landing page (`index.html`, `style.css`, `script.js`, `assets/`), published through GitHub Pages. `site/gesture-explainer.html` is a symlink to the docs copy. |
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
collaborate. Files that run as root `app_process` entry points are marked *(root)*.

**Service, gestures and settings**

| File | Lines | Purpose |
|---|---|---|
| `SwitcherService.kt` | 1761 | The accessibility service and runtime coordinator. Intercepts Back and the shoulder keycodes, receives Back + D-pad from `PadHelper`/`DpadOverlay`, owns the Android side effects (global actions, display-aware launches, screenshots, controller focus, task-preserving screen swap, Active App Control, exceptions at runtime), captures switcher thumbnails, and creates `SwitcherOverlay`. Delegates Back-key *decisions* to `BackGestureMachine`. |
| `BackGestureMachine.kt` | 158 | The Back-press state machine, free of Android side effects: single tap vs. double tap vs. hold vs. tap-then-hold, including blocked gestures from app exceptions. Clock, scheduling and actions enter through small interfaces so the timing logic is unit-testable on the JVM. |
| `DoubleTapDetector.kt` | 18 | Standalone press-interval tracker. Fires when two presses fall inside the configured window. Uses `0L` as a "no previous press" sentinel — relevant when writing tests. |
| `PhysicalKeyTracker.kt` | 31 | Collapses duplicate shoulder-key events from the Thor's two mirrored controller input devices into one physical press/release cycle. |
| `ServiceToggleGesture.kt` | 57 | The activation shortcut: observes a configured button pair without consuming it and toggles interception after the hold time. |
| `KeyInterceptionGate.kt` | 29 | Keeps each key DOWN/UP pair on its original side of a runtime interception change, so pausing never strands a half-consumed press. |
| `ShoulderButton.kt` | 42 | The `ComboButton` interface and its two enums: the four shoulder buttons (preference key, Android keycode) and the four D-pad directions (preference key, default action, hat-axis mapping). |
| `Prefs.kt` | 385 | `SharedPreferences` wrapper: schema, defaults, typed accessors, atomic application of change plans, and the one-time **migrations** in its `init` block. The single place where stored-preference changes belong. |
| `AppExceptions.kt` | 58 | `ExceptionControl` columns and `AppException` rules, plus their stored line format. |
| `SettingsActivity.kt` | 907 | The launcher UI, assembled programmatically. Reads and writes everything through `Prefs`, reports accessibility-service health with a "repair service" flow, triggers the update check, offers the page and whole-app defaults/unbind actions, and hosts the indicator-appearance popup (also opened directly from the focus pictogram). |
| `SettingsUiKit.kt` | 622 | The hand-built UI toolkit behind the settings screen: `dp()`, service-status helpers (`ServiceStatus`, `currentServiceStatus()`), the `SettingsPage` builder that produces cards, rows, sliders and choosers, and `positionGrid()`, the 3×3 dot picker on a 16:9 outline. |
| `SettingsNavigation.kt` | 221 | Adaptive six-page navigation (left panel from 720 dp, menu dialog below), top bar, lazy page cache and scroll state. |
| `SettingsChanges.kt` | 97 | Immutable settings snapshots and scoped defaults/unbind change plans. |
| `ExceptionSettingsPage.kt` | 497 | Per-app exception editor: app search, allow/block start choice, twelve-column matrix with diagonal labels, active switch, trash and all-box. |
| `UpdateChecker.kt` | 141 | Polls the GitHub Releases API with `HttpURLConnection` and manual JSON parsing (no HTTP or JSON library), compares the semver tag against the installed `versionName`, and posts a notification. No in-app APK install by design. |

**Recent apps and the switchers**

| File | Lines | Purpose |
|---|---|---|
| `AccessibilityAppSource.kt` | 100 | The only source of "which apps are running". A normal app cannot read other apps' tasks, so it reconstructs a most-recently-used list per display from the windows the accessibility service observes, filters a fixed ignore-set of launcher/system/OEM packages, and caches thumbnails in memory. |
| `RunningApp.kt` | 15 | The `RunningApp` data class (package, label, icon, thumbnail) and the `AppSource` interface that `AccessibilityAppSource` implements. |
| `SwitcherOverlay.kt` | 494 | The bottom-screen Recents, drawn as a `TYPE_ACCESSIBILITY_OVERLAY` window bound to a specific display. Carousel of cards, memory readout, Screenshot and Close all actions, empty state and controller navigation. Built from framework Views in code. |
| `CardTouch.kt` | 45 | Touch arbitration for a switcher card: tap opens, upward swipe past a threshold closes, horizontal drags are handed to the scroller. Extracted so the gesture maths is testable without a window. |
| `RecentsCarousel.kt` | 104 | Shared finger and controller paging with Quickstep's settling curve and velocity timing. |
| `RecentsCardStyle.kt` | 72 | Card dimensions and corner radius read from the installed launcher, with local defaults. |
| `RecentsTheme.kt` | 62 | The installed launcher's light/dark colors and dynamic system palette. |
| `RecentsBadges.kt` | 62 | The OEM's own lock and display-indicator drawables and their geometry, loaded from the launcher package. |
| `RecentsEmptyView.kt` | 80 | Quickstep's empty-state icon and "No recent items" text, centered in the card area. |
| `RecentsMemory.kt` | 33 | Available/total memory exactly as the OEM launcher computes it. |
| `RecentsSelection.kt` | 25 | Decides which known task a native Recents click or task-to-front event may be bound to. |
| `RecentsThumbnailCache.kt` | 16 | Byte-bounded LRU of system task snapshots (12 MiB). |
| `RecentsTaskState.kt` | 39 | Parsed task status (display, lock, task id, movability) from the system's recent-task query. |
| `RecentsStateReader.kt` | 124 | *(root)* Reads system task/status data and exports task snapshots into private cache files. |
| `RecentTaskQuery.kt` | 23 | Recent-task query that includes tasks excluded from native Recents, such as companion apps. |
| `RecentsTaskLauncher.kt` | 114 | *(root)* Resumes an existing task on the requested screen without relaunching it. |
| `TaskMovePolicy.kt` | 34 | Rules for task moves: protected tasks and which app stays in front. |
| `SwapReturn.kt` | 49 | Remembers the last swap and moves the partner app back when one of the two is closed. |
| `TopRecentsFocus.kt` | 46 | *(root)* Completes AYN's paused Overview handoff so native controller navigation works in the top Recents. |
| `TopRecentsNavigation.kt` | 45 | Buffers D-pad presses while top Recents initializes, then releases them. |
| `TopRecentsState.kt` | 48 | The small subset of AYN Launcher3's dump needed to know the Overview state and card geometry. |
| `TaskRemover.kt` | 57 | *(root)* Removes recent tasks like Recents' "Clear all". |

**Frontends and bottom-screen panels**

| File | Lines | Purpose |
|---|---|---|
| `BottomPanels.kt` | 141 | Serializes capture, hide and show of full-screen frontend windows on the bottom display so a card always belongs to its panel. |
| `PanelWindow.kt` | 66 | A panel as a window (id, owner, display, hidden), and the firmware dump parsing that finds it. |
| `PanelConnection.kt` | 118 | Authenticated uid-0 socket to `PanelWindowHost`, started through `PService`. |
| `PanelWindowHost.kt` | 238 | *(root)* Holds the panels' window binders and hides/restores them; an independent peer restores them if this process dies. |
| `FrontendParking.kt` | 188 | Parks a frontend's bottom activity on a private virtual display so it keeps running instead of being stopped. |
| `ParkingPolicy.kt` | 17 | When to park, and the loop guard that leaves a frontend like iiSU alone. |
| `TaskParker.kt` | 95 | *(root)* Moves the task onto the parking display and back, and reads running root tasks. |

**Shortcut actions, controller and D-pad**

| File | Lines | Purpose |
|---|---|---|
| `AppControl.kt` | 158 | The Active App Control action: `AppControl.targets` resolves the foreground apps of the chosen screen (top, bottom, both) without ever returning a launcher, system UI or this app, and `AppControlPrompt` is the small focusable overlay behind "Ask" and the optional force-close confirmation. |
| `ControllerFocus.kt` | 85 | Routes the controller to the top or bottom screen with the same `PService` sequence as AYN's own tool (property, setting, `keyevent 60` without `-d`), and resolves the toggle target from `Settings.System`. |
| `FocusIndicatorOverlay.kt` | 145 | Brief top-screen accessibility overlay with a handheld pictogram showing which screen has the controller. Nine positions, configurable display time, optional tap-through to the settings. |
| `PadHelper.kt` | 233 | Starts the native root helper through `PService`, accepts only a uid-0 peer on its abstract socket, and turns its reports into D-pad presses, navigation modes and the exception mask. See [`HELPER.md`](HELPER.md). |
| `DpadOverlay.kt` | 81 | Fallback without the helper: a focusable 1×1 overlay that exists only while Back is held and catches the D-pad's hat events, at the cost of the foreground app's focus. |
| `LevelControl.kt` | 69 | The volume and brightness actions for both screens, the top or the bottom one. |
| `LevelSteps.kt` | 17 | Pure step arithmetic for volume and brightness (15 equal brightness steps). |
| `BrightnessControl.kt` | 55 | *(root)* Steps the brightness of one or more displays via `IDisplayManager`. |
| `SystemScreenshot.kt` | 148 | *(root)* Short-lived entry point and Binder handshake for Android's native, display-targeted screenshot flow. |
| `ScreenshotSaver.kt` | 40 | Fallback: saves a screenshot of one display to `Pictures/Screenshots` through MediaStore when the native flow is unavailable. |
| `PService.kt` | 120 | Bridge to AYN's root daemon `pservice` (`PServerBinder`), and `runEntryPoint` for root `app_process` entry points. |
| `Shell.kt` | 15 | Validation and quoting for everything that reaches a root shell. |

#### Native helper — `app/src/main/cpp/`

| File | Lines | Purpose |
|---|---|---|
| `padhelper.c` | 411 | The root input helper: hides AYN's pad behind an identical uinput clone, captures D-pad for Back combos and the bottom switcher, and captures only Up for top-Recents card dismissal. Kernel UAPI and libc only; compiled by the Gradle task `buildPadHelper` with the NDK's clang and shipped as `lib/arm64-v8a/libpadhelper.so`. See [`HELPER.md`](HELPER.md). |

#### Resources — `app/src/main/res/`

| Path | Purpose |
|---|---|
| `values/strings.xml` | English strings (**237**, source of truth). |
| `values-de/strings.xml` | German translations (**236**; the difference is `app_name`, which is `translatable="false"` — this gap is correct and asserted by a test). |
| `values/colors.xml`, `values-night/colors.xml` | The few named colors used by the hand-built UI, with a night variant. |
| `xml/accessibility_config.xml` | The accessibility-service declaration: requested event types and the capability flags the app depends on — key-event filtering, interactive-window retrieval, gesture performing and screenshot taking. |
| `drawable/ic_clear_all.xml`, `ic_screenshot.xml` | Vector icons for the switcher's system-Recents-style actions. |
| `drawable/ic_settings_*.xml` | Vector icons of the settings navigation and top bar (SVG sources in `art/settings-icons/`). |
| `drawable-nodpi/focus_top_active.webp`, `focus_bottom_active.webp` | The two controller-focus pictograms (top or bottom screen active), with the area outside the handheld transparent. |
| `drawable-*/ic_launcher_foreground.png` | Launcher foreground at five densities; also reused as the notification icon. |
| `mipmap-*/ic_launcher.png` | Launcher icon at five densities. |
| `mipmap-anydpi-v26/ic_launcher.xml` | Adaptive-icon definition for API 26+. |

There are **no layout XML files** and no Compose: every view is constructed in Kotlin.

### `app/src/test/` — JVM tests (Robolectric + JUnit)

The automated safety net. 56 test classes, **332 tests**. Run by the gate. By area:

| Area | Files |
|---|---|
| Preferences | `PrefsDefaultsTest` (every default and typed accessor), `PrefsMigrationTest` (the one-time migrations, factory shoulder defaults and their update safety, reset and unbind — the highest-risk code for existing installs), `DpadPrefsTest` |
| Gestures and keys | `BackGestureMachineTest` (every branch, controlled clock and scheduler), `DoubleTapDetectorTest` (including the clock-at-zero sentinel), `CardTouchTest`, `PhysicalKeyTrackerTest`, `ServiceToggleGestureTest`, `KeyInterceptionGateTest`, `LevelStepsTest` |
| Settings UI and changes | `SettingsActivitySnapshotTest`, `ShoulderRowSnapshotTest`, `SettingsDialogSnapshotTest`, `AccessibilityInventoryTest` (View-tree snapshots against the goldens), `SettingsNavigationTest`, `SettingsUiKitTest`, `SettingsActionsTest`, `SettingsChangesTest`, `SettingsChangeDialogTest`, `ServiceToggleSettingsTest`, `ExceptionSettingsPageTest`, `AppExceptionTest` |
| Shortcut actions | `AppControlTest`, `ControllerFocusTest` |
| Recent apps and Recents | `AccessibilityAppSourceTest`, `PanelCardsTest`, `RecentsBadgesTest`, `RecentsCardStyleTest`, `RecentsCarouselTest`, `RecentsDisplayBadgeTest`, `RecentsEmptyViewTest`, `RecentsMemoryTest`, `RecentsSelectionTest`, `RecentsTaskStateTest`, `RecentsThemeTest`, `RecentsThumbnailCacheTest`, `RecentTaskQueryTest`, `SwitcherPreviewTest`, `SwitcherCloseAllTest`, `SwitcherTaskSyncTest`, `TopRecentsNavigationTest`, `TopRecentsStateTest`, `TopRecentsBackTest` |
| Tasks, swap, frontends | `TaskMovePolicyTest`, `SwapReturnTest`, `ParkingPolicyTest`, `BottomPanelsTest`, `PanelWindowsTest` |
| Pad helper | `PadHelperLifecycleTest`, `PadNavigationModeTest` |
| Other | `ShellTest`, `UpdateCheckerParseTest` (release parsing, semver ordering, legacy tags, rejected inputs), `ResourceIntegrityTest` (EN/DE string parity), `SmokeTest` |
| Support | `support/ViewTree.kt` renders a View hierarchy to deterministic text — the snapshot format, platform-independent unlike pixel screenshots. `support/Golden.kt` compares against a golden file, or rewrites it when `UPDATE_GOLDENS=1` (see the golden rule in [`TESTING.md`](TESTING.md)). `support/ViewTreeSmokeTest.kt` verifies the renderer itself. |

| Path | Purpose |
|---|---|
| `resources/robolectric.properties` | Pins Robolectric to `sdk=34`, matching `compileSdk`. |
| `resources/golden/*.txt` | The **44** golden snapshots: six settings pages across locales/themes/window widths, shoulder/D-pad states, change-preview dialogs and accessibility inventory. |

### `app/src/androidTest/` — instrumented tests

| File | Purpose |
|---|---|
| `SettingsActivitySmokeTest.kt` | Starts `SettingsActivity` on a real device to catch manifest, resource and framework-integration failures a JVM model cannot reproduce. |
| `RecentsMemoryDeviceTest.kt` | Checks on the device that the installed launcher's memory helper is reachable. |

Both require an attached device and are **not** part of the gate.

## `.github/` — automation

| Path | Purpose |
|---|---|
| `workflows/ci.yml` | Runs the same `verify` gate as locally on every push and pull request to `main`, with Gradle caching, and uploads lint, test and coverage reports as artifacts. |
| `workflows/pages.yml` | Publishes `site/` (and the explainer it links to) to GitHub Pages on pushes to `main` that touch them. |
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
| `ARCHITECTURE.md` | Components, their responsibilities, how a gesture flows through them, the display-targeting model, bottom panels, frontend parking and Recents routing. |
| `HELPER.md` | The root input helper: why the D-pad needs it, what it touches, how the pad is always restored. Mandatory reading before changing that path. |
| `THOR-RECENTS.md` | Reference for the AYN launcher's system Recents and how the bottom Recents copies its look, memory readout and previews. |
| `TESTING.md` | The test concept and every quality gate, plus what is deliberately not automated. |
| `MANUAL-TESTING.md` | Severity tiers S0–S4 and the step-by-step device procedures each one requires before pushing. |
| `PROJECT-STRUCTURE.md` | This file. |
| `MAINTENANCE.md` | Setup, building, releasing, dependency policy, and recipes for common changes. |
| `PERFORMANCE.md` | Measured hot-path costs, the optimizations tried and why they were not kept, and how to measure on the device. |
| `release-notes/` | The release text per version (`v1.4.0.md`); the landing page renders its headings, bullets and bold text. |
| `hardening/` | The historical, German-language pre-release hardening plan: its 38 step files, reports and checklists. Frozen record, not current instructions. |
| `gesture-explainer.html` | Interactive page demonstrating each gesture on a device diagram. Fully self-contained: `thor-base.png` is embedded as a base64 data URI so the single file can be downloaded and opened anywhere, offline. The master image stays in `assets/` — re-embed it there if it ever changes. |

### `docs/assets/`

Every image the documentation and the app identity are built from. The shipped app icons
live in `app/src/main/res/` and are generated from the masters here — these files are not
read at build time.

| File | Purpose |
|---|---|
| `Back Button Hero 1.4.0.mp4` | Source of the 60 fps video (about 2.5 minutes), recorded frame by frame from `gesture-explainer.html?autoplay`. The README and the landing page link to the copy published with the site, `site/assets/back-button-hero-1.4.0.mp4`; keep the two files identical. |
| `thor-base.png` | Device outline used by the explainer and the animation. |
| `launcher-icon.png` | The working export the density variants in `res/` were produced from. |
| `launcher-icon-original.png` | The unscaled icon master. |
| `screenshot-*.png` | Older settings screenshots (1.3.0 layout). Nothing references them any more; the README and the landing page use `site/assets/screenshots/`. |
