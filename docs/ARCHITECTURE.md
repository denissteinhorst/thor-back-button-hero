# Architecture

How the implementation is divided, how a gesture travels through it, and which
constraints shaped it. For a file-by-file inventory see
[`PROJECT-STRUCTURE.md`](PROJECT-STRUCTURE.md); for the safety net around all of it,
[`TESTING.md`](TESTING.md).

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

## Components

### `SwitcherService` — runtime coordinator

Receives key events and window-change events, and owns every Android side effect:
global actions (Back, Home, Recents), display-aware activity launches, screenshots,
and the screen swap. It holds the `AccessibilityAppSource`, creates the
`SwitcherOverlay`, and opportunistically captures thumbnails of the front app.

What it deliberately does **not** own is the Back-press decision — that moved into
`BackGestureMachine`. Shoulder buttons are handled here directly: they act only while
Back is held, and an unassigned button's event is left unconsumed so games and apps see
normal input.

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
top system Recents, and an empty state. Close all is bottom-only in this UI; the service
still retains its both-screens close path for other callers. `CardTouch` arbitrates each
card's touches — tap opens, upward swipe past a threshold closes, horizontal drags go to
the scroller — and is extracted so that arbitration is testable without a real window.

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

### `Prefs` — storage and migrations

Wraps `SharedPreferences` and owns the schema, the defaults, and the one-time
**migrations** in its `init` block. Factory shortcuts (L2 screenshot, R2 controller focus) are
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

`ShoulderButton` and `DpadDirection` both implement `ComboButton`, so `Prefs` stores every combo
button's action, launch screen and level target the same way.

### `SettingsActivity` + `SettingsUiKit`

Six hand-built pages share framework Views; no `PreferenceFragment` or layout XML.
`SettingsNavigation` lazily builds and caches pages, using a left panel at 720 dp and
above and a menu dialog below that width. Explicit navigation starts content at the top;
activity recreation preserves the active page and scroll position. `SettingsUiKit`
provides shared cards, branch-connected dependent controls and aligned range sliders.
Accessibility warnings appear at the right of the top bar; root-helper/setup warnings
remain below it. Service health still distinguishes on / unresponsive / off and offers
the existing Android accessibility-settings repair flow.

`SettingsChanges` captures immutable before/after snapshots for page defaults/unbind and
whole-app reset/unbind. Confirmations list actual changes; `Prefs.applySettings` rejects
stale previews and writes accepted plans atomically. Whole-app unbind clears shoulder,
D-pad and activation shortcuts, preserving primary gestures. An unassigned activation
shortcut reactivates temporarily paused interception so the app cannot remain stranded.

`ExceptionSettingsPage` is a WIP UI draft only: installed-app search and a ten-column
checkbox matrix have no runtime effect or persistent preferences. Its draft survives
navigation and saved-state recreation. Help and Exceptions have no defaults/unbind links.


### `UpdateChecker`

Talks to the GitHub Releases API directly with `HttpURLConnection` and manual JSON
parsing — consistent with the no-dependency rule. It parses the release tag as semver and
compares it against the installed `versionName`, then posts a notification linking to the
release page. There is deliberately no in-app download or install, which would require
`REQUEST_INSTALL_PACKAGES` and a download manager.

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
SwitcherService.onKeyEvent ──────────────► shoulder button held with Back?
      │                                          │
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
| Single tap | `GLOBAL_ACTION_BACK`, or the bottom screen's launcher — user's choice |
| Double tap within the window | Switcher overlay on the bottom screen |
| Hold for the configured duration | Swap the apps on the top and bottom screens |
| Tap, then hold again within the tap-hold window | System Recents on the **top** screen instead of a swap |
| Hold + L1/L2/R1/R2 | That button's configured action, on its configured screen |

### Why `pressFollowsTap` is latched at DOWN

`pressFollowsTap` is captured when the key goes down, not recomputed on the way up. A
pending single-tap callback can fire on its own mid-hold, before the UP event arrives —
recomputing later would read state the earlier callback already changed, and the
tap-then-hold gesture would intermittently behave like a plain hold. The comment at its
declaration records this; it survived the extraction into `BackGestureMachine`
deliberately and should not be "simplified".

## Display targeting

All display logic distinguishes `Display.DEFAULT_DISPLAY` (the top screen) from the
dynamically detected bottom display — `bottomDisplay()` returns the non-default display
with the highest ID. **Do not hardcode display IDs anywhere else.** Shoulder actions that
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

## Testability seams, and where they stop

Three extractions exist purely so that logic became reachable from the JVM:
`BackGestureMachine` (key timing), `CardTouch` (touch arbitration) and
`UpdateChecker.parseVersion`/`parseRelease` (release parsing).

Everything that needs a real accessibility connection, a second display, or a real
window remains outside automated reach: `SwitcherOverlay` has no automated coverage and
`SwitcherService` has almost none. Robolectric cannot provide
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
