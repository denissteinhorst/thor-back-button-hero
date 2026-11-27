# Project structure

Every tracked file and folder, and what it is for. Repository-root paths throughout.
Generated artifacts (`build/`, `.gradle/`, `local.properties`, `release.keystore`) are
gitignored and therefore absent here.

## Top level

| Path | Purpose |
|---|---|
| `README.md` | The user-facing page: what the app does, gestures, shortcuts, defaults, install steps, permissions and privacy, troubleshooting. |
| `AGENTS.md` | The shared, owner-maintained instructions for every coding agent: invariants, working agreement and pointers into `docs/`. |
| `CLAUDE.md` | Claude-specific additions: talking to the owner, the owner's machine, the device workflow and handoff format, commit rules. Technical detail lives in `docs/`. |
| `art/settings-icons/` | SVG sources of the settings navigation icons that became the `ic_settings_*` vector drawables. |
| `site/` | The landing page (`index.html`, `style.css`, `script.js`, `assets/`), published through GitHub Pages. `guide.html` is the shared content source and script-free fallback; `hotkey-layers.js` renders the homepage’s Back/Home layers. See [`WEBSITE.md`](WEBSITE.md). `site/app-simulation.html` is a symlink to the docs copy. `site/gesture-explainer.html` redirects the former name there. `site/thor-ad-v2/` contains the final 2.0 clip; `site/model-editor/` contains the standalone 3D editor and shared model/renderer. |
| `LICENSE` | 0BSD. Chosen deliberately: the release APK ships no third-party code beyond the Kotlin stdlib (see [`BUILD-AND-RELEASE.md`](BUILD-AND-RELEASE.md)). |
| `.gitignore` | Excludes build output, IDE files, `local.properties`, and — importantly — `*.keystore`/`*.jks` so a signing key can never be committed. |
| `settings.gradle.kts` | Gradle settings: declares the single `:app` module and the plugin/dependency repositories. |
| `build.gradle.kts` | Root build file. Declares the AGP/Kotlin plugin versions, the **`verify`** aggregate task (the one quality gate), and the `installGitHooks` task. |
| `gradle.properties` | JVM and Gradle flags for the build (heap, AndroidX, caching). |
| `gradlew`, `gradlew.bat` | Gradle wrapper launchers. Always build through these, never a system Gradle. |
| `gradle/wrapper/gradle-wrapper.properties` | Pins the Gradle version (9.9.3). |
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

Packages by feature below `dev.steinhorst.backbuttonhero` (one Gradle module). The file tables
below are grouped by role, not by package; the package of each file is listed here:

| Package | Contents |
|---|---|
| root (frozen) | `SwitcherService`, `SettingsActivity`, `UpdateInstaller`, `UpdateChecker` and the ten root `app_process` entry points. Their names are read by Android, stored settings or root scripts, so they never move ([decision 0001](decisions/0001-frozen-external-contracts.md)). |
| `core` | `Shell`, `PService`, `RunningApp`, `AccessibilityAppSource` |
| `prefs` | `Prefs` (schema, migrations, transactions), backups, change plans, app exceptions and groups |
| `input` | Back gesture machine, double tap, key tracking and interception gate, activation toggle, shoulder/stick/face/Home buttons |
| `actions` | Action groups, Active App Control, closing apps, last app, power menu, status, controller focus, AYN controls |
| `recents` | Bottom switcher overlay, Recents styling and state, top Recents navigation, task move and parking policies |
| `panels` | Bottom-screen panel capture, windows and the connection to the root window host |
| `pad` | Root pad helper client, D-pad overlay, level control and repeat, gyro |
| `macro` | Macro model, recorder, editor and widgets |
| `capture` | Screen recording, screenshots, push-to-talk and the voice chain, recording indicator |
| `overlay` | Notice and focus-indicator overlays, and their shared top-display window helper |
| `settings` | Settings navigation, UI kit, bindings overview, choosers, welcome sheet, exception and gyro dialogs |

Tests live in the same package as the class they cover; cross-feature tests and the snapshot
helpers (`support/`) stay in the root test package.

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for how they
collaborate. Files that run as root `app_process` entry points are marked *(root)*.

**Service, gestures and settings**

| File | Lines | Purpose |
|---|---|---|
| `ComboRouter.kt` | 76 | Back + shoulder, Home/Start/Select or face button: runs that button's action and swallows its event; unassigned buttons stay the game's. |
| `DirectionalRouter.kt` | 117 | Back + D-pad and Back + stick from the helper or the focus overlay: run, arm or leave to the game, and when an armed focus or app switch commits. |
| `StickClickRouter.kt` | 77 | L3/R3 while Back is held: ours only when the stick has a click action; both mirrored devices' events are consumed as one press. |
| `HomeAsBack.kt` | 63 | The pad's Home key as Back: the first key down owns the one virtual Back until both are released. |
| `ActionChooser.kt` | 279 | The hotkey action list with "Not set", A–Z entries and a search that also finds options inside groups and AYN tiles. |
| `GyroSettingsDialog.kt` | 414 | The gyro hotkey's setup popup with a live ball preview beside every switch and slider. |
| `WelcomeSheet.kt` | 202 | First-start sheet and `startSetup`, the defaults it applies, and whether settings are already customized. |
| `StatusSummary.kt` | 19 | The arithmetic behind the battery status notice, apart from the battery service. |
| `NoticeOverlay.kt` | 131 | The service's short messages as one toast-like window that only changes its text, so background rate limits never drop one; muted while Do Not Disturb hides pop-ups. |
| `SwitcherService.kt` | 1759 | The accessibility service and runtime coordinator. Intercepts Back and the shoulder keycodes, receives Back + D-pad from `PadHelper`/`DpadOverlay`, owns the Android side effects (global actions, display-aware launches, screenshots, controller focus, task-preserving screen swap, Active App Control, exceptions at runtime), captures switcher thumbnails, and creates `SwitcherOverlay`. Delegates Back-key *decisions* to `BackGestureMachine`. |
| `BackGestureMachine.kt` | 158 | The Back-press state machine, free of Android side effects: single tap vs. double tap vs. hold vs. tap-then-hold, including blocked gestures from app exceptions. Clock, scheduling and actions enter through small interfaces so the timing logic is unit-testable on the JVM. |
| `DoubleTapDetector.kt` | 18 | Standalone press-interval tracker. Fires when two presses fall inside the configured window. Uses `0L` as a "no previous press" sentinel — relevant when writing tests. |
| `PhysicalKeyTracker.kt` | 31 | Collapses duplicate shoulder-key events from the Thor's two mirrored controller input devices into one physical press/release cycle. |
| `ServiceToggleGesture.kt` | 113 | The activation shortcut: observes a configured button pair and toggles interception after the hold time; only Back + Home consumes a paired press and holds back a lone one until release. |
| `KeyInterceptionGate.kt` | 29 | Keeps each key DOWN/UP pair on its original side of a runtime interception change, so pausing never strands a half-consumed press. |
| `ShoulderButton.kt` | 146 | The `ComboButton` interface and its key and D-pad enums: the four shoulder buttons, Home/Start/Select (`AndroidButton`) and the face buttons by printed letter (`FaceButton`, mapped through the controller-style switch, each with its fixed place on the diamond, `FacePosition`), each with preference key and Android keycode, plus the four D-pad directions and the four Back gestures. |
| `PromptKeys.kt` | 18 | The controller keys of the prompts and switchers after Android's convention (`BUTTON_A` confirms, `BUTTON_B` cancels, `BUTTON_X` closes all) and where the style switch puts each one physically. |
| `Prefs.kt` | 385 | `SharedPreferences` wrapper: schema, defaults, typed accessors, atomic application of change plans, and the one-time **migrations**, which its `init` runs from `Migrations.kt`. The single place where stored-preference changes belong. |
| `Action.kt` | 100 | Typed hotkey actions (`Action`) and `ActionCodec`, the only mapping between them and the stored strings; unknown `@` values survive unchanged. Not yet used by callers. |
| `Migrations.kt` | 190 | The one-time rewrites of stored settings, each behind its versioned flag, in the order `Prefs`' `init` runs them. |
| `MacroStore.kt` | 45 | The macro library and the hotkeys' references into it; `Prefs` delegates to it and keeps whole-settings transactions. |
| `AppExceptions.kt` | 58 | `ExceptionControl` columns and `AppException` rules, plus their stored line format. |
| `SettingsActivity.kt` | 907 | The launcher UI, assembled programmatically. Reads and writes everything through `Prefs`, reports accessibility-service health with a "repair service" flow, triggers the update check, offers the page and whole-app defaults/unbind actions, and hosts the indicator-appearance popup (also opened directly from the focus pictogram). |
| `SettingsUiKit.kt` | 622 | The hand-built UI toolkit behind the settings screen: `dp()`, service-status helpers (`ServiceStatus`, `currentServiceStatus()`), the `SettingsPage` builder that produces cards, rows, sliders and choosers, and `positionGrid()`, the 3×3 dot picker on a 16:9 outline. |
| `BindingCard.kt` | 326 | The card of one binding in one layer: its action row, the option rows built on first need and hidden afterwards, and `update()`, which re-reads the binding and changes those views; the choosers stay in the activity behind `BindingCard.Host`. |
| `BindingDescriber.kt` | 406 | Every text the settings derive from a binding and every list a binding chooses from: action list, search words, option names, screens, modes, targets, qualities, ducks, indicator positions, gyro and macro summaries. |
| `ChangeDescriber.kt` | 158 | The "label: old → new" lines of the defaults, preset, unbind and restore previews. |
| `BackupFlow.kt` | 126 | Saving and restoring the settings file: pickers, off-main-thread read and write, refusals and the version hint before the change preview. |
| `UpdateFlow.kt` | 154 | The manual update check: busy row, result dialogs, the release-only download into Android's installer; `UpdateCheck` is the seam the activity wires to `UpdateChecker` and tests to a fake. |
| `SettingsNavigation.kt` | 469 | Adaptive nine-page navigation (a non-scrolling icon rail under the app logo from 720 dp, whose icons lay the full panel over the page until a tap or drag on the page closes it; menu dialog below), top bar (a bare page title from 720 dp, floating with the service chip on the Overview), lazy page cache and scroll state. |
| `BindingsOverview.kt` | 535 | The Overview page: a line drawing of the Thor's lower half, traced from the measured geometry of `thor-base.png`, with a leader line from each assigned control to its labels; tapping a part of the drawing (faintly tinted) opens its page, the labels only read. |
| `SettingsChanges.kt` | 97 | Immutable settings snapshots and scoped defaults/unbind change plans. |
| `ExceptionGroups.kt` | 41 | The four input groups of an exception and their controls. |
| `ExceptionGroupDialog.kt` | 288 | The modal of one group: glyph, name and switch per control, all-switches. |
| `ExceptionSettingsPage.kt` | 377 | Per-app exception editor: app search, allow/block start choice, one row per app with active switch, trash and four group buttons that open the group modal. |
| `UpdateInstaller.kt` | 139 | Streams the release APK into an Android `PackageInstaller` session, checks length and checksum, and lets Android confirm; its receiver opens that confirmation or names a refusal. |
| `UpdateChecker.kt` | 203 | Polls the GitHub Releases API with `HttpURLConnection` and manual JSON parsing (no HTTP or JSON library), compares the semver tag against the installed `versionName`, posts a notification, and names the failure (rate limit, connection, unusable answer). |

**Recent apps and the switchers**

| File | Lines | Purpose |
|---|---|---|
| `AccessibilityAppSource.kt` | 100 | The only source of "which apps are running". A normal app cannot read other apps' tasks, so it reconstructs a most-recently-used list per display from the windows the accessibility service observes, filters a fixed ignore-set of launcher/system/OEM packages, and caches thumbnails in memory. |
| `RunningApp.kt` | 15 | The `RunningApp` data class (package, label, icon, thumbnail) and the `AppSource` interface that `AccessibilityAppSource` implements. |
| `SwitcherOverlay.kt` | 531 | The bottom-screen Recents, drawn as a `TYPE_ACCESSIBILITY_OVERLAY` window bound to a specific display. Carousel of cards, memory readout, Screenshot and Close all actions, empty state, controller navigation and, while the controller drives it, the legend of its keys. Built from framework Views in code. |
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
| `TopRecentsFocus.kt` | 46 | *(root)* Completes AYN's paused Overview handoff so native controller navigation works in the top Recents. |
| `TopRecentsSession.kt` | 323 | The top Recents controller session: its flags, Quickstep state checks, focus priming, dismissal queue and closing Back, with stale worker results dropped by session token and focus version. |
| `TopRecentsNavigation.kt` | 45 | Buffers D-pad presses while top Recents initializes, then releases them. |
| `TopRecentsState.kt` | 48 | The small subset of AYN Launcher3's dump needed to know the Overview state and card geometry. |
| `TaskRemover.kt` | 57 | *(root)* Removes recent tasks like Recents' "Clear all". |
| `WindowTracker.kt` | 49 | The service's window-change handling: records both screens and debounces panel refresh, front-app capture and the parked reclaim. |
| `TaskOps.kt` | 99 | Closing apps with root or the unprivileged fallback, the memory report, and launching existing tasks. |
| `SwapCoordinator.kt` | 166 | Hold-to-swap and moving the app with controller focus to the other screen. |
| `BottomSwitcherController.kt` | 150 | The bottom switcher's open state, its task-state poll and task snapshots. |
| `TopRecentsLauncher.kt` | 150 | The launch phase of native top Recents: baseline, Quickstep poll and selection. |

**Frontends and bottom-screen panels**

| File | Lines | Purpose |
|---|---|---|
| `BottomPanels.kt` | 189 | Serializes capture, hide and show of full-screen frontend windows and AYN's assistant panel on the bottom display so a card always belongs to its panel. |
| `PanelWindow.kt` | 77 | A panel as a window (id, owner, display, hidden), and the firmware dump parsing that finds it. |
| `PanelConnection.kt` | 118 | Authenticated uid-0 socket to `PanelWindowHost`, started through `PService`. |
| `PanelWindowHost.kt` | 240 | *(root)* Holds the panels' window binders and hides/restores them; an independent peer restores them if this process dies. |
| `FrontendParking.kt` | 188 | Parks a frontend's bottom activity on a private virtual display so it keeps running instead of being stopped. |
| `ParkingPolicy.kt` | 17 | When to park, and the loop guard that leaves a frontend like iiSU alone. |
| `TaskParker.kt` | 95 | *(root)* Moves the task onto the parking display and back, and reads running root tasks. |

**Shortcut actions, controller and D-pad**

| File | Lines | Purpose |
|---|---|---|
| `ActionGroups.kt` | 96 | Single actions the settings show as one entry with a choice; storage keeps the single action. |
| `AppControlActions.kt` | 141 | Minimize, force-close or restart the app in front, close apps on chosen screens, standby; each may ask first. |
| `CloseApps.kt` | 28 | The displays a close-apps target covers. |
| `LastApp.kt` | 37 | Which app was used before the one in front on the controller's screen; pressing again flips back. |
| `LastAppAction.kt` | 69 | Brings that app back, as an existing task through root or a plain launch without it. |
| `PowerMenu.kt` | 13 | Finds the system power dialog's first real option so the D-pad is not stuck on its strip. |
| `SystemActions.kt` | 64 | Media keys, Android settings pages with a fallback, and the battery status notice. |
| `AynControls.kt` | 331 | AYN's assistant and quick-settings tiles as hotkeys, writing exactly the values the tiles write. |
| `AutoControllerFocus.kt` | 92 | Auto-lock: the screen touched last gets the controller, through an app-owned touch shield. |
| `ControllerFocusTouch.kt` | 62 | *(root)* Touches only the app-owned shield, never an underlying app. |
| `MacroPlayback.kt` | 83 | Which hotkey's macro plays, and the one-press cancel by Back or the same hotkey. |
| `Gyro.kt` | 309 | Gyro math: bias, tilt, posture and the mapping from turns to stick deflection. |
| `GyroFusion.kt` | 178 | Rest detection and the device's sensor-pair measurements. |
| `GyroControl.kt` | 208 | The sole live sensor path; feeds the helper and the settings preview with the same samples. |
| `GyroActions.kt` | 80 | Gyro on/off, calibration and the hold key, each with a notice. |
| `HidePopups.kt` | 197 | Do Not Disturb's "Try to hide all pop-ups": mutes the app's notices and, through root, toasts and heads-up; saves the found values and restores them on every exit path. |
| `PopupGuardLink.kt` | 109 | Starts `PopupGuard` through `PService` and holds the authenticated socket whose end makes it restore. |
| `PopupGuard.kt` | 45 | *(root)* Restores the heads-up setting and the toast app-op when the app's socket ends without `RESTORED`. |
| `ScreenRecording.kt` | 203 | App side of a recording: starts and stops `ScreenRecorder` as root, one recording at a time. |
| `ScreenRecorder.kt` | 763 | *(root)* Mirrors the chosen screens into an encoder, captures playback and microphone, and writes the MP4. |
| `RecordingIndicatorOverlay.kt` | 145 | The blinking recording dot that turns into a microphone while push to talk is held. |
| `PushToTalk.kt` | 31 | Which keys can be the push-to-talk button, and their names. |
| `EchoTrim.kt` | 313 | Takes what the speaker plays out of the microphone, learnt from the captured playback. |
| `VoiceBand.kt` | 91 | Keeps the microphone to the speech band. |
| `VoiceLeveler.kt` | 115 | Brings the microphone up to speech level and limits the mix. |
| `VoicePolish.kt` | 72 | The last voice step: pauses go down, a few samples ahead of the sound. |
| `AppControl.kt` | 193 | The Active App Control action: `AppControl.targets` resolves the foreground apps of the chosen screen (top, bottom, both) without ever returning a launcher, system UI or this app, and `AppControlPrompt` is the small focusable overlay behind "Ask", the optional force-close confirmation and every other modal choice, with its confirm/cancel legend. |
| `ControllerFocus.kt` | 140 | Routes the controller to the top or bottom screen with the same `PService` sequence as AYN's own tool (property, setting, `keyevent 60` without `-d`), and resolves the toggle target from `Settings.System`; Auto hands it to AYN's Auto-lock, and a toggle out of Auto-lock first reads the driven screen and locks the other one. |
| `FocusedScreen.kt` | 23 | Resolves the "focused" target: the fixed route's screen, or in Auto-lock the display of Android's focused input window (`dumpsys input`), top when unreadable. |
| `FocusIndicatorOverlay.kt` | 178 | Brief top-screen accessibility overlay with a handheld pictogram showing which screen has the controller. Nine positions, configurable display time, optional tap-through to the settings; Auto-lock shows both screens lit with an AUTO label in the same square. |
| `ButtonHints.kt` | 191 | `FaceButtonPictogram`, the face-button diamond of `ic_settings_face` drawn in code with one dot filled; `ButtonHints`, the pictogram-plus-label legends of the prompt and the switcher; and `ButtonHintOverlay`, the untouchable Confirm/Cancel window beside native top Recents. |
| `TopOverlayWindow.kt` | 32 | Window context on the top display and the nine-point position grid shared by the notice, the focus pictogram and the recording dot. |
| `SettingsBackup.kt` | 160 | The backup file: JSON writer, defensive reader (validates and clamps every value, ignores the unknown), file name and version comparison. |
| `StickButton.kt` | 70 | The two sticks, their eight direction buttons and `StickDetector`, the engage/neutral state machine shared with the overlay fallback. |
| `StickClickMachine.kt` | 60 | Press, double press and tap-and-hold decisions for a stick's L3/R3 while Back is held. |
| `LevelRepeater.kt` | 60 | `RepeatCurve` and the repeater that steps a held volume/brightness shortcut faster over time. |
| `HelperProtocol.kt` | 53 | Every command line the service sends to the root helper, with the direction and stick bit masks it expects. |
| `PadPress.kt` | 176 | The *Press button* actions: `PadButton` (the pressable pad buttons and the events that press them) and `PadPress`, which holds a button through the helper's clone while its trigger is held, releases it on every exit and lets the pressed key pass to the game. |
| `PadHelper.kt` | 347 | Starts the native root helper through `PService`, accepts only a uid-0 peer on its abstract socket, and turns its reports into D-pad presses, navigation modes and the exception mask. See [`HELPER.md`](HELPER.md). |
| `Macro.kt` | 501 | Pure macro model: steps, buttons and eight-way directions, the checked stored form, the named library entry `SavedMacro`, the editor's list operations `MacroEdits`, the playback timeline for the helper, and `MacroRecorder`, which turns the helper's raw recording into steps. |
| `MacroEditor.kt` | 1253 | The full-screen macro editor: step cards with live recording, two-column step and pause sheets, the input palette, drag to move or delete, undo, preview, naming and loading. |
| `MacroIconView.kt` | 99 | One macro input as a 40 dp glyph: a lettered key, a D-pad with the pressed arm lit, or a stick ring. |
| `MacroLibraryDialog.kt` | 210 | The saved macros of a hotkey: choose, new, edit, rename, duplicate and delete. |
| `MacroWidgets.kt` | 320 | The macro editor's shared building blocks: colours, pills, focus rings, the bottom sheet, `StepStrip`, the row that drags steps, and `ScrollIndicator`, the position thumb under it. |
| `DpadOverlay.kt` | 81 | Fallback without the helper: a focusable 1×1 overlay that exists only while Back is held and catches the D-pad's hat events, at the cost of the foreground app's focus. |
| `LevelControl.kt` | 69 | The volume and brightness actions for both screens, the top or the bottom one. |
| `LevelSteps.kt` | 17 | Pure step arithmetic for volume and brightness (15 equal brightness steps). |
| `BrightnessControl.kt` | 55 | *(root)* Steps the brightness of one or more displays via `IDisplayManager`. |
| `SystemScreenshot.kt` | 148 | *(root)* Short-lived entry point and Binder handshake for Android's native, display-targeted screenshot flow. |
| `ScreenshotSaver.kt` | 40 | Fallback: saves a screenshot of one display to `Pictures/Screenshots` through MediaStore when the native flow is unavailable. |
| `AudioMixer.kt` | 155 | *(capture/recorder)* The recording's audio mix: game playback plus microphone through the voice chain, the push-to-talk gate, fades, the video duck and the echo-late opening. Pure; frozen bit for bit by `RecorderMixTest`. |
| `MicRing.kt` | 49 | *(capture/recorder)* The microphone ring between the reader thread and the mix: push-to-talk delay, drift cap, written and pulled sample positions. |
| `PService.kt` | 120 | Bridge to AYN's root daemon `pservice` (`PServerBinder`), and `runEntryPoint` for root `app_process` entry points. |
| `Displays.kt` | 20 | The one rule for which display is which screen: top is `DEFAULT_DISPLAY`, bottom is the highest non-default, non-private id. |
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
| `drawable-nodpi/focus_top_active.webp`, `focus_bottom_active.webp`, `focus_both_active.webp` | The controller-focus pictograms (top, bottom or both screens active; both is Auto-lock, labelled in code), with the area outside the handheld transparent. |
| `drawable-*/ic_launcher_foreground.png` | Launcher foreground at five densities; also reused as the notification icon. |
| `mipmap-*/ic_launcher.png` | Launcher icon at five densities. |
| `mipmap-anydpi-v26/ic_launcher.xml` | Adaptive-icon definition for API 26+. |

There are **no layout XML files** and no Compose: every view is constructed in Kotlin.

### `app/src/test/` — JVM tests (Robolectric + JUnit)

The automated safety net. 140 test classes, **896 tests**. Run by the gate. By area:

| Area | Files |
|---|---|
| Preferences | `PrefsDefaultsTest` (every default and typed accessor), `PrefsMigrationTest` (the one-time migrations, factory shoulder defaults and their update safety, reset and unbind — the highest-risk code for existing installs), `DpadPrefsTest`, `ActionSentinelContractTest` (every stored action sentinel frozen as a literal: Prefs and backup round trip, unknown values), `ActionCodecTest` (typed actions ↔ stored strings, byte exact against the storage and backup fixtures) |
| Gestures and keys | `BackGestureMachineTest` (every branch, controlled clock and scheduler), `DoubleTapDetectorTest` (including the clock-at-zero sentinel), `CardTouchTest`, `PhysicalKeyTrackerTest`, `ServiceToggleGestureTest`, `KeyInterceptionGateTest`, `LevelStepsTest`, `InputPipelineCharacterizationTest` (the service's key path through `onKeyEvent`), `ComboRouterTest`, `DirectionalRouterTest`, `StickClickRouterTest` |
| Settings UI and changes | `SettingsActivitySnapshotTest`, `ShoulderRowSnapshotTest`, `SettingsDialogSnapshotTest`, `AccessibilityInventoryTest` (View-tree snapshots against the goldens), `SettingsNavigationTest`, `SettingsUiKitTest`, `SettingsActionsTest`, `SettingsChangesTest`, `SettingsChangeDialogTest`, `ServiceToggleSettingsTest`, `ExceptionSettingsPageTest`, `AppExceptionTest` |
| Shortcut actions | `AppControlTest`, `ControllerFocusTest`, `ControllerFocusAutoTest`, `FocusedScreenTest`, `ServiceFocusedScreenTest` |
| Recent apps and Recents | `AccessibilityAppSourceTest`, `PanelCardsTest`, `RecentsBadgesTest`, `RecentsCardStyleTest`, `RecentsCarouselTest`, `RecentsDisplayBadgeTest`, `RecentsEmptyViewTest`, `RecentsMemoryTest`, `RecentsSelectionTest`, `RecentsTaskStateTest`, `RecentsThemeTest`, `RecentsThumbnailCacheTest`, `RecentTaskQueryTest`, `SwitcherPreviewTest`, `SwitcherCloseAllTest`, `SwitcherTaskSyncTest`, `TopRecentsNavigationTest`, `TopRecentsStateTest`, `TopRecentsBackTest` |
| Tasks, swap, frontends | `TaskMovePolicyTest`, `ParkingPolicyTest`, `BottomPanelsTest`, `PanelWindowsTest` |
| Pad helper | `PadHelperLifecycleTest`, `PadNavigationModeTest`, `PadHelperProtocolTest`, `PadPressTest`, `ServicePadPressTest` |
| Other | `ShellTest`, `UpdateCheckerParseTest` (release parsing, semver ordering, legacy tags, rejected inputs), `ResourceIntegrityTest` (EN/DE string parity), `SmokeTest` |
| Support | `support/ViewTree.kt` renders a View hierarchy to deterministic text — the snapshot format, platform-independent unlike pixel screenshots. `support/Golden.kt` compares against a golden file, or rewrites it when `UPDATE_GOLDENS=1` (see the golden rule in [`TESTING.md`](TESTING.md)). `support/ViewTreeSmokeTest.kt` verifies the renderer itself. |

| Path | Purpose |
|---|---|
| `resources/robolectric.properties` | Pins Robolectric to `sdk=34`, matching `compileSdk`. |
| `resources/golden/*.txt` | The **62** golden snapshots: eight settings pages across locales/themes/window widths, shoulder/D-pad states, change-preview dialogs and accessibility inventory. |

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
| `contract/` | Static contract oracles (`check.sh`): frozen manifest, class names, stored keys, wire strings and fixture hashes, plus the move-only and rename tools used by the refactoring. |
| `devicetest/` | The on-device suite (`run.py`), visual capture (`visual.py`), resource and lifecycle measurements (`resources.py`) and instrumentation; see [`DEVICE-TEST-SUITE.md`](DEVICE-TEST-SUITE.md). |
| `docs/check-links.py` | Every relative link and anchor in `docs/`, `README.md`, `AGENTS.md` and `CLAUDE.md` resolves. |
| `docs/check-structure.py` | This file names every tracked production and instrumented source file, and none that is gone. |

## `docs/`

All documentation lives here; [`README.md`](README.md) is the index and reading path.

| File | Purpose |
|---|---|
| `README.md` | Index of every document, the reading path for newcomers, the decision list and the KDoc conventions. |
| `ARCHITECTURE.md` | Components, their responsibilities, how a gesture flows through them, the display-targeting model, bottom panels, frontend parking, Recents routing and rejected approaches. |
| `PROJECT-STRUCTURE.md` | This file. |
| `HELPER.md` | The root input helper: why the D-pad needs it, what it touches, how the pad is always restored. Mandatory reading before changing that path. |
| `HOME-DUAL-MODE.md` | Home as a second modifier with its own bindings: the owner's decisions, storage and runtime. |
| `THOR-SYSTEM-RECENTS.md` | Reference for the AYN launcher's system Recents and how the bottom Recents copies its look, memory readout and previews. |
| `TESTING.md` | The test concept, every quality gate, the expected results, and what is deliberately not automated. |
| `FEATURE-COVERAGE.md` | Every observable behavior mapped to the check that notices a change. |
| `MANUAL-TESTING.md` | Severity tiers S0–S4 and the step-by-step device procedures each one requires before pushing. |
| `DEVICE-TEST-SUITE.md` | The on-device suite in `tools/devicetest`, post-install prerequisites and device facts. |
| `PERFORMANCE.md` | Measured hot-path costs, the optimizations tried and why they were not kept, and how to measure on the device. |
| `BUILD-AND-RELEASE.md` | Setup, building, change recipes, dependency policy, releasing, the signing key and repository protection. |
| `WEBSITE.md` | The GitHub Pages site, the App-Simulation, the 2.0 film and the 3D model editor. |
| `FAQ.md` | Reader-facing answers: device compatibility beyond the tested Thor, how the update check behaves, and the privacy and trust questions an accessibility service invites. Linked from the README's Compatibility section. |
| `REFACTORING-VERIFICATION.md` | The final verification of the 2026-11 structural refactoring against the pre-refactoring baseline. Historical record. |
| `decisions/` | Architecture decision records (`0000-template.md` plus numbered records). |
| `release-notes/` | The release text per version (`v2.0.0.md`); the release workflow uses it as the release body and the landing page renders its headings, bullets and bold text. |
| `app-simulation.html` | The App-Simulation: a self-contained device diagram for trying every function, also shipped with each release. See [`WEBSITE.md`](WEBSITE.md#the-app-simulation). |
| `check-simulation.mjs` | Node check of the App-Simulation's contract. |

### `docs/assets/`

Every image the documentation and the app identity are built from. The shipped app icons
live in `app/src/main/res/` and are generated from the masters here — these files are not
read at build time.

| File | Purpose |
|---|---|
| `thor-base.png` | Device outline used by the App-Simulation and the settings overview drawing. |
| `launcher-icon.png` | The working export the density variants in `res/` were produced from. |
| `launcher-icon-original.png` | The unscaled icon master. |

