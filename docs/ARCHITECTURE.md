# Architecture

How the implementation is divided, how a gesture travels through it, and which
constraints shaped it. For a file-by-file inventory see
[`PROJECT-STRUCTURE.md`](PROJECT-STRUCTURE.md); for the safety net around all of it,
[`TESTING.md`](TESTING.md).

The code is one module in feature packages (`core`, `prefs`, `input`, `actions`, `recents`,
`panels`, `pad`, `macro`, `capture`, `overlay`, `settings`). Classes whose names are read outside
the compiler (the service, the activity, the update receiver and the root entry points) stay in the
root package; see [decision 0001](decisions/0001-frozen-external-contracts.md).

## What the app has to work around

Three constraints explain most design decisions here.

**A normal Android app cannot read other apps' tasks.** There is no public API for "what
is running on which screen". The app therefore runs as an `AccessibilityService` and
reconstructs that knowledge from the windows it observes. Everything about the recent-app
model follows from this.

**The hardware Back button must keep working as Back.** The app overloads it, so it has
to decide — within a few hundred milliseconds, from a stream of key events — whether a
press is a plain Back, a double tap, a hold, or a tap-then-hold. The decision cannot be
deferred, and getting it wrong breaks the device's most-used button.

**The second screen is not addressable by a constant.** Display IDs are assigned at
runtime, so every piece of display-targeting logic has to resolve the bottom screen
dynamically.

On top of that, two self-imposed rules: **no third-party runtime dependencies** (the
release APK resolves to nothing but the Kotlin stdlib) and **no layout XML or Compose** —
every view is built in Kotlin. New code should stay consistent with both.

## Entry points

Two, both declared in the manifest:

- **`SettingsActivity`** — the launcher icon. Configuration UI only; it performs no
  gestures.
- **`SwitcherService`** — the `AccessibilityService`. Everything the app actually *does*
  happens here. It is also what keeps the process alive, which is why the process
  survives swiping the app away.

They share no state in memory. `Prefs` is the only channel between them: the activity
writes, the service reads on demand.

### Lifetimes and work lanes

An unbind and a destroy release the same session state through `releaseSession()`, in one
order: pending switcher and top-Recents launches, previews and macro recording, the screen
recording, the standby receiver, the input routers, the D-pad helper, the app-control prompt,
panels and frontend parking. Android follows an unbind with a destroy, so every step is safe
to run twice. Only the destroy then drops the main handler's pending work and the switcher and
focus windows.

Background work deliberately keeps its own lanes and is not merged into one executor:
`PService` serializes its own transactions, `PanelConnection` has an ordered scheduled worker,
`ControllerFocus`, `AynControls`, `LevelControl` and `FrontendParking` each own a single-thread
executor, the recorder has independent start/stop, audio and codec threads with a Binder death
link, screenshots launch and connect concurrently, and the update check does blocking I/O on its
own thread. A shared queue would change head-of-line blocking and timeouts. The native helper,
the panel watchdog and the recorder's death link restore the device on real process death, which
no in-process close can replace.

## Components

### `SwitcherService` — runtime coordinator

Receives key events and window-change events, and owns every Android side effect:
global actions (Back, Home, Recents), display-aware activity launches, screenshots,
and the screen swap. It holds the `AccessibilityAppSource`, creates the
`SwitcherOverlay`, and opportunistically captures thumbnails of the front app.

What it deliberately does **not** own is the Back-press decision — that moved into
`BackGestureMachine`. The other per-press decisions sit in small units in `input/`, each
called from the service's key path in the original order, with the effects left to the
service behind a small host interface:

- `ComboRouter` — Back (or Home in dual mode) + shoulder, Home/Start/Select or face button:
  acts only while a modifier is held, leaves an unassigned or excepted button's event to the
  app (an excepted one still counts as a combo, so Back's release is no tap), tracks both
  mirrored devices' releases, and arms controller focus / app switch until the last release.
- `DirectionalRouter` — Back + D-pad and Back + stick from `PadHelper`/`DpadOverlay`: the same
  run/arm decision per direction, the commit of an armed focus on its own axis or stick, or on
  Back's release while the overlay held window focus.
- `StickClickRouter` — Back + L3/R3: mirrored-device tracking and the layer latched at the
  click's DOWN; each stick's `StickClickMachine` times press, double press and tap-and-hold.

The ordering in the service (toggle gesture, Home-as-Back routing, AYN's button, the
interception gate, Recents controller keys, exception latch, native Back passthrough) stays in
`SwitcherService.handleActiveKeyEvent`; it is interleaved with Recents, macro and overlay state
and is characterized through `onKeyEvent` by `InputPipelineCharacterizationTest`.

The stored actions are dispatched by `SwitcherService.runComboAction` in one `when`; families
with their own logic or state run in small handlers, the effects they need from the service
passed in through a host:

- `actions/SystemActions` — media keys for whatever app plays, Android's settings pages (with a
  fallback page and a message), and the battery status notice.
- `actions/AppControlActions` — minimize, force-close or restart the app in front, close the apps
  known on the chosen screens, and standby; each may ask first on the controller's screen, and
  "ask" resolves the apps before its prompt opens.
- `actions/LastAppAction` — the previous app on the controller's screen, via the root task
  launcher (skipping apps that moved to the other screen) or a plain launch without root.
- `macro/MacroPlayback` — which hotkey's macro the helper plays, and the one-press cancel: Back
  stops it, and its own hotkey in that press does not restart it.
- `pad/GyroActions` — gyro on/off with a button's setup, calibration, and the hold key.

Thin actions bound to the service's own state (power menu, Home, screenshots, launches, AYN
switches, Recents) stay in the service.

Recents, windows and task moves sit in `recents/`, each behind a host for the service's effects:

- `WindowTracker` — the window-change part of `onAccessibilityEvent`: records both screens and
  debounces the panel refresh (400 ms), the front-app capture (800 ms) and the parked reclaim.
- `TaskOps` — closing apps (root removal and force-stop, else `killBackgroundProcesses`), the
  memory report, and launching an existing task with the relaunch fallback where allowed.
- `SwapCoordinator` — hold-to-swap and moving the app with controller focus to the other screen;
  Hold exceptions and captured panels refuse the move before anything leaves its screen.
- `BottomSwitcherController` — the bottom switcher's open state: task list first, then the
  overlay; while it shows, a task-state poll every second and at most three snapshot requests
  per task. Closed → opening → open → closed; a second press while opening cancels.
- `TopRecentsLauncher` — the launch phase of native top Recents: the task list before Overview
  opens, the 250 ms Quickstep poll (25 attempts), and the selection read or card click that
  identifies the moved task.

The top Recents controller session (routing, state checks, focus priming, dismissal queue,
restore) stays in the service: the key path writes its flags and it drives the pad helper,
accessibility nodes and gestures. Panel capture/hide/restore is `panels.BottomPanels`.

### `BackGestureMachine` — the timing decisions, without Android

The state machine over a single Back press's DOWN/UP sequence. It contains the gesture
state and the timing rules but no Android calls. Clock, scheduling and completed actions
enter through three small interfaces (`GestureClock`, `GestureScheduler`,
`GestureActions`) that `SwitcherService` implements with `SystemClock` and a `Handler`.

The seam exists for one reason: `Handler`- and `SystemClock`-driven key timing cannot be
exercised deterministically on the JVM, which left the app's most critical code
untestable. The extraction was verbatim — the decision logic, including the
`pressFollowsTap` latching quirk described below, moved across unchanged, so the machine
reproduces the original behavior rather than a tidied-up version of it.

### `DoubleTapDetector` — press-interval tracking

A deliberately separate, tiny tracker consulted independently of the single/hold timers.
Note that it uses `0L` as its "no previous press" sentinel; with a test clock sitting at
zero no double tap is ever detected. That is a test-fixture consideration, not a defect.

### `AccessibilityAppSource` — the recent-app model

Implements `AppSource`. Builds a most-recently-used list **per display** purely from
`windowsOnAllDisplays`, filters a fixed ignore-set (launcher, SystemUI, OEM dual-screen
packages) so they never surface in the switcher, and supplies labels and icons from
`PackageManager`.

By default, the bottom switcher uses its own display history. Its optional **Show apps
from both screens** setting combines that history with the top display's observed history;
the same setting makes **Close all** act on both displays.

Two consequences worth internalising:

- **Only apps seen since the service started are known.** There is no history before
  that, and no way to obtain one.
- **Thumbnails are a separate in-memory cache**, filled opportunistically by
  `captureFrontApp` in the service — not derived from window state. A card can therefore
  exist without a preview image.

### `SwitcherOverlay` — the bottom-screen switcher

Draws the switcher as a `TYPE_ACCESSIBILITY_OVERLAY` window bound to one display, via
`createDisplayContext(...).createWindowContext(...)`. That binding is what pins it to the
bottom screen; it is not a full-screen window that happens to be positioned low.

Contents: a horizontal scroll row of cards (icon, label, thumbnail), the current
available/total memory, bottom-screen Screenshot and Close all actions styled like the
top system Recents, and an empty state. Close all acts on the bottom screen, or on both screens
when **Show apps from both screens** is on. `CardTouch` arbitrates each
card's touches — tap opens, upward swipe past a threshold closes, horizontal drags go to
the scroller — and is extracted so that arbitration is testable without a real window.

Back Button Hero's own settings appear as a card like any other app, matching top
Recents. Closing that card (swipe or Close all) only finishes and removes our own task:
this process hosts the accessibility service, so it is never force-stopped or killed.

The Screenshot action dismisses the switcher, then `SystemScreenshot` uses the existing
AYN `pservice` bridge to submit a display-targeted request to Android's protected
SystemUI screenshot service. The saved image contains only the bottom display; this
firmware always renders SystemUI's native screenshot animation and action strip on the
top display. Bottom and both-screen screenshot hotkeys use the same path; the old
accessibility capture and `ScreenshotSaver` remain only as a fallback when `pservice`
is unavailable. For "both", AYN's encoded display request makes SystemUI produce one
vertical composite: the top display at full width and the narrower bottom display
centered directly underneath it.

### `ControllerFocus` and `FocusIndicatorOverlay` — routing the controller

`ControllerFocus` switches the Thor's controller between the two screens with the same three
steps AYN's own tool runs through `PService`: set `persist.sys.input.dispaly`, write the
`screen_focus_lock` setting, and send `input keyevent 60` **without** `-d`, so the key reaches
the focused display where AYN's assistant window commits the route. The current route is read
from `Settings.System`, which keeps "toggle" instant and free of a root round trip.

`FocusIndicatorOverlay` shows the confirming pictogram on the top screen (the bottom one is
permanently covered by AYN's assistant). It appears when the shoulder button goes **down**, before
the root command has run, and disappears again if the command fails. Position (a 3×3 grid,
default center right), display time (default 500 ms) and visibility are stored per button. Tapping the pictogram makes it a touchable
window and opens `SettingsActivity` on the top screen inside the indicator popup for that button.
The settings screen previews changes through `SwitcherService.previewFocusIndicator`, because only
the service may draw accessibility overlays.

**The app never switches the controller focus on the user's behalf.** The route changes only
when the user triggers it: an assigned controller-focus shortcut, Switch active app with its
per-hotkey follow option enabled (the default), or the opt-in controller
navigation in Recents, which borrows the route and restores it on exit, AYN's Auto-lock (`0`)
included. Holding Back, Recents task moves and ordinary app launches retain the route,
for every app. An automatic route-follows-swap for RetroArch
(RetroArch hands port 1 to the first device it hears after a resume, and AYN's focus commit comes
from Android's "Virtual" device) shipped as an experiment in 1.4.0 and was removed again in favor
of this rule.

Switch active app captures the controller/input display and moves only its foreground task through
`RecentsTaskLauncher`, retaining task identity and leaving the destination's app in place. It waits
for the trigger release and honors that hotkey's app exception, independently of Hold exclusions.
In Auto-lock, `AutoControllerFocus` selects the requested input display with a consumed touch on a
temporary app-owned shield. `ControllerFocusTouch` verifies the operation's uniquely named input window and restricts injection
to the app UID, failing closed when the shield is absent, not touchable, or covered. No routing setting/property is changed in Auto-lock. The
shield is removed after delivery; with follow disabled, the same path retains the source display.
Fixed routes use the existing OEM command sequence only when follow is enabled.

### Screen recording — `ScreenRecording` and the root `ScreenRecorder`

`ScreenRecording` in the service owns the recording state (idle, starting, recording, stopping)
and its session guard; the root entry point `ScreenRecorder` mirrors the screens through a GL
compositor into the video encoder, captures the game's playback through an audio policy, and
writes MP4 segments. Its audio mix is plain Kotlin in `capture.recorder`: `MicRing` carries the
microphone to the mix, `AudioMixer` adds it to the game sound through the voice chain and the
push-to-talk gate and lowers the video's game sound while a talk is open. The device speaker is
lowered separately and always restored; the voice itself is never altered beyond the chain.
`RecorderMixTest` freezes the mix bit for bit; encoder, muxer and compositor are device-only.

### Small indicators — `NoticeOverlay`, `FocusIndicatorOverlay`, `RecordingIndicatorOverlay`

All three draw on the top screen, because AYN's assistant covers the bottom one, and never take
key or controller focus. `TopOverlayWindow` gives them their window context there and maps the
nine-point position grid that the pictogram and the recording dot share. Their sizes, colours and
margins stay with each overlay as named constants
([decision 0006](decisions/0006-overlay-design-values-stay-local.md)).

### Active App Control — `AppControl` and `AppControlPrompt`

A Back + button action that minimizes or force-closes the app in front. Per button it stores a mode
(`minimize` or `force_close`), a screen (top, bottom, both or `ask`) and, for force-close with a fixed
screen, whether to confirm first; none of it is set until the action is assigned, so existing installs
are unchanged. `SwitcherService.runAppControl` resolves the target apps through
`AccessibilityAppSource.topApp` **before** any prompt opens, so "the app behind the modal" is the one
acted on even if the window state shifts while the user decides. Minimize reuses `goHome` for the
display; force-close reuses `killOnly`, the same path as swiping a card away in the switcher
(`TaskRemover` and `force-stop` through `PService`, with the unprivileged kill as fallback). Neither
saves anything for the app, which the settings say plainly.

`AppControlPrompt` is a focusable accessibility overlay on the display the controller currently
drives (`controllerDisplayId`). Joystick and hat events reach only the focused window, so it takes
focus like `DpadOverlay`; D-pad or stick moves the choice, the confirm key (which follows the Thor's
A/B flip setting) accepts it and the other face button cancels. It is dismissed with the service.

### `Prefs` — storage and migrations

Wraps `SharedPreferences` and owns the schema, the defaults, and the one-time
**migrations**, which its `init` runs from `Migrations.kt` in their fixed order. Factory shortcuts (L2 screenshot, R2 controller focus) are
written once on a fresh install; an update pins L2 and R2 to their existing values, unassigned
included, so changed defaults never reach an existing install (`shoulder_defaults_v3`). Shoulder actions are stored either as a bare package
name (launch that app) or as an `@`-prefixed sentinel (`SHOULDER_HOME`,
`SHOULDER_FLICK_LEFT`, …); `isShoulderApp()` distinguishes the two.

When changing a default or renaming a stored key, add a guarded migration here rather
than silently changing behavior for existing installs — follow the established pattern of
a versioned boolean flag guarding a one-time rewrite. See
[`MAINTENANCE.md`](MAINTENANCE.md) for the recipe.

### Back + D-pad: `PadHelper`, `DpadOverlay`, `LevelControl`

The D-pad reports a hat axis, which `onKeyEvent` never sees, so D-pad combos take a different
path from the shoulder buttons. Both end in the same `runComboAction`:

- **`PadHelper`** starts the native root helper (`app/src/main/cpp/padhelper.c`) through
  `PService` and listens on an abstract socket that only accepts uid 0. The helper hides AYN's pad
  from Android behind an identical uinput clone and withholds D-pad presses made while Back is held
  or the bottom switcher is open, so the game keeps focus. Top Recents keeps Quickstep's native
  controller navigation; the helper sends only Up to the service for a swipe bounded to the actual
  TaskView geometry read when Overview opened.
  It runs while a D-pad direction has an action or Recents controller navigation is enabled, and it follows
  AYN's controller-style switches. The full design and its safety nets are in
  [`HELPER.md`](HELPER.md).
- **`DpadOverlay`** is the fallback when the helper is not active. A focusable 1×1 overlay exists
  only while Back is held and catches the hat events, at the cost of the foreground app's window
  focus.
- **`LevelControl`** runs the volume and brightness actions for both screens, the top one or the
  bottom one. Top volume is `STREAM_MUSIC`. Bottom volume (AYN's `secondary_screen_volume_level`)
  and per-display brightness (the `BrightnessControl` root entry point) need pservice. The step
  arithmetic is the pure, unit-tested `LevelSteps`.

**Analog sticks** (`StickButton`, `StickDetector`, `LevelRepeater`): eight more `ComboButton`s, all
unassigned by default, set on the Analog Sticks page (two tabs, left and right). Reports arrive from
the helper (`STICK`) or the overlay fallback and go to `SwitcherService.handleStick`, which runs the
action once per deflection; controller focus commits when the stick returns to neutral, and the
stepping actions (volume, brightness) repeat through `LevelRepeater` while Back and the stick are held.
A stick's click (L3/R3) has press, double press and tap-and-hold gestures with their own
double-press and tap-and-hold times, timed exactly like Back's (`StickClickMachine`, a port of the
same decisions) and only while Back is held. Stepping actions repeat on `RepeatCurve` (slow start, easing to ~40 ms) for sticks, stick
hold and the D-pad alike; `LevelControl` drops repeat steps while root work is still queued.
Every stick direction and click gesture has its own exception column (seven per stick). An unchecked direction is listed in `SPASS`: the helper hands such a deflection back to the game as soon as it recognises it, and a stick with no direction left for BBH is no longer listed in `STICKS`; an unchecked click stays unconsumed. A native-Back exception still releases all of them, like every other combo. Exceptions stored before the sticks existed were migrated once (`exception_sticks_v2`) with every stick column on; backups carry the stick columns as `sticksAllowed` and read a missing field as all on.

`SettingsBackup` writes and reads the backup JSON (format marker and version, app version, date, then the same values `SettingsState` holds). Reading is defensive by construction: unknown fields are ignored, missing ones keep the factory value, each value is validated and clamped on its own, and only a non-backup is refused. The result is a `SettingsState` that goes through the normal preview and the atomic `Prefs.applySettings`; the file is picked and saved through the system file picker, so no storage permission is needed. Everything starts unassigned; `unassigned_defaults_v1` pins the earlier D-pad shortcuts and activation pair for existing installs.

The four Back gestures are `BackGestureButton`s: `BackGestureMachine` still decides timing and exceptions and reports which gesture fired, `SwitcherService.runBackGesture` runs the bound action (new actions: `@back`, `@switcher`, `@swap_apps`). `Prefs.doubleTapEnabled`/`tapHoldEnabled`/`holdEnabled` are derived from whether the gesture has an action; `back_gestures_v1` migrates the old switches and single choice once.

`ShoulderButton`, `DpadDirection` and `StickButton` all implement `ComboButton`, so `Prefs` stores every combo
button's action, launch screen and level target the same way.

**Home, Start, Select and the face buttons** (`AndroidButton`, `FaceButton`) are key events like the shoulder
buttons and share `ComboRouter` (via `SwitcherService.handleShoulder`); `comboButton` maps an event to its button. Face buttons are
stored by their printed letter: the Thor's style switch (`flip_button_layout`) swaps the keys the pad reports
(A↔B, X↔Y, measured on the device), so `FaceButton.of` reads the setting at every press. AYN's own button sends
the same `KEYCODE_HOME` as the house key, but from `gpio-keys` instead of the pad, so Home counts only with
`SOURCE_GAMEPAD`. A Back + Home combo the service will consume bypasses `handleAynButton` (`isHomeCombo`), DOWN
and UP alike; Home alone, an unassigned or excepted Home and AYN's button keep their earlier path. Home, Start
and Select sit on the Android Buttons page below the Back gestures, A/B/X/Y on the Face Buttons page.

**Home as Back** (`Prefs.homeAsBack`, Settings page, off by default) makes the pad's house key behave exactly like
Back: every gesture, combo and exception. `onKeyEvent` routes Back and the pad's Home through `HomeAsBack` before
`handleAynButton` and the interception gate. Whichever key goes down first owns the one virtual Back until all its
devices release it; Home's events are copied as `KEYCODE_BACK` and the other key's press is swallowed whole, so
holding both is neither a double press nor Back + Home. The route is fixed at DOWN, so a press keeps it across a
pause or a setting change; with interception paused no new press is routed and Home stays Home. A translated Home
is always consumed: where real Back would pass natively (an exception's native Back), the service injects a Back
on release instead. Back + Home cannot be pressed meanwhile, so its row is shown disabled with its binding kept.
`PadHelper` sends `HOMEBACK 0/1` so the helper counts Home as Back for D-pad and stick captures, which also turns
its Home + Back emergency release off.

**Dual mode** (`Prefs.dualMode`, Settings page, off by default, exclusive with Home as Back) makes the pad's Home a
second modifier with its own complete binding set; `docs/DUAL-MODE.md` is the spec. Home-layer bindings are the
same `ComboButton`s wrapped in `HomeLayerButton`, whose `prefKey` carries a `dual_` prefix, so every prefKey-based
read, write, snapshot and backup handles them unchanged; `ComboButton.base` gets the wrapped button back for type
checks and exceptions, which both layers share. The service reuses the Home-as-Back route (the first modifier owns
the press, the other is swallowed) and latches at DOWN whether the press is Home's: Home then drives its own
`BackGestureMachine` (`homeGesture`, over `Prefs.homeGestures` with the Home layer's timings) and every combo held
under it resolves through `layered()` to the Home layer. Home's native fallback is `GLOBAL_ACTION_HOME`, not Back.
`PadHelper` sends `HOMEBACK 2`: Home counts as held for captures, and the emergency release stays on. Turning dual
mode off keeps the Home layer's bindings but makes them rest; only a whole-app reset clears them.

**Back + Home as the activation shortcut** (`Prefs.TOGGLE_BACK_HOME`) must toggle and nothing else, in either order,
active or paused, with Home as Back on or off. `ServiceToggleGesture` therefore runs first in `onKeyEvent`, ahead of
the Home-as-Back routing, and tracks one press per key across mirrored devices. Once both keys are down the second
press is consumed whole; a first press the service already handled (active Back, or Home acting as Back) gets
`markComboFired`, so its Back gesture ends without a tap. A first press that would reach Android at DOWN (both keys
while paused, the pad's Home while active without Home as Back) is held back instead. Released without its partner,
it is replayed through the normal key path (`replayUnpaired`, so AYN-panel and combo handling still apply) and
whatever that leaves to Android is injected: Back via `nativeBack` (long press past the system timeout), Home via
`GLOBAL_ACTION_HOME`. The cost is that, with this shortcut, such a lone press reaches Android only on release. AYN's
own Home and injected Back never count. `Start + Select` is an ordinary observed pair like the shoulders.

**Macros** (`Prefs.SHOULDER_MACRO`, any combo but Back's own gestures, `Prefs.MACRO_BUTTONS`) play a recorded
input sequence through the helper's pad clone, so the game receives it as player 1. `Macro` holds the steps
(inputs pressed together, hold, pause before the next step) and its compact stored form, which `decode` checks
value by value. Macros live in a named library (`SavedMacro`, pref `macro_library`, one tab-separated line per
macro); a hotkey stores only the id in `<key>_macro`, so one macro can serve several hotkeys and an edit reaches
all of them. Deleting a macro clears every reference in the same write; resets and unbinding clear references but
keep the library. Snapshots and backups carry the library and the references; a backup reference to a macro its
own library lacks is dropped. A new macro is named when first saved (a free "Macro <hotkey>"); a library macro
saves in place, is renamed from the editor's header, and "Save as new" (⋯ menu) stores a variant. Loading (⋯ menu,
or the empty editor) and the hotkey's single "Saved macros" row (`MacroLibraryDialog`: choose, new, edit, rename, duplicate,
delete) pick from the library; that row's value names the macro the hotkey plays. Holds and pauses under 50 ms (`SHORT_WARN_MS`) are
flagged in the timeline and explained on their sheet: games that read input once per frame can miss them. Editing another macro from the
list never reassigns the hotkey; editing its own works like before, so "Save as new" hands the hotkey the variant.

The editor is built for people who are not into macros: one big record button that always adds to the end, steps
as cards (a bar under each shows the hold up to a second), tap a card or pause for a bottom sheet with minus/plus
in 50 ms steps, presets and an exact value, and per step move earlier/later, duplicate and delete, so everything
also works with the D-pad. Long press drags a card (`StepStrip` takes the touch stream over, an accent line marks
the slot, a bin replaces the bottom bar); pauses stay in place when steps move (`MacroEdits.move`). A palette adds
steps by hand or changes a step's chord (one input per channel, at most eight). Every change goes through one
undo history; deletions show an undo bar instead of asking. The preview lights cards and pauses up in real time
without sending any input, because real input would operate the settings screen. The pure list operations live
in `MacroEdits`; the shared views (`MacroUi`, sheets, `StepStrip`) in `MacroWidgets.kt`. `MacroEditor` (settings) records through
`SwitcherService.macroRecorder`: the service sets `PadHelper.setRecording`, the helper withholds the pad from
Android and streams raw `REC` lines, and `MacroRecorder` turns them into steps (keys at once, D-pad, sticks and
trigger axes per frame; sticks in eight directions with the 0.6/0.3 thresholds; presses that overlap or start
within 60 ms become one step). `finish` changes nothing, so the editor calls it every 100 ms while recording
to show the steps so far, with a held press and the pause since the last release (`restingMs`) counting up; the
timeline only rebuilds the steps that changed and scrolls to its end. Playback sends the compiled `MacroEvent` timeline with an id; the helper starts it
only after Back is released, because macro keys pressed under a held Back would read as Back + button shortcuts.
A new Back press stops it in the helper at once; the service remembers which macro that press stopped, so the
same hotkey in that press only cancels. The helper's `MACRO_STOPPED` can arrive before that Back press reaches
the service, so a stop the service did not request also counts as cancelled by the next Back press. Face buttons are stored by printed label and mapped through
`flip_button_layout` at recording and playback. While any macro is bound, the helper is kept running.

### `SettingsActivity` + `SettingsUiKit`

Eight hand-built pages share framework Views; no `PreferenceFragment` or layout XML.
`SettingsNavigation` lazily builds and caches pages, using a left panel at 720 dp and
above and a menu dialog below that width. Explicit navigation starts content at the top;
activity recreation preserves the active page and scroll position. `SettingsUiKit`
provides shared cards, branch-connected dependent controls and aligned range sliders.
Accessibility warnings appear at the right of the top bar; root-helper/setup warnings
remain below it. Service health still distinguishes on / unresponsive / off and offers
the existing Android accessibility-settings repair flow.

The activity builds the pages and wires their dialogs; what is pure text lives beside it.
`BindingDescriber` names every action, option and row value and holds the lists the choosers
offer, `ChangeDescriber` writes the lines of a change preview, and `BackupFlow` runs saving and
restoring the settings file up to that preview. `BindingTextTableTest` freezes every binding text
in both languages and both layers.

`SettingsChanges` captures immutable before/after snapshots for page defaults/unbind and
whole-app reset/unbind. Confirmations list actual changes; `Prefs.applySettings` rejects
stale previews and writes accepted plans atomically. Whole-app unbind clears button, stick,
D-pad and activation shortcuts, preserving primary gestures. An unassigned activation
shortcut reactivates temporarily paused interception so the app cannot remain stranded.

`ExceptionSettingsPage` edits per-app exceptions (`AppException`, stored by `Prefs` as
`app_exceptions`): installed-app search (system apps hidden unless toggled on or
searched for), a start choice (allow or block everything), and a
list of apps. Each row has an active switch, a trash button (with confirmation) and five group buttons in a
two-column grid (Android buttons, face buttons, shoulder buttons, D-pad, analog sticks) that show how many of
the group's controls Back Button Hero still handles; a button opens `ExceptionGroupDialog`, a modal with a
glyph, name and switch per control (33 in all: four Back gestures, L1–R2, four D-pad directions, per stick four
directions plus press/double/tap-and-hold, Home/Start/Select and A/B/X/Y), an all-switch and one all-switch per
section (Back gestures and Back + Home/Start/Select; left and right stick). The 33 bits no longer fit an `Int`,
so `AppException.allowed` is a `Long`; `exception_buttons_v1` turns the seven button bits on for exceptions
stored before them, and backups carry them as `buttonsAllowed`. The group model is
`ExceptionGroup`; every change is written at once. Help has no page actions; Exceptions offers **Add app** and
**Remove all exceptions** (a `PRESET` plan for that section) in its introduction. Whole-app factory settings also
clear exceptions; Unbind never does.

At runtime `SwitcherService` resolves the exception of the top application window on the
display the controller is routed to (`screen_focus_lock`) and latches it at Back DOWN for that
press and its combos. `BackGestureMachine` treats a blocked double/tap-and-hold as disabled,
turns a blocked single tap into `nativeBack(false)` and a blocked hold into
`nativeBack(true)` at the system long-press timeout. `nativeBack` injects `input -d <display>
keyevent [--longpress] 4` through pservice (global Back as fallback); the injected virtual-device
Back passes `onKeyEvent` untouched. With all four Back gestures blocked the physical Back is
not consumed at all, and no combo runs. Blocked key buttons stay unconsumed and blocked
D-pad directions are sent to the helper as a `PASS` mask; either still marks the Back press as a
combo, so its release is no tap. The mask is refreshed on window changes, setting
changes and every Back DOWN. The activation toggle is global and unaffected.

Hold-to-swap additionally checks both captured foreground packages against the current
exception list, independently of controller focus. A package with Hold unchecked is
removed from the move plan before panel and task protection checks; the other package
can still move onto its screen. The excepted task is neither moved nor relaunched.
Inactive exceptions do not protect a package. Explicit Recents selections retain their
existing cross-display behavior and do not apply this Hold exclusion.
An excepted hold shows a short notice when its native long press fires. With all
four Back gestures excepted, the gesture machine only observes the physical Back
pair and shows the notice after the configured hold time; it neither consumes nor
injects input. Short presses, repeats and mirrored devices do not create extra
notices. Resetting interception cancels a pending notice. A globally disabled Hold
does not produce an exception notice, and an allowed partial swap remains silent.


### `UpdateChecker`

Talks to the GitHub Releases API directly with `HttpURLConnection` and manual JSON
parsing — consistent with the no-dependency rule. It parses the release tag as semver and
compares it against the installed `versionName`, then posts a notification linking to the
release page. A failed check says why (`Failure`): a spent rate limit (403/429 with the
`X-RateLimit-*`/`Retry-After` headers, shown with the minutes to wait), no connection, or an
unusable answer.

The release's APK is listed as an `Asset` (name, size and the SHA-256 GitHub publishes). Only an
asset under this repository's own `releases/download/` URL, ending in `.apk`, is offered.
`UpdateInstaller` streams it into an Android `PackageInstaller` session (no file of its own),
checks length and checksum before committing, and lets Android ask for the confirmation. Android
installs only a package signed with the installed app's key, so a foreign or tampered file is
refused; `UpdateInstaller.StatusReceiver` opens the confirmation or names the refusal. This needs
`REQUEST_INSTALL_PACKAGES`, which Android asks the user to grant once per app ("allow from this
source"); without it the dialog explains the step and opens that setting. A release therefore has
to publish its APK as a release asset, as `release.yml` does.

Two behavioral facts that regularly surprise people:

- **The check runs only on `SettingsActivity.onCreate` and on the manual button.** There
  is no `WorkManager` job, no boot receiver, no interval. A notification can therefore
  only appear shortly after the app is opened.
- **It is a single attempt with no retry** and a 5 s timeout. If it fails — no network
  yet after a reboot, or GitHub's 60-requests-per-hour unauthenticated limit — the
  failure is swallowed and nothing happens until the next app open.

## How a Back press flows

```
hardware Back
      │
      ▼
SwitcherService.onKeyEvent ──────────────► button held with Back? (ComboRouter,
      │                                          │  DirectionalRouter, StickClickRouter)
      │ no                                       ▼
      ▼                                   run its configured action
BackGestureMachine.handleBack(event)      (or leave unconsumed if unassigned)
      │
      ├─ DOWN: latch pressFollowsTap, schedule hold / tap-hold runnables
      └─ UP:   cancel or fire, consult DoubleTapDetector
                     │
                     ▼
            GestureActions (implemented by SwitcherService)
                     │
        ┌────────────┼─────────────┬──────────────────┐
        ▼            ▼             ▼                  ▼
   GLOBAL_ACTION  swapScreens  openTopRecents    showSwitcher
      _BACK                                           │
   or bottom                                           ▼
   launcher                                      SwitcherOverlay
                                                       │
                                                       ▼
                                            AccessibilityAppSource
                                            (MRU list + thumbnails)
```

The five outcomes and their triggers:

| Gesture | Outcome |
|---|---|
| Single tap | Its assigned action (default: Home on the bottom screen; "Regular Back press" is `GLOBAL_ACTION_BACK`) |
| Double tap within the window | Switcher overlay on the bottom screen |
| Hold for the configured duration | Swap the apps on the top and bottom screens |
| Tap, then hold again within the tap-hold window | System Recents on the **top** screen instead of a swap |
| Hold + L1/L2/R1/R2 or a D-pad direction | That button's configured action, on its configured screen |

### Why `pressFollowsTap` is latched at DOWN

`pressFollowsTap` is captured when the key goes down, not recomputed on the way up. A
pending single-tap callback can fire on its own mid-hold, before the UP event arrives —
recomputing later would read state the earlier callback already changed, and the
tap-then-hold gesture would intermittently behave like a plain hold. The comment at its
declaration records this; it survived the extraction into `BackGestureMachine`
deliberately and should not be "simplified".

## Display targeting

All display logic distinguishes `Display.DEFAULT_DISPLAY` (the top screen) from the
dynamically detected bottom display — `core.Displays.bottom` (the service's `bottomDisplay()`
and `FrontendParking` both use it) returns the non-default, non-private display with the highest ID. **Do not hardcode display IDs anywhere else.** Shoulder actions that
target a screen, the overlay's window binding, and the swap all route through this.

`AccessibilityAppSource` additionally tracks whether a display is *covered*, so panel
discovery can run before launching and app thumbnails never capture a covering panel.

### Bottom-screen frontend panels

`BottomPanels` tracks window identities separately from activity packages. Full-screen
third-party `PRESENTATION`, `PRIVATE_PRESENTATION`, and `APPLICATION_OVERLAY` windows
are eligible; normal activities, input/accessibility windows and system-owned panels are
excluded. `PanelWindows` joins WindowManager ownership/type/bounds with accessibility
window IDs, including panels whose accessibility root is null. Display IDs are resolved
at runtime. No frontend package allowlist is used.

Before opening the bottom launcher or an app, the service captures each visible panel
and then hides it. Bottom Recents also captures the visible front panel before its own
overlay appears. Each panel has its own card, icon, localized label and retained image;
its owner's activity task snapshots never replace that image. Selecting the card
restores the original window without moving or relaunching its owner's activity.
Swiping up/controller dismissal leaves the return card intact. Close all closes each
listed panel's owner package too, including its upper-screen activity; it does not
spare panel owners in either scope. Normal activity cards remain independently closable.
Destroyed/replaced windows expire their
old cards; a failed capture leaves the normal empty preview rather than using an
unrelated activity snapshot.

`PanelConnection` owns an authenticated uid-0 local socket to `PanelWindowHost`, started
through the existing AYN bridge. The root owner retains each `IWindow` binder before
`dispatchAppVisibility(false)` removes the window from accessibility discovery. An
independent root recovery process retains the same binders before hiding is allowed.
App/socket death restores all held windows; if either root process dies, its surviving
peer restores them. Both channels have bounded timeouts. Service teardown explicitly
closes the channel, but recovery does not rely on `onDestroy`. Without the privileged
bridge or on unsupported firmware, ordinary launches continue without panel handling.
This path does not rewrite window types. Hold-to-swap is rejected
with a localized explanation if either foreground app owns an observed bottom panel,
including a hidden panel; neither task is moved in that case.

**AYN's assistant panel** is the one system window treated as a panel, as an owner-approved
exception: `com.odin.dualscreen.assistant`'s `BOOT_PROGRESS` window on the bottom screen. Its layer
sits above every accessibility overlay, so it is a *covering* panel and behaves like a normal
bottom window instead:
- Opening bottom Recents captures and hides it, so Recents is seen. Closing Recents without a
  choice shows it again; choosing an app or the launcher leaves it parked behind its card.
- AYN's button arrives as a physical Home key from `gpio-keys`. While the panel is parked, AYN
  still considers it open, so its own press would close it. The service consumes the whole press,
  long ones included (the screen-off hold is only known after AYN missed the DOWN), and shows the
  panel. This runs ahead of the interception gate. Unparked, the button reaches AYN untouched.
- Closing its card (swipe, D-pad up, Close all) never force-stops AYN's system app. Root writes a
  tap of the button's own key (`KEY_F24` on the device named `gpio-keys`), the service lets exactly
  that press through, and AYN closes its panel itself; Recents closing afterwards does not reopen it.
- Refreshes never re-hide it after a launch: AYN only shows a new panel when the user opens it.

### Parked frontend activities

Some frontends (Cocoon) draw their bottom screen as an ordinary activity rather than a
panel window. Covering it stops it, and the frontend reacts to that (Cocoon switches its top
screen to a single-screen layout). `FrontendParking` therefore moves such a task, before
anything else starts on the bottom screen, onto a private `VirtualDisplay` owned by this
process. The task stays resumed there; an `ImageReader` drains its frames. Detection is
`ParkingPolicy.candidate`: the same package is in front on both screens. No package list.
`TaskParker` (root entry point) only moves the visible front task of that package, only a
standalone movable root, and reads running root tasks because recent-task queries may omit
private displays.

The package's ordinary bottom Recents card restores the parked task to the requested display
without relaunching it; `launchFromRecents` routes it there, since `RecentsTaskLauncher`
would treat the parking display as a source screen needing a Home. The card preview is
captured just before parking: a parked task never becomes invisible, so it gets no system
snapshot of its own. A frontend can restart its bottom activity on the real bottom display
when its top activity starts (wake, return from a game). Window events then park the new
instance again. A reclaim within `ParkingPolicy.REPARK_MIN_INTERVAL_MS` of the last park
means the frontend reacts to any foreground change (iiSU); that package is then left alone
for the rest of the service session instead of looping.

Pausing interception and service teardown restore the parked task to the bottom screen.
If this process dies first, Android removes the private display and destroys its content;
the frontend recreates its bottom screen on its next start. `bottomDisplay()`, Close all and
`DpadOverlay` skip private displays, so the parking display is never treated as a screen.

## Testability seams, and where they stop

Three extractions exist purely so that logic became reachable from the JVM:
`BackGestureMachine` (key timing), `CardTouch` (touch arbitration) and
`UpdateChecker.parseVersion`/`parseRelease` (release parsing).

Root commands are observed one level lower: tests put `support/FakePService` in place of AYN's
Binder, so every call site's command and script is recorded unchanged, and `PServiceFailureContractTest`
freezes the failure side (`null` vs `""`, the 10 s retry, the unprivileged kill as fallback). See
[ADR 0005](decisions/0005-reuse-the-binder-seam-over-new-ports.md).

Everything that needs a real accessibility connection, a second display, or a real
window remains outside automated reach: `SwitcherOverlay` is only partly covered (about
36 % of its lines) and `SwitcherService` barely (about 24 %). Robolectric cannot provide
`windowsOnAllDisplays`, and there is no emulator image for a dual-screen device. Manual
testing on the AYN Thor is the only net under those paths — see
[`MANUAL-TESTING.md`](MANUAL-TESTING.md) for the procedures and
[`TESTING.md`](TESTING.md) for how they fit into the gates.

### Optional system Recents metadata and styling

Recents selection targets the screen where that menu opened. `RecentsTaskLauncher` uses
the existing pservice bridge to move and resume an existing standalone app task. It uses
`moveTaskToFront` with flags 0: `startActivityFromRecents` raises default-display Home even
for a background move or a same-display bottom launch. Android
reveals the source display's underlying task or its own home. If native Recents interposed
Home above the previous app, the mover restores that app after the target activation;
moving a background task retains the original source foreground as its fallback too. Home/secondary-home tasks
are excluded from this path. The bottom overlay delegates launches to the service. For
the top native menu, the service binds native card-click labels to unique, known task
identities and checks their current displays. Card indices cannot be system-task indices:
AYN can retain an older card order after a screen swap. Android's task-to-front events
remain a fallback; reselecting an already visible bottom task need not emit such an event
or close native Overview. Ambiguous labels are not forcibly assigned to a task. Without the
privileged bridge, the previous launch-intent behavior remains available.

Controller confirmation of an identified native card is consumed before Quickstep handles
the key. The service moves and activates that task directly on the top display, consuming
the matching key release too. Action buttons and unidentified cards keep their native key
handling. Touch clicks are reported after the native launch, so this observation path can
still briefly expose an app on its former display before moving it.

Hold-to-swap captures both foreground packages and resolves both task IDs before moving
either one, then uses this same move entry point sequentially. This preserves task identity
and lets Android notify the launcher about display changes.

**The app never moves windows between the screens on the user's behalf.** A task changes
screens only through a user action: hold-to-swap or picking a card in Recents. Closing,
leaving or launching an app moves nothing else, even when that leaves a frontend covered (for
example Companion over ES-DE after a swapped game is quit). An automatic return of the partner app
after a swap (`SwapReturn`) shipped in 1.4.0 and was removed again in favor of this rule.
`FrontendParking` is a deliberate exception: it never moves anything between the two screens,
only keeps a covered frontend's bottom task out of sight on a private display while the user
works there, and brings it back through its Recents card.

`TopRecentsFocus` completes AYN's paused Overview handoff with a non-Home intent before
keyboard initialization. A successful injection alone does not make navigation ready:
Quickstep must be resumed, its window focused, and a native card/action focused. Pooled
cards and the secondary drawer can retain focus flags that do not represent live top
input. After the handoff, the service retains the centered card node before Tab exits touch
mode and restores input focus to that exact node afterward. Initialization never injects a
directional key. Focus transactions invalidate older state queries before queued directions
are released. A queued focus worker checks that Overview is still active and the screen awake.

The bottom overlay still lists accessibility-observed packages. `RecentsStateReader` supplements
those entries with authoritative display IDs and the original launcher lock status through a
read-only root entry point. `RecentsTaskState` parses the snapshot for status indicators.
Back Button Hero's explicit Close all command overrides these locks within its configured scope;
it does not change the launcher's lock store or native Overview behavior.
No private launcher state is written. `RecentsBadges` loads the original OEM drawables/geometry;
`RecentsTheme` resolves the installed launcher's light/dark colors and dynamic system palette.

System task snapshots now supplement the foreground screenshot cache. The existing root entry
point exports snapshots to validated private cache files; loading and PNG decoding run off the UI
thread. `RecentsThumbnailCache` bounds retained snapshot bytes and keeps displayed bitmaps valid
after eviction. Overlay/session and task-identity checks reject obsolete image completions.

Successful system task snapshots also expire accessibility-history entries whose packages no
longer have a recent task. This runs before the bottom overlay opens and during its metadata
refresh, removing stale cards without killing apps. It does not add apps absent from the observed
history or change display scope. Failed/unavailable snapshots leave the history intact.

Bottom inventory queries include tasks with `FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS`, so
an observed companion app is not mistaken for a closed task. `RecentTaskQuery` uses the
same flags for existing-task activation and explicit removal. Native top Recents keeps
its original filtered queries. A package advertising Home may still have an ordinary
companion activity: that task can be resumed or moved like any other ordinary app.
Task metadata reports actual activity type/Home intent protection through `movable`.
Hold-to-swap queries excluded tasks too and builds both moves before executing either;
a protected task rejects the pair with a localized message. A package's Home intent
filters only restrict the unprivileged package-relaunch fallback when task identity
cannot be verified. The root mover independently checks the live task's type, intent
and standalone root before moving it. Protected-task rejection never falls back to a
package relaunch. Genuine Home/secondary-Home tasks remain excluded from task activation.
