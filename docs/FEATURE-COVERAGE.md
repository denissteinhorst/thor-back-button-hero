# Feature coverage

What the app does, and which check would notice if that changed. Each row is one observable
behavior, linked to the JVM tests, golden snapshots, on-device checks
([DEVICE-TEST.md](DEVICE-TEST.md)) and manual procedures ([MANUAL-TESTING.md](MANUAL-TESTING.md))
that cover it. Rows without automated coverage name the gap, its risk and the latest point by which
it must be protected.

This table maps coverage; it does not prove equivalence. A test class named here is evidence only for
the assertions it actually makes, and a planned check is never counted as passing.

## Contents

- [How to read this table](#how-to-read-this-table)
- [Back gestures](#back-gestures-ges)
- [Back + button hotkeys](#back--button-hotkeys-cmb)
- [D-pad, sticks and the root input helper](#d-pad-sticks-and-the-root-input-helper-hlp)
- [Actions](#actions-act)
- [Bottom switcher](#bottom-switcher-swi)
- [Top Recents](#top-recents-trc)
- [Swapping and moving tasks](#swapping-and-moving-tasks-swp)
- [Frontend panels and parking](#frontend-panels-and-parking-pnl)
- [Activation toggle](#activation-toggle-tog)
- [App exceptions](#app-exceptions-exc)
- [Home as Back and dual mode](#home-as-back-and-dual-mode-mod)
- [Settings screen](#settings-screen-set)
- [Stored data, defaults and backup](#stored-data-defaults-and-backup-dat)
- [Service, platform and non-functional promises](#service-platform-and-non-functional-promises-svc)
- [Cross-feature scenarios](#cross-feature-scenarios-x)
- [Known baseline limitations](#known-baseline-limitations-lim)
- [JVM view snapshots outside the settings activity (S4)](#jvm-view-snapshots-outside-the-settings-activity-s4)
- [Inventories](#inventories)
- [Gap summary](#gap-summary)

## How to read this table

| Column | Meaning |
|---|---|
| ID | Stable identifier `F-<area>-<n>`; never reused |
| Behavior | One observable sentence |
| Where | `T` top screen, `B` bottom screen, `TB` both; `L2` = proven in both dual-mode layers |
| JVM · Golden | Test classes (`…Test` suffix omitted) and golden files (`g:` prefix, wildcards group variants) |
| Device | On-device area and check, as `area: short title` |
| Manual | Procedure IDs in MANUAL-TESTING.md |
| V/T | Visual (S5) and trace (S6) evidence: `S5`/`S6` = planned there, `—` = not applicable |
| Groups | Refactoring groups whose code is on the path (R1 always applies by package move) |
| Risk | `H` user-visible breakage or data loss, `M` degraded behavior, `L` cosmetic or rare |
| Status → due | `JVM` passing at the S0 baseline; `DEV` registered device check, baseline run pending; `MAN` manual only; `GAP` no assertion yet, followed by the step before which it must exist |

Baseline: source commit `a032d080db33`, 726 JVM tests in 98 classes, 89 goldens, 74 device checks in
16 areas (82 with the Home layer). Device checks have not yet been run against the baseline debug APK;
that run is part of the safety-net work, so every `DEV` entry is "implemented, baseline evidence
pending".

## Back gestures (GES)

| ID | Behavior | Where | JVM · Golden | Device | Manual | V/T | Groups | Risk | Status → due |
|---|---|---|---|---|---|---|---|---|---|
| F-GES-1 | A single tap of Back runs the single-tap binding after the double-tap window, at once when no double tap is bound | L2 | BackGestureMachine, BackGestureThreshold, DoubleTapDetector | gestures: single/double/tap-hold; single fires at once | B1, B2, B7 | —/S6 | R4 | H | JVM+DEV |
| F-GES-2 | Two taps within the double-tap time run the double-tap binding and no single tap | L2 | BackGestureMachine, BackGestureThreshold, DoubleTapDetector | gestures: single/double/tap-hold | B3, B4 | —/S6 | R4 | H | JVM+DEV |
| F-GES-3 | Tap, then hold within the tap-hold window runs the tap-and-hold binding, never a swap | L2 | BackGestureMachine, BackGestureThreshold | gestures: single/double/tap-hold | B6 | —/S6 | R4 | H | JVM+DEV |
| F-GES-4 | Holding Back past the hold time runs the hold binding; releasing adds no tap | L2 | BackGestureMachine, BackGestureThreshold | gestures: hold adds no tap | B5, B8 | —/S6 | R4 | H | JVM+DEV |
| F-GES-5 | An unassigned gesture does nothing (Back is swallowed) while Back + button hotkeys keep working | L2 | BackGestureMachine, UnassignedRow, g:settings_main_*_basic | gestures: unassigned gestures | C17o | —/S6 | R4, R8 | H | JVM+DEV |
| F-GES-6 | A gesture switched off (double tap, tap-hold, hold) is skipped without delaying the others | L2 | BackGestureMachine, PrefsDefaults | gestures: single fires at once | B7, B8 | —/S6 | R4 | M | JVM+DEV (hold/tap-hold disable not device-asserted) |
| F-GES-7 | Timings are adjustable per gesture and the slider shows only for an assigned gesture | — | SettingsActivitySnapshot, g:settings_main_*_basic | — | C17o | S5/— | R8 | L | JVM |
| F-GES-8 | `pressFollowsTap` is latched at DOWN, so a pending single tap firing mid-hold does not change the decision | — | BackGestureMachine | — | — | —/S6 | R4 | H | JVM (boundary t−1/t/t+1 with fake clock → GAP before R4.1) |

## Back + button hotkeys (CMB)

| ID | Behavior | Where | JVM · Golden | Device | Manual | V/T | Groups | Risk | Status → due |
|---|---|---|---|---|---|---|---|---|---|
| F-CMB-1 | Back + L1/L2/R1/R2 runs that button's binding; the game never sees the bound press | L2 | — (service key path has no JVM test), g:shoulder_* (settings only) | sources: every button | C9–C15, C17 | S5/S6 | R4, R5 | H | DEV → GAP JVM characterization (B3) before R4.1 |
| F-CMB-2 | Back + A/B/X/Y follow the printed letter in every controller style | L2 | FaceButton | sources: every button; ayn: controller style | C17r, C17s | —/S6 | R4 | H | JVM+DEV |
| F-CMB-3 | Back + Home/Start/Select run their bindings; AYN's own button keeps its role | L2 | FaceButton (key mapping), PhysicalKeyTracker, HomeAsBackService | sources: every button | C17r, C17t | —/S6 | R4 | H | JVM+DEV |
| F-CMB-4 | An unassigned button is left alone, with and without Back held | L2 | KeyInterceptionGate (consume/release retention) | sources: bound withheld, unbound reaches game | C17, C17u | —/S6 | R4 | H | DEV; JVM partial → GAP before R4.1 |
| F-CMB-5 | Shoulder buttons act only while Back is held; otherwise events stay unconsumed | — | — | sources: bound withheld | C17 | —/S6 | R4 | H | DEV → GAP JVM characterization before R4.1 |
| F-CMB-6 | A hotkey releasing Back produces no Back tap | L2 | DualModeService (layer swallow), HomeAsBackService | sources: every button | C17r | —/S6 | R4 | H | DEV; JVM partial |
| F-CMB-7 | Volume and brightness hotkeys repeat while held after 0.25 s, accelerating to about 40 ms | — | LevelSteps | levels: held stick repeats | C17n | —/S6 | R5, R11 | M | JVM+DEV |

## D-pad, sticks and the root input helper (HLP)

| ID | Behavior | Where | JVM · Golden | Device | Manual | V/T | Groups | Risk | Status → due |
|---|---|---|---|---|---|---|---|---|---|
| F-HLP-1 | Back + D-pad direction runs its binding; the press is withheld and the game keeps focus | L2 | DpadPrefs (defaults/targets), PadHelperLifecycle (reports) | sources: D-pad and stick directions | C17b, C17c, C17d | —/S6 | R11 | H | JVM+DEV |
| F-HLP-2 | The helper hides AYN's pad behind an identical clone on controller number 1; without Back, input is unchanged | — | PadHelperLifecycle | preflight; sources: unbound reaches game | C17a | —/S6 | R11 | H | DEV+MAN |
| F-HLP-3 | The original pad is restored on every exit path: service off, process death, clearing all D-pad bindings | — | PadHelperLifecycle | service: restart, app process death | C17g, C18, C20 | —/S6 | R10, R11 | H | JVM+DEV (S6: app process killed → helper hands back, service, helper and clone return, hotkey works) |
| F-HLP-4 | Holding Home + Back for 5 s hands the pad back; shortcuts fall back to the overlay until restart | — | — | service: Home + Back held 5 s hands the pad back | C17f | —/S6 | R11 | H | DEV (S6: release, clone gone, native face input reaches the game, Back + D-pad via overlay, helper back after restart) |
| F-HLP-5 | Controller-style switches (Standard, Xbox, Ban) keep the clone working | — | — | ayn: controller style | C17e | —/— | R11 | H | DEV+MAN |
| F-HLP-6 | Without pservice, Back + D-pad works through a focus overlay (games may pause) and settings warn | — | — | — | C17h | S5/— | R3, R11 | M | MAN → GAP before R3.1 |
| F-HLP-7 | Stick directions fire once per deflection and need the centre before the next | L2 | Stick | sources: D-pad and stick directions | C17i, C17j | —/S6 | R4, R11 | M | JVM+DEV |
| F-HLP-8 | A stick without an action is never withheld; a stick pushed before Back stays with the game | — | Stick | sources: bound withheld | C17k | —/S6 | R11 | M | JVM+DEV |
| F-HLP-9 | Stick clicks (L3/R3) distinguish press, double press and tap-and-hold, timed like Back | L2 | Stick | sources: stick clicks | C17m | —/S6 | R4 | M | JVM+DEV |
| F-HLP-10 | Releasing Back stops a running level repeat | — | LevelSteps | — | C17l | —/S6 | R11 | M | JVM (repeater stop) → GAP device before R11.1 |
| F-HLP-11 | Helper peers are accepted only from uid 0 (credential check) | — | — | — | — | —/— | R3, R11 | H | GAP → before R3.1 (security) |
| F-HLP-12 | The padhelper.c ↔ PadHelper line protocol is unchanged | — | PadNavigationMode (wire values), PadHelperLifecycle | sources | — | —/S6 | R11 | H | JVM (partial) + S2 wire contract |

## Actions (ACT)

Every stored action value, derived from `Prefs.KNOWN_ACTIONS` + `Ayn.ACTIONS` (55 values, see
[Inventories](#inventories)).

| ID | Behavior | Where | JVM · Golden | Device | Manual | V/T | Groups | Risk | Status → due |
|---|---|---|---|---|---|---|---|---|---|
| F-ACT-1 | Launch app (fixed package) opens it on the chosen screen | TB | LaunchAppChooser, g:shoulder_l2_app_with_toggle | windows: launches app top/bottom | C11, C12 | S5/S6 | R5 | H | JVM+DEV |
| F-ACT-2 | `@app_ask` / `@app_favorites` open an app list (all / starred) on press and launch the choice | TB | LaunchAppChooser | — | — | S5/— | R5, R7 | M | JVM (settings side) → GAP device before R5.1 |
| F-ACT-3 | `@home` opens the launcher on the configured screen | TB | — | windows: Home top and bottom | C13 | —/S6 | R5 | H | DEV |
| F-ACT-4 | `@back` sends a normal Back to the app with controller focus | TB | — | windows: Back reaches focused app | — | —/S6 | R5 | H | DEV |
| F-ACT-5 | `@recents` opens system Recents on top; `@switcher` opens the bottom switcher | TB | ActionGroups | windows: switcher/Top Recents opens | C14, B3 | S5/S6 | R5, R6 | H | DEV |
| F-ACT-6 | `@swap_apps` trades the apps of both screens | TB | TaskMovePolicy | windows: swap apps | B5 | —/S6 | R5, R6 | H | JVM+DEV |
| F-ACT-7 | `@last_app` flips between the last two apps of the controller screen; a closed app shows a message | TB | LastApp | windows: last app ×3 | — | S5/S6 | R5 | M | JVM+DEV |
| F-ACT-8 | `@screenshot` top/bottom/both uses native screenshots; both = top full width with bottom centred below | TB | — | capture: screenshot top/bottom/both | C15a–c, C5 | S5/S6 | R9 | H | DEV |
| F-ACT-9 | `@record_video` starts/stops on the same hotkey, saves under Videos/BBH-Records, ends when screens go off; optional mic and push-to-talk | TB | ScreenRecording, EchoTrim, GameBed, VoiceBand, VoiceLeveler, VoicePolish | capture: recording | — | S5/S6 | R9 | H | JVM+DEV; audio by ear (MAN) |
| F-ACT-10 | `@macro` plays the recorded sequence after Back is released; same hotkey or Back stops it; needs pservice | — | Macro, MacroSettings | macro: plays in order | C17z–C17z8 | S5/S6 | R5, R8, R11 | H | JVM+DEV+MAN |
| F-ACT-11 | `@gyro` toggles gyro aiming (optionally hold-to-aim); `@gyro_calibrate` measures offset/noise | — | Gyro, GyroFusion, GyroSettings | gyro: ×3 | Gyro 1–6 | S5/— | R5, R11 | M | JVM+DEV+MAN |
| F-ACT-12 | Navigation flick left/right moves to the previous/next app | T | — | windows: navigation flicks | C9, C10 | —/S6 | R5 | M | DEV |
| F-ACT-13 | Volume up/down/mute on both, top or bottom; mute restores | TB | LevelSteps | levels: volume, mute | C17c | S5/— | R5, R11 | M | JVM+DEV |
| F-ACT-14 | Brightness up/down on both, top or bottom | TB | LevelSteps | levels: brightness | C17b | —/— | R5, R11 | M | JVM+DEV |
| F-ACT-15 | Media play/pause, next, previous reach the playing session | — | — | media: media control | — | —/— | R5 | M | DEV |
| F-ACT-16 | `@controller_focus` toggles or fixes the route; the pictogram appears on DOWN on the top screen at the configured position/time | T | ControllerFocus, g:dpad_focus_options | windows: focus switches; pictogram while down | C23–C28 | S5/S6 | R5, R7 | H | JVM+DEV |
| F-ACT-17 | `@switch_active_app` moves the input screen's foreground app to the other screen; focus follows by default | TB | ActiveAppSwitch | windows: switch active app | C37–C40 | —/S6 | R5, R6 | H | JVM+DEV |
| F-ACT-18 | `@app_control` minimizes, force-closes or restarts on a screen or asks; optional confirmation | TB | AppControl | appcontrol: ×5 | C30–C36 | S5/S6 | R5, R7 | H | JVM+DEV |
| F-ACT-19 | `@close_apps` / `@close_background_apps` end apps on chosen screens, asking first by default | TB | CloseApps | appcontrol: close all/background/asks | — | S5/— | R5 | H | JVM+DEV |
| F-ACT-20 | `@power_menu` opens the power dialog on top; `@standby` turns screens off, optionally asking | T | PowerMenu | panels: power menu, standby | — | S5/— | R5 | M | JVM+DEV |
| F-ACT-21 | `@status_overlay` shows battery, time left/until full, temperature | T | StatusSummary | media: status overlay | — | S5/— | R5, R7 | L | JVM+DEV |
| F-ACT-22 | The 12 AYN controls apply their setting with a notice; prompt variants offer a choice | T | Ayn, AynPrefs | ayn: ×13 | — | S5/— | R5 | M | JVM+DEV |
| F-ACT-23 | Quick settings / notifications open the shade; 10 settings shortcuts open their page or fall back | T | ActionGroups | panels: shade; settings shortcuts | C16 | —/— | R5 | L | DEV |
| F-ACT-24 | "Not set" stores an empty binding that does nothing | — | UnassignedRow | gestures: unassigned | C17o | —/— | R2 | M | JVM+DEV |

## Bottom switcher (SWI)

| ID | Behavior | Where | JVM · Golden | Device | Manual | V/T | Groups | Risk | Status → due |
|---|---|---|---|---|---|---|---|---|---|
| F-SWI-1 | Opens on the bottom screen with one card per observed app, memory and actions; Back closes it | B | RecentsCarousel, RecentsCardStyle, RecentsMemory, SwitcherSnapshot · g:switcher_* | windows: switcher opens, Back closes | C1, B4 | S5/S6 | R6, R7 | H | JVM (styling) + DEV |
| F-SWI-2 | Tapping a card opens the app (task moved, not relaunched); swiping up closes it; horizontal scroll never closes | B | CardTouch, RecentsSelection | windows: controller opens app | C2, C3, C4 | S5/S6 | R6, R7 | H | JVM+DEV |
| F-SWI-3 | "Screenshot" captures only the bottom screen without the switcher | B | — | — | C5 | S5/— | R6, R9 | M | MAN → GAP before R6.1 |
| F-SWI-4 | "Close all" closes the listed apps (bottom or both scopes) | TB | SwitcherCloseAll | windows: X closes listed apps | C6, C6a | —/S6 | R6 | H | JVM+DEV |
| F-SWI-5 | Empty state shows the Quickstep icon and "No recent items", theme updates live | B | RecentsEmptyView, RecentsTheme | — | C7 | S5/— | R7 | L | JVM |
| F-SWI-6 | "Show apps from both screens" adds top apps with a screen badge | TB | RecentsDisplayBadge, RecentsBadges | — | C2a | S5/— | R6 | M | JVM |
| F-SWI-7 | Controller navigation: left/right selects, confirm opens, up closes; focus returns after every close path | B | RecentsSelection, RecentsCarousel, SwitcherSnapshot · g:switcher_many_* | windows: controller opens app | C8a, C8c | —/S6 | R6 | H | JVM+DEV |
| F-SWI-8 | Thumbnails are cached per task and shown when available; a package on both screens previews the switcher's task | B | RecentsThumbnailCache, SwitcherPreview, AccessibilityAppSource | — | C1 | S5/— | R6 | L | JVM |
| F-SWI-9 | Stale tasks disappear; failed system queries never clear history | B | SwitcherTaskSync, RecentTaskQuery, RecentsTaskState | — | C2c | —/— | R6 | M | JVM |
| F-SWI-10 | ES-DE Companion keeps its card although Android excludes it from native Recents | B | RecentTaskQuery | — | C2d | —/— | R6 | M | JVM + MAN |

## Top Recents (TRC)

| ID | Behavior | Where | JVM · Golden | Device | Manual | V/T | Groups | Risk | Status → due |
|---|---|---|---|---|---|---|---|---|---|
| F-TRC-1 | Tap-then-hold opens system Recents over the top screen's app | T | TopRecentsState | windows: top Recents opens | B6 | S5/S6 | R6 | H | JVM+DEV |
| F-TRC-2 | Controller navigation through Quickstep's native path; up closes one card; no autonomous step | T | TopRecentsNavigation, TopRecentsBack | windows: controller opens from top Recents | C8b, C8d, C8e | —/S6 | R6 | H | JVM+DEV |
| F-TRC-3 | Selecting a bottom app moves its task to the top; launchers never move; focus restored | TB | RecentsSelection, TaskMovePolicy | windows: controller opens from top Recents | C2b, C8d | —/S6 | R6 | H | JVM+DEV |

## Swapping and moving tasks (SWP)

| ID | Behavior | Where | JVM · Golden | Device | Manual | V/T | Groups | Risk | Status → due |
|---|---|---|---|---|---|---|---|---|---|
| F-SWP-1 | Hold-to-swap moves the existing tasks between displays (task IDs survive) | TB | TaskMovePolicy | windows: hold Back swaps | B5, C8j | —/S6 | R6 | H | JVM+DEV |
| F-SWP-2 | An app whose Hold is unchecked stays; only the other app moves | TB | AppException, TaskMovePolicy | exceptions | C18l | —/S6 | R6 | H | JVM; GAP device before R6.1 |
| F-SWP-3 | A foreground bottom panel blocks the swap with a localized message | TB | BottomPanels | — | C8i | S5/— | R6 | M | MAN → GAP before R6.1 |
| F-SWP-4 | A protected task (Home, shared root) cancels the pair with a message; no relaunch fallback | TB | TaskMovePolicy | — | C8j | —/— | R6 | H | JVM |
| F-SWP-5 | Moving the foreground app reveals the previous app or the screen's own home | TB | TaskMovePolicy | windows: swap | C2a, C8e | —/S6 | R6 | M | JVM+DEV |

## Frontend panels and parking (PNL)

| ID | Behavior | Where | JVM · Golden | Device | Manual | V/T | Groups | Risk | Status → due |
|---|---|---|---|---|---|---|---|---|---|
| F-PNL-1 | Third-party full-screen bottom panels are hidden through the root window host and get their own card | B | PanelWindows, PanelCards, BottomPanels | — (`panels` = system panels) | C8, C8f | S5/— | R6 | H | JVM → GAP device scenario before R6.1 |
| F-PNL-2 | A hidden panel is restored on every exit path: service off, app death, root process death, standby | B | BottomPanels | — | C8h | —/— | R6, R10 | H | MAN → GAP before R6.1/R10.1 |
| F-PNL-3 | Swiping a panel card keeps it; Close all closes its owner package | B | PanelCards | — | C8g | —/— | R6 | M | JVM (partial) + MAN |
| F-PNL-4 | Frontend parking keeps Cocoon-style launchers from reclaiming the bottom screen | B | ParkingPolicy | — | — | —/— | R6 | M | JVM → GAP device before R6.1 |
| F-PNL-5 | Panel window host peers are accepted only from uid 0 | — | — | — | — | —/— | R3, R6 | H | GAP → before R3.1 (security) |

## Activation toggle (TOG)

| ID | Behavior | Where | JVM · Golden | Device | Manual | V/T | Groups | Risk | Status → due |
|---|---|---|---|---|---|---|---|---|---|
| F-TOG-1 | Holding the selected pair past the hold time pauses/resumes all interception with one message | — | ServiceToggleGesture, KeyInterceptionGate | toggle: every pair | Toggle list | S5/S6 | R4 | H | JVM+DEV |
| F-TOG-2 | Shorter holds, one button alone or mixed pairs do nothing | — | ServiceToggleGesture | toggle: shorter/alone | Toggle list | —/S6 | R4 | H | JVM+DEV |
| F-TOG-3 | While paused, Back and every combo reach the game untouched; stick clicks always do | — | KeyInterceptionGate | toggle: paused passthrough | Toggle list | —/S6 | R4 | H | JVM+DEV |
| F-TOG-4 | Offered pairs: L3+R3, L1+R1, L2+R2, Start+Select, Back+Home; 100–3000 ms; unassigned by default | — | ServiceToggleSettings, SettingsActions, PrefsDefaults | — | Toggle list | S5/— | R8 | M | JVM |
| F-TOG-5 | Service restart starts active; an unassigned shortcut re-activates interception | — | KeyInterceptionGate | service: restart | Toggle list | —/— | R4, R10 | H | JVM+DEV |

## App exceptions (EXC)

| ID | Behavior | Where | JVM · Golden | Device | Manual | V/T | Groups | Risk | Status → due |
|---|---|---|---|---|---|---|---|---|---|
| F-EXC-1 | An unchecked action behaves as if the app were not installed (tap = Back, hold = long press, combo reaches game) | — | AppException, ExceptionGroupSnapshot | exceptions: blocks excluded combo | C18b–C18f | —/S6 | R4 | H | JVM+DEV |
| F-EXC-2 | All four Back gestures unchecked: Back and every combo reach the app untouched; one message after the swap timeout | — | AppException | exceptions: all Back excluded | C18g | —/S6 | R4 | H | JVM+DEV |
| F-EXC-3 | The app on the controller-focused screen decides; a switched-off row is ignored | — | AppException | — | C18h, C18i | —/S6 | R4 | H | JVM → GAP device before R4.1 |
| F-EXC-4 | Exception editor pages, modals and "Remove all" with preview | — | ExceptionSettingsPage, g:exceptions_*, g:settings_*_exceptions | — | C18k | S5/— | R8 | M | JVM |
| F-EXC-5 | Native Back passthrough uses the root path for excepted apps | — | — | exceptions | C18b | —/S6 | R3, R4 | H | DEV (partial) |

## Home as Back and dual mode (MOD)

| ID | Behavior | Where | JVM · Golden | Device | Manual | V/T | Groups | Risk | Status → due |
|---|---|---|---|---|---|---|---|---|---|
| F-MOD-1 | "Use the Home button as Back" makes Home act exactly like Back; Back + Home is disabled with its binding kept | — | HomeAsBack, HomeAsBackService, HomeAsBackSettings | gestures: Home as Back | C17w–C17y | S5/S6 | R4, R8 | H | JVM+DEV |
| F-MOD-2 | Dual mode makes Home a second modifier with its own bindings and timings | L2 | DualMode, DualModeService | dual: own layer | C17z | —/S6 | R4 | H | JVM+DEV |
| F-MOD-3 | First activation copies Back's bindings (Home's single tap stays Home); off keeps the Home layer | — | DualModeSettings | dual: off rests | C17z, C17za | —/— | R2, R8 | H | JVM+DEV |
| F-MOD-4 | The first modifier owns the press; the second is ignored | L2 | DualModeService | dual: first owns | C17za | —/S6 | R4 | H | JVM+DEV |
| F-MOD-5 | Pages show Home and Back side by side while dual mode is on | — | g:dual_* | — | C17z | S5/— | R8 | M | JVM |

## Settings screen (SET)

| ID | Behavior | Where | JVM · Golden | Device | Manual | V/T | Groups | Risk | Status → due |
|---|---|---|---|---|---|---|---|---|---|
| F-SET-1 | Every page renders in EN/DE, light/dark, compact/wide exactly as frozen | T | SettingsActivitySnapshot, g:settings_main_*, g:settings_wide_* | — | A1, A6, C22 | S5/— | R8 | H | JVM |
| F-SET-2 | Overview drawing: leader lines to every binding; tapping a part opens its page | T | BindingsOverview, g:*_overview, g:overview_all_assigned | — | — | S5/— | R8 | M | JVM |
| F-SET-3 | Navigation: menu dialog below 720 dp, icon rail + overlay panel at ≥ 720 dp | T | SettingsNavigation | — | — | S5/— | R8 | M | JVM |
| F-SET-4 | Action chooser lists actions with groups and sub-options per button type | T | ActionChooser, ActionGroups, OptionGuide, g:dialog_*chooser | — | A4 | S5/— | R8 | M | JVM |
| F-SET-5 | Shoulder/D-pad/face/stick rows show every state (sentinel, app, target, toggle) | T | ShoulderRowSnapshot, g:shoulder_*, g:dpad_* | — | A4 | S5/— | R8 | M | JVM |
| F-SET-6 | Clear bindings / load defaults preview before/after and apply atomically | T | SettingsChanges, SettingsChangeDialog, g:dialog_unbind_*, g:dialog_preset_basic, g:dialog_reset_settings_confirm | — | C18k | S5/— | R2, R8 | H | JVM |
| F-SET-7 | Service health line, warning row and "Repair service" flow | T | ServiceBlocker, SettingsUiKit, SettingsDialogSnapshot, g:dialog_repair_service | preflight | A2, C18, C19 | S5/— | R8 | H | JVM + MAN |
| F-SET-8 | Version footer; manual and automatic update check with notification | T | UpdateCheckerParse | — | A3, D7 | —/— | R8 | M | JVM (parse) + MAN |
| F-SET-9 | In-app update download/install through PackageInstaller, status receiver opens confirmation | T | UpdateInstaller | — | — | —/— | R8 | H | JVM → GAP device scenario before R8.1 |
| F-SET-10 | Welcome sheet: defaults, clean start, start setup keeping macros, customized detection | T | WelcomeSheet, g:welcome_* | — | — | S5/— | R8 | M | JVM |
| F-SET-11 | Macro editor: record, edit, drag, undo, preview, save, rename, delete | T | MacroSettings, Macro | macro | C17z–C17z8 | S5/— | R8 | M | JVM + MAN |
| F-SET-12 | Gyro dialog with live preview, calibration instructions | T | GyroSettings | — | Gyro 6 | S5/— | R8 | M | JVM + MAN |
| F-SET-13 | Focus indicator popup: 9 positions, visibility, display time, preview on top | T | ControllerFocus, SettingsActions, g:dpad_focus_options | windows: pictogram | C25–C28 | S5/— | R7, R8 | M | JVM+DEV |
| F-SET-14 | Accessibility labels and focus order of all controls | T | AccessibilityInventory, g:a11y_inventory | — | — | —/— | R8 | M | JVM |
| F-SET-15 | Settings controller navigation (focus rings, D-pad reachable) | T | — | — | C17z2c | S5/— | R8 | M | MAN → GAP before R8.1 |
| F-SET-16 | Transient state survives recreation: scroll, focus, IME, open dialog edits | T | — | — | — | S5/— | R8 | M | GAP → before R8.1 |
| F-SET-17 | Every string exists in EN and DE with matching placeholders | — | ResourceIntegrity | — | C22 | —/— | R8 | M | JVM |
| F-SET-18 | Activity starts without manifest/resource failures on device | T | Smoke | instrumented | A1 | —/— | R1, R8 | H | JVM + androidTest smoke via `tools/devicetest/instrument.py` (not in gate) |

## Stored data, defaults and backup (DAT)

| ID | Behavior | Where | JVM · Golden | Device | Manual | V/T | Groups | Risk | Status → due |
|---|---|---|---|---|---|---|---|---|---|
| F-DAT-1 | Preferences live in `back_button_hero` with unchanged keys and encodings, both layers | — | PrefsDefaults, Prefs* | — | A5 | —/— | R2 | H | JVM + StoredDataContractTest raw typed fixtures (S3, A6) |
| F-DAT-2 | Fresh install: nothing assigned, no toggle; documented defaults (timings, focus centre right 500 ms) | — | PrefsDefaults | — | C17q | —/— | R2 | H | JVM |
| F-DAT-3 | Migrations run once in order: navigation_flick_defaults_v1, navigation_flick_directions_v2, removed `@swap_screens`, shoulder_defaults_v3, back_gestures_v1, exception_sticks_v2, exception_buttons_v1, toggle_pairs_v1, toggle_default_v2, unassigned_defaults_v1, gyro_calibration_v1 | — | PrefsMigration | — | C17q, D4a | —/— | R2 | H | JVM (each) + StoredDataContractTest order, partial-flag and second-construction idempotence (S3) |
| F-DAT-4 | An update keeps settings; new defaults reach only never-touched settings | — | PrefsMigration | — | D2–D4a | —/— | R2 | H | JVM + device in-place update via `tools/devicetest/upgrade.py` (F5: populated, legacy migration, fresh) |
| F-DAT-5 | Backup: one JSON named `back-button-hero-backup_v<version>_<date>.json` through the file picker | — | SettingsBackup, SettingsBackupUi | — | C17p | —/— | R2, R8 | H | JVM + StoredDataContractTest fixed-bytes backup fixtures (S3, A6) |
| F-DAT-6 | Load backup previews changes, warns on another version, ignores unknown parts, validates each value, refuses non-backups unchanged | — | SettingsBackup, SettingsBackupUi | — | C17p, C21 | S5/— | R2, R8 | H | JVM |
| F-DAT-7 | Gyro calibration is excluded from backups and survives defaults/restore | — | GyroSettings, SettingsBackup | — | Gyro 6 | —/— | R2 | M | JVM |
| F-DAT-8 | `applySettings` writes a plan atomically; listeners see a complete state; stale previews are rejected | — | SettingsChanges | — | — | —/— | R2 | H | JVM + StoredDataContractTest listener-visible state (S3) |
| F-DAT-9 | Macros, favorites and exceptions are stored and round-trip | — | Macro, AppException, SettingsBackup | — | C17z6 | —/— | R2 | H | JVM |

## Service, platform and non-functional promises (SVC)

| ID | Behavior | Where | JVM · Golden | Device | Manual | V/T | Groups | Risk | Status → due |
|---|---|---|---|---|---|---|---|---|---|
| F-SVC-1 | After a service restart the helper and every hotkey come back; exactly one helper runs | — | PadHelperLifecycle | service: restart | C20 | —/— | R10, R11 | H | JVM+DEV |
| F-SVC-2 | No focus or window switch the user did not trigger (owner rule) | TB | — | — | C8e, C40 | —/S6 | R4–R7, R10 | H | MAN → GAP trace assertion before R4.1 |
| F-SVC-3 | Without pservice, swipe-close falls back to `killBackgroundProcesses`; other privileged actions report unavailable | — | Shell | — | C17h | —/— | R3 | M | MAN → GAP before R3.1 |
| F-SVC-4 | Every package name reaching a privileged command passes `Shell.isPkg` | — | Shell | — | — | —/S6 | R3 | H | JVM |
| F-SVC-5 | Frozen class names: SwitcherService, SettingsActivity, UpdateInstaller$StatusReceiver, 10 root entry points | — | — | preflight (service) | — | —/— | R1 | H | GAP → S2 static contract before R1 |
| F-SVC-6 | Wire strings: binder descriptors, broadcast actions, helper protocol, log decision texts | — | — | — | — | —/S6 | R1, R3, R11 | H | GAP → S2 before R1 |
| F-SVC-7 | Release runtime classpath stays kotlin-stdlib + annotations; no new dependency | — | — | — | — | —/— | all | H | S0 evidence; GC A9 in S2 |
| F-SVC-8 | The accessibility service is enabled by component name and survives updates | — | — | preflight | D4 | —/— | R1 | H | DEV + MAN |
| F-SVC-9 | Notification permission and channels unchanged | — | — | — | — | —/— | R8 | M | GAP → S2 manifest/resource contract |
| F-SVC-10 | Performance and memory stay within baseline budgets | — | RecentsMemory | — | — | —/— | R6, R7, R10 | M | device `tools/devicetest/resources.py` (F1–F4, budgets in PERFORMANCE.md) |
| F-SVC-11 | Update check uses the public releases API without credentials | — | UpdateCheckerParse | — | D7 | —/— | R8 | M | JVM (parse) |

## Cross-feature scenarios (X)

Scenarios combining features. None has a dedicated automated check yet; each lists the step before
which it needs one or an accepted manual procedure.

| ID | Scenario | Groups | Risk | Status → due |
|---|---|---|---|---|
| F-X-1 | A setting changes while Back or a combo is held | R2, R4 | M | GAP → before R4.1 |
| F-X-2 | Both modifiers held, both release orders (dual mode, Home as Back) | R4 | H | DEV (dual: first owns) + MAN C17x, C17za |
| F-X-3 | Exception on the controller-focused display while the other screen has another app | R4 | H | MAN C18i → GAP before R4.1 |
| F-X-4 | Panel plus ordinary task in one Recents session and in a swap | R6 | H | MAN C8f, C8i |
| F-X-5 | Excluded Companion task versus genuine Home task | R6 | M | MAN C2d, C8j |
| F-X-6 | Failed task query preserves history; stale asynchronous replies are ignored | R6, R10 | M | JVM (SwitcherTaskSync partial) → GAP stale reply before R10.1 |
| F-X-7 | Recording while switching, swapping or entering standby | R9, R10 | M | GAP → before R9.1 |
| F-X-8 | Service reconnect with a held input or queued callback | R10 | H | GAP → before R10.1 |
| F-X-9 | Toggle pause while a combo is held or a switcher is open | R4 | M | MAN (toggle list) |
| F-X-10 | Active App Control prompt open, then interception paused | R5, R7 | M | MAN C35 |
| F-X-11 | Macro running while Back is pressed again or the style changes | R5, R11 | M | MAN C17z4, C17z5 |
| F-X-12 | Helper or panel process death during use | R10, R11 | H | GAP → before R10.1 |

## Known baseline limitations (LIM)

Recorded from FEEDBACK.md as current behavior. They are not refactoring targets; fixing one is
separate scope with its own owner decision.

| ID | Limitation |
|---|---|
| F-LIM-1 | Some launchers (Jarngreipr) move along on swap despite exceptions |
| F-LIM-2 | WatermelonDS/Azahar lose their bottom screen to the launcher at game start; swap then reports not possible |
| F-LIM-3 | iiSU reclaims its bottom screen immediately; unsupported |
| F-LIM-4 | ES-DE/Companion do not return automatically after a swapped game ends (deliberately removed in 1.4.1) |
| F-LIM-5 | Unreproduced report: app not working despite accessibility permission |
| F-LIM-6 | Heading drift of gyro without magnetometer; sustained acceleration not separable from gravity |

## JVM view snapshots outside the settings activity (S4)

Recorded on the baseline production code; each golden is frozen by contract A6. Overlays are
captured from the window manager as added (`support/OverlayTree.kt`: window parameters, the
`ViewTree` text, and alpha, rotation, elevation and background per view).

| Producer | JVM · Golden | Not visible to a tree snapshot → where it is asserted |
|---|---|---|
| NoticeOverlay | OverlaySnapshot · g:overlay_notice | Fade timing → device notice log (any area using notices), manual |
| RecordingIndicatorOverlay | OverlaySnapshot · g:overlay_recording | Pulse drawing → S5 / manual video procedure |
| FocusIndicatorOverlay | OverlaySnapshot · g:overlay_focus | Pictogram pixels and tap-to-open on device → S5, focus device checks |
| AppControlPrompt | OverlaySnapshot · g:overlay_app_control_* | Controller key routing → device app-control checks |
| AutoControllerFocus, DpadOverlay | OverlaySnapshot · g:overlay_auto_focus, g:overlay_dpad | Window focus and key capture → device D-pad/focus checks |
| SwitcherOverlay (+ RecentsCarousel, RecentsEmptyView, RecentsBadges) | SwitcherSnapshot · g:switcher_* | Launcher-derived colours (absent on the JVM: the selection colour is transparent there), thumbnail pixels, memory figures (0 B on the JVM) → S5 bottom-screen captures, C1/C7 |
| MacroEditor, MacroLibraryDialog | EditorDialogSnapshot · g:macro_editor_*, g:macro_library_en_light | Drag/drop and step-sheet interaction stay in MacroSettings |
| GyroSettingsDialog | EditorDialogSnapshot · g:gyro_setup_* | GyroPreview canvas → S5; live sensor → manual gyro procedure |
| ActionChooser, WelcomeSheet, BindingsOverview, ExceptionGroupDialog | existing g:dialog_*, g:welcome_*, g:overview_*, g:exceptions_* | BindingsOverview vector drawing → S5 |

## Inventories

**Stored actions (55).** `Prefs.KNOWN_ACTIONS` lists 43 values; `Ayn.ACTIONS` adds 12:
`@performance`, `@fan`, `@handle`, `@display`, `@refresh_rate`, `@stick_light`, `@direct_power`,
`@vibration`, `@force_landscape`, `@virtual_mouse`, `@do_not_disturb`, `@mute_microphone`. A bare
package name is an app launch. Source literals add `@choose_app` (settings-only chooser entry, never
stored) and `@swap_screens` (read only by its removal migration); `@group_*` entries are list-only
values from `ActionGroups`. Total `"@…"` literals in production source: 57 = 55 + 2.

**Hotkeys.** Back's four gestures; L1, L2, R1, R2; A, B, X, Y; Home, Start, Select; four D-pad
directions; four directions per stick; three click gestures per stick; mirrored in dual mode's Home
layer for every binding except Back + Home (`Prefs.HOME_LAYER_BASES`).

**Device checks.** 76 registered checks in 16 areas, 84 runs with the Home layer
(`python3 tools/devicetest/run.py --list`).

## Gap summary

Gaps by the step before which they must be protected (S8 accepts later rows as pending; rows due
before R1 must have evidence by then):

| Due before | Rows |
|---|---|
| R1 (static contracts, S2) | F-SVC-5, F-SVC-6, F-SVC-9 |
| R2.1 (stored data, S3) | closed by S3: F-DAT-1, F-DAT-3, F-DAT-5, F-DAT-8 |
| R3.1 (platform boundary) | F-HLP-6, F-HLP-11, F-PNL-5, F-SVC-3; root commands frozen by PrivilegedCommandContractTest (call sites, AYN controls, FrontendParking, AutoControllerFocus), ServiceActionCommandTest (every stored action, helper launch script) and ServiceInternalCommandTest (native Back, swipe-kill, Recents routing, swap, last app, screenshot, top/bottom Recents). SocketLaunchCommandTest freezes the screen recorder launch script; PadHelperProtocolTest freezes the pad helper line protocol (commands, takeover replay, event reactions incl. malformed lines). Still open, due before the first semantic change of the helper connection (needs a production seam, LocalServerSocket is unavailable on the JVM): uid-0 peer acceptance/rejection, line framing from the socket reader, relaunch after a lost helper; until then device suite. Device/manual only: the PanelConnection launch script (needs a LocalServerSocket, unavailable on the JVM) and the AYN panel close (needs a covering panel window) |
| R4.1 (input pipeline) | F-GES-8, F-CMB-1, F-CMB-4, F-CMB-5, F-EXC-3, F-SVC-2, F-X-1, F-X-3 |
| R5.1 (actions) | F-ACT-2 |
| R6.1 (Recents, panels) | F-SWI-3, F-SWP-2, F-SWP-3, F-PNL-1, F-PNL-2, F-PNL-4 |
| R8.1 (settings UI) | F-SET-9, F-SET-15, F-SET-16 |
| R9.1 (media) | F-X-7 |
| R10.1 (lifecycle) | F-X-6, F-X-8, F-X-12 (F-HLP-3 closed by the S6 device check) |
| R11.1 (helper) | F-HLP-10 (F-HLP-4 closed by the S6 device check) |
| S7 (resources) | F-SVC-10 |

Every row also has a visual (S5) or trace (S6) allocation where marked; those columns are planned,
not implemented.
