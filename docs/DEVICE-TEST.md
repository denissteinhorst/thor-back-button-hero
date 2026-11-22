# On-device test suite

`tools/devicetest` runs every advertised feature on a connected AYN Thor. It presses the physical
controller programmatically and checks the effect on the device itself: system settings, the app in
front on each screen, windows, processes, files, media keys, messages and the input a game receives.
It complements the JVM gate (`./tools/verify.sh`) and replaces most of the clicking in
[MANUAL-TESTING.md](MANUAL-TESTING.md). It does not replace looking at the screen when a change is
visual.

## Requirements

- The Thor is connected over USB with USB debugging on and is the only device (or pass `--serial`).
- The **debug** build is installed. Its debug-only `DeviceTestReceiver` lets the suite configure the
  app, and `DeviceTestKeyActivity` stands in for a game. Neither exists in the release APK. The
  receiver requires `android.permission.DUMP`, which the adb shell holds and no ordinary app does.
- AYN's pservice is reachable (SELinux permissive, the Thor's default). The suite uses it to press
  buttons as root and to read and restore protected values.
- JDK 17 at the path `tools/verify.sh` uses, plus the Android SDK build tools. The suite compiles its
  small helpers (`tools/devicetest/java`) into a dex on first use.

## Running

```sh
python3 tools/devicetest/run.py --changed          # areas the branch's changes can affect (the default habit)
python3 tools/devicetest/run.py --only windows,ayn # chosen areas
python3 tools/devicetest/run.py --full             # everything in both layers, only on request (about 20 minutes)
python3 tools/devicetest/run.py --only sources --layers home   # chosen areas in dual mode's Home layer
python3 tools/devicetest/run.py --list             # areas and their tests
python3 tools/devicetest/run.py --restore          # put back what an interrupted run left behind
```

The close-action observers and the area selection also have host-only regression checks (not part of
`./tools/verify.sh`):

```sh
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=tools/devicetest python3 -m unittest discover -s tools/devicetest/tests
```

The harness reads Android's decimal separator for the device's locale, independently of the
resource language: English in Germany uses a comma; German in Switzerland uses a point.

`preflight` always runs first and stops the run if the service is not bound, root is unreachable or
the input helper cannot start. Each line prints `✓`, `✗` (failed), `!` (the test itself broke) or `–`
(skipped), with `⚠` warnings below. A JSON report is written to `app/build/devicetest/`; it records the
selected areas, the layers and every expected test. If the app crashes during a test, that test fails
with the exception, and the service is re-enabled before the next one.

The summary compares the executed tests with the expected ones. The exit code is `0` only when every
expected test ran and passed without a skip or warning; `1` on a failure, an error, or an expected test
that never ran (for example after a failed preflight); `3` when skips or warnings remain, which need a
recorded decision before the run counts as a pass. `--only` takes full registered area names
(`--list`); an empty list or an unknown name, such as a short alias, is rejected before the device is
touched.

`--changed` compares the branch against its merge base with `main` (or `--base <ref>`), including
uncommitted and untracked files under `app/src/main/`. It maps the changed files to areas through `FILE_AREAS` in
`tools/devicetest/dt/runner.py`. A change to `SwitcherService`, `Prefs`, `PService`, `Shell`, the
manifest or the accessibility config selects every area. So does any changed production file that maps
to no area (settings UI, texts, backup, a newly extracted class): the run prints it as unmapped rather
than silently selecting nothing. Add new source files to `FILE_AREAS` when you add them so the
selection can narrow again. Debug, test, harness and build paths are not mapped; select their areas
with `--only` or `--full`.

**Layers.** Tests registered with `layered=True` (every `sources` and `gestures` check of a shortcut)
run once per dual-mode layer: as written with Back, and again as "… [Home layer]" with dual mode on,
the pad's Home as the modifier and every binding and timing written to the Home layer's `dual_` keys
(`Device.layer`, `Device.binding`, `pad.modifier`). `--full` runs both layers, and so does `--changed`
when it selects every area or the `dual` area; otherwise only Back's layer runs. `--layers back`,
`home` or `back,home` overrides that.

## Safety: what the suite touches and restores

Before the first test, the suite:

- saves the app's settings file to `app/build/devicetest/owner-prefs-<run>.xml` and checks that it
  parses as a settings file (a failed read never counts as empty settings);
- records every system value a test may change: AYN's settings, the controller route and style
  properties, Do Not Disturb, microphone privacy, media volume and both screens' brightness;
- pulls the installed APK to `app/build/devicetest/installed-<run>.apk` and records its hash;
- writes `app/build/devicetest/state.json` with the run id. A second run refuses to start while this
  file exists;
- asks the app for a complete copy of its settings, tagged with the run id, and requires `OK <count>`
  matching the saved file. An existing copy is never overwritten: the run stops before changing anything.

It then resets the app to fresh-install defaults. Each test configures only what it needs. Afterwards,
including after failures and Ctrl-C, the restore:

1. asks the app to write its copy of this run back to disk (a synchronous commit) and requires
   `OK <count>`. Without a copy of this run (`NONE`, or `OTHER` for another run's copy, which is never
   adopted) it writes the saved XML file back instead, with the service off and the app stopped so no
   live preference cache overwrites it, and starts the service again;
2. reads the settings file back and compares every key, type and value with the saved file;
3. restores the system values and reads them back;
4. only then has the app drop its copy, and deletes `state.json`. The saved XML file is kept.

Any other reply, a write failure or a difference stops with an error and keeps `state.json`, the saved
XML file and the app's copy. `--restore` repeats the same steps and can be run again safely after an
interruption, a lost reply or a failed attempt.

**Sessions that install other builds** (trace builds, A/B arms) call `Device.install(apk)` inside the
transaction. It refuses an APK signed differently from the installed build (never uninstalls to work
around it), installs in place, checks the installed hash, restores the prerequisites (notification
permission, service enabled and bound) and then checks that both recovery copies survived: the app
must answer the read-only `status` with `KEPT <run> <count>`, and the host's settings file must still
hold that count. `status` is used rather than `snapshot`, which would take a fresh copy of the test
settings if the owner's copy had been lost. The restore first reinstalls the original build from the
host copy when another one is installed, then restores the settings as above, checks the original
build is installed again and only then deletes the host copy.

**Manual recovery.** If `--restore` keeps failing, the newest `owner-prefs-<run>.xml` holds the
settings as they were before the run. With the debug build installed: turn the service off in
Android's accessibility settings, run `adb shell am force-stop dev.steinhorst.backbuttonhero`, push the
file to `/data/local/tmp/`, copy it with `adb shell run-as dev.steinhorst.backbuttonhero cp
/data/local/tmp/<file> shared_prefs/back_button_hero.xml`, and turn the service on again. A run
refused because the app still holds a copy from an older run (`KEPT …`) and no `state.json` exists
can be cleared, after checking the settings, with `adb shell am broadcast -a
dev.steinhorst.backbuttonhero.DEVICE_TEST -p dev.steinhorst.backbuttonhero --es op drop`.

Side effects to know about:

- Settings (`com.android.settings`) and Gallery (`com.android.gallery3d`) are started, moved and
  force-stopped as stand-in apps. Neither holds owner data a force-stop could lose. Apps the owner
  had open stay open but may end up behind them.
- Screenshots and recordings created by the tests are deleted again through the media store. Files
  that existed before the run are never touched.
- A test media session briefly becomes the media button session, so no real player receives the
  media keys.
- The controller-style test recreates the pad. A running RetroArch then needs a pause and resume
  (see `CLAUDE.md`). Run full suites without a game open.
- Standby switches the screens off and wakes the device again. A secure lock screen would stop the
  run there. The Thor has none by default.

## Areas, in run order

The order keeps related checks together and the most disruptive ones last.

| Area | What is proved |
|---|---|
| `preflight` | Service bound, root, input helper, display ids, locale |
| `ayn` | Every AYN toggle twice with its notice; every option of performance, fan, refresh rate and stick lights; at the end: the choice prompt, screen off/on per screen, controller style |
| `levels` | Volume and brightness per target, mute, stick repeat |
| `media` | Play/pause, next, previous reach the media session; the status overlay's parts |
| `sources` | Back + every button, D-pad and stick direction, stick-click gestures; bound input is withheld from the game, unbound input reaches it |
| `gestures` | Back single, double, tap-and-hold and hold, timing rules, unassigned gestures, Home as Back |
| `dual` | Dual mode: Back + L1 and Home + L1 run their own layer, the first modifier owns the press, each modifier's gestures run its own layer, and with dual mode off the Home layer rests |
| `toggle` | Every pause/resume pair, too-short holds, input passing through while paused |
| `windows` | Launch on top/bottom, Home, Back, bottom switcher (open, controller launch, close all), top Recents (open, controller launch), swap apps, hold-to-swap, controller focus and its pictogram, switch active app, switch to last app (flipping, fast flipping on both screens, closed app, each screen keeps its own pair, after a swap), navigation flicks |
| `appcontrol` | Minimize, force-close, restart on the same screen, confirmation prompt, screen prompt, close all and close background apps, the close prompt |
| `capture` | Screenshots of top, bottom and both (checked by image size); a recording produces a valid MP4 |
| `macro` | A library macro plays buttons, D-pad and stick into the game in order |
| `gyro` | Gyro on/off and its notices; a gyro deflection reaches the game's right stick only while on, and with a hold button only while it is held ; tilting holds the stick until calibration starts, then a real stable sensor window must succeed (aiming rates are replaced, since a test cannot turn the device; calibration always uses physical readings) |
| `panels` | Quick settings, notifications, all ten settings shortcuts, power menu, standby with and without prompt, the first hotkey after waking |
| `exceptions` | An app exception blocks only its combos; with all Back gestures excluded, Back reaches the app natively |
| `service` | After the service is switched off and on, or the app process is killed, the helper restarts and hotkeys work; Home + Back held 5 s releases the pad (clone gone, native input reaches the game, Back + D-pad via the overlay) until the next restart |

## How things are observed

| Observation | Source |
|---|---|
| Presses | `sendevent` as root into AYN's pad through the input helper's own file descriptor, so events take the path of real presses. Without the helper, straight into the pad node |
| What a game receives | `DeviceTestKeyActivity` on the top screen logs every key and axis it gets (`PROBE_KEY`, `PROBE_AXIS`) |
| Media keys | A debug media session logs `MEDIA_KEY` |
| Messages | The service's `NoticeOverlay` (it replaced Android's toasts, which are rate-limited for background apps): its window `BackButtonHeroNotice` in `dumpsys window`, and the text from the `Notice:` log line |
| Closing apps | The original process ends, the task disappears and no activity remains in front. Android may preload a new background process; its existence alone does not mean the closed app reopened. |
| Apps and windows | `dumpsys activity` (resumed activity per display), `dumpsys window` (windows with owner and display) |
| Controller route | `screen_focus_lock`. AYN's route is independent of Android's focused display |
| Screen off | AYN covers a dark screen with its own window. Power and backlight stay on |

Face buttons are named by their print. In Xbox Style the Thor swaps their codes, which `Device.face()`
accounts for. The confirm button in the app's prompts and the switcher is the bottom one, printed B.
Android's `BUTTON_X` is always the left button (evdev 307).

## Visual baseline (raw frames)

`tools/devicetest/visual.py` captures the settings screen on the top display as raw frames and
compares runs pixel for pixel. Frames are screenshots: capture and inspection need the owner's
permission each time the scope changes (AGENTS.md). They stay private under
`refactoring/baseline/S5/frames/` and are never committed.

```sh
python3 tools/devicetest/visual.py capture --run NAME
python3 tools/devicetest/visual.py compare RUN_A RUN_B
```

- Capture uses `adb exec-out screencap -d <physical id>` without `-p`. `dt/frames.py` accepts only the
  verified Android 13 layout: a 16-byte header (width, height, format, colorspace), format 1
  (RGBA_8888) and exactly width × height pixels. Any other header, format or length is rejected.
- A state counts only once two consecutive frames are identical.
- The only mask is the status bar (System UI clock, signal and battery). Its height is read from the
  device's status-bar inset.
- The run sits inside the recovery transaction, so owner settings and system values are restored and
  verified.
- States: the welcome sheet, the overview with nothing assigned and with assigned bindings, and every
  rail page with the panel open and closed, and the controller-focus indicator dialog with each of
  its nine positions selected (31 per variant). Variants: light, dark (`cmd uimode`),
  German (the app's own language only, `cmd locale set-app-locales`) and font scale 1.3, so each run
  covers 124 states. Night mode, app language and font scale are saved first, then restored and
  verified; a setting that was absent stays absent. Rail entries are found by their accessibility
  descriptions.
- A capture waits until no toast window is shown. Toasts are transient, and in the debug build
  LeakCanary posts one while it dumps the heap.
- Not capturable as stable frames, so covered elsewhere:
  - the bottom Recents overlay (AYN's assistant covers the bottom display): JVM golden `switcher_*`,
    manual C1;
  - the focus pictogram (its preview lasts at most 1 s): JVM golden `overlay_focus`, manual check;
  - notices and the recording dot (transient or tied to a live recording): JVM goldens
    `overlay_notice` and `overlay_recording`, manual video procedure;
  - top Recents (it needs live third-party tasks): device checks and manual procedures.
- Evidence (2026-11-22, Thor): base vs base 21/21 identical, also across a reinstall; with all
  variants and the indicator dialog, 124/124 identical (84-state runs identical over three runs). A card corner
  radius of 16 → 12 dp changed 16 states. Leader-label gravity changed only the assigned overview, which
  is exactly what each change touches.
- A debug build is not byte-reproducible. To return to a candidate, keep and reinstall its APK file.

## Gesture traces (DEBUG_GESTURES builds)

`tools/devicetest/trace.py build [--rev REV]` builds a debug APK of a pinned revision with
`DEBUG_GESTURES` switched on, in a temporary git worktree, never in the checkout. The result is cached
under `refactoring/baseline/S6/trace-cache/` by source tree, trace patch and JDK, with the applied diff
and the APK hash beside it; a cached APK whose hash no longer matches is refused. Accepted source keeps
the flag off (contract A10). Both builds share the machine's debug key, so the trace build installs in
place over the normal debug build.

`dt/trace.py` reads the service's lines from logcat and normalizes only the fields that vary between
runs: pid and timestamps, measured durations (`t=`, `held=`, time since the previous DOWN) and input
device and task ids, which become symbols numbered by first appearance, and the battery and
temperature readings of the status notice. Configured durations, keys,
actions, displays, arguments and line order stay. Its host tests (`tests/test_trace.py`) show that a
wrong display, a missing, duplicate or reordered line and two ids collapsed into one still differ.

```sh
python3 tools/devicetest/trace.py capture --run NAME [--rev REV] [--probe PATCH]
python3 tools/devicetest/trace.py compare RUN_A RUN_B
```

`capture` builds (or reuses) the trace APK, installs it as an arm of the recovery transaction, warms
the new pad clone up until a Back press arrives, then records each scenario's normalized trace and its
observed effect (media keys) under `refactoring/baseline/S6/traces/<run>/`. The restore reinstalls the
original build. Scenarios use the gesture suite's timings (double 300 ms, tap-hold 600 ms, hold 800 ms),
plus two bands released at 700 ms, 100 ms clear of both thresholds: the trace prints *configured*
durations, not the delay a timer was actually posted with, so only those bands show a wrong delay on the
device. Exact delays and one-millisecond boundaries are covered on the JVM by `BackGestureThresholdTest`.
`--probe` applies a sensitivity-probe diff inside the temporary worktree only; it is never committed.

## Instrumented tests (androidTest)

`python3 tools/devicetest/instrument.py` builds the debug app and test APKs, installs both inside the
same recovery transaction as the suite, runs every `androidTest` class through `am instrument`, then
removes the test APK and ends on the original build with the owner's settings restored. It fails when
a test fails or when a test found in `app/src/androidTest` was not reported at all. The raw output is
kept in `app/build/devicetest/instrument-output.txt`. Like the suite it is not part of the gate.

## In-place update (F5)

`python3 tools/devicetest/upgrade.py` checks that an update keeps the service and the stored data. Inside
the recovery transaction it installs a fresh debug build, then for each of three frozen stores
(`populated.txt` written through the app's own SharedPreferences, the synthesized pre-1.1 file that
migrates to `legacy-v1.txt`, and the reset defaults of `fresh.txt`) it updates the build over itself
and reads the service state before anything repairs it: still enabled, bound again by itself, process
replaced. The typed raw entries must then equal the fixture exactly. `--apk` updates to another build of
the same signature instead; `--only` picks stores. The full store is written with the service off,
because one commit of every entry runs all of the service's preference listeners on the main thread and
outlasts the broadcast timeout. Results go to `app/build/devicetest/upgrade-result.json`.

## Resources, latency and lifecycle (F1–F4)

`python3 tools/devicetest/resources.py` measures a fresh debug build inside the recovery transaction:
`measure` (memory, own threads, descriptors and the pad helper after a fixed switcher workload, five
runs), `latency` (trigger to first frame, cold and warm) and `lifecycle` (five real disable/enable
cycles and a process kill, with LeakCanary's watches and completed heap analysis). `--only` picks one,
`--compare BASE.json CANDIDATE.json` judges two saved runs against the budgets in
`docs/PERFORMANCE.md` without touching the device. The device must not be in use: the workload starts
Settings, Clock and Gallery and wakes the screen.

## Adding a test

Add a function with `@test("<area>", "<what is proved>")` to the matching module in
`tools/devicetest/suites/`. Use `d.bind(pref, action, **options)` for hotkeys and `d.press(Seq()…)`
for input. A test of a modifier shortcut should be `layered=True` and press `d.modifier` (or
`Seq.with_back`/`combo`, which follow it) instead of `BACK`, so it covers both layers. Assert on
device state with `d.eventually`. Restore anything the test changes outside the app's preferences and the recorded system values (apps it started, files it created) in a
`finally` block. A new area also needs an entry in `FILE_AREAS` and in the table above.
