# Root input helper — Back + D-pad without pausing the game

Back + D-pad shortcuts (default: brightness on up/down, volume on left/right) need a small root
helper. This document explains why, how it works, what it touches on your device, and how it stays
out of the way. Everything here is implemented in this repository. The helper is
[`app/src/main/cpp/padhelper.c`](../app/src/main/cpp/padhelper.c), and the app side is
`PadHelper.kt`, `DpadOverlay.kt`, `LevelControl.kt` and `BrightnessControl.kt`.

## Why a helper exists at all

The Thor's D-pad sends **no key events**. AYN's controller reports it as a hat axis (`ABS_HAT0X` /
`ABS_HAT0Y`, values −1/0/+1), in every controller style. An `AccessibilityService` only gets *key*
events through `onKeyEvent`, so it never sees the D-pad. The shoulder buttons and Back do arrive,
because they are real keys.

Android only delivers joystick events (the hat axis) to the window that has **input focus**. Without
root, the only way to catch the D-pad is to take focus away from the foreground app. The app does
this while Back is held: it places an invisible 1×1 focusable overlay (`DpadOverlay`). It works, but
the game loses window focus for that moment, and many games and emulators **pause** when that
happens.

Things that were checked and do not help on the Thor:

- **`AccessibilityServiceInfo.setMotionEventSources`** lets a service receive joystick events. It is
  Android 14 API; the Thor runs Android 13, and the method does not exist on the device.
- **Controller styles.** "Xbox Style", "Standard Style" and "Ban on Use" all report the D-pad as a hat
  axis or not at all.
- **An overlay on the other screen.** Controller input goes only to the display that was used last.
- **Reading the pad without hiding it from Android.** The game would still receive Back + D-pad as
  ordinary input.

## How the helper solves it

The helper sits **between AYN's pad and Android**, below the level where focus matters:

1. **It opens AYN's pad**, the evdev node that has the hat, Back and gamepad buttons, for example
   `/dev/input/event9`.
2. **It hides that pad from Android.** It removes the *node* `/dev/input/event9`; its own open file
   handle stays valid. Android's `EventHub` watches `/dev/input` and drops the device, which frees its
   controller number and its descriptor. No exclusive grab (`EVIOCGRAB`) is needed, so other readers
   of the pad, such as AYN's own services, keep working.
3. **It re-offers the pad as a uinput clone that is indistinguishable from the original:**

   | Property | Original | Clone |
   |---|---|---|
   | Name | e.g. "Xbox Wireless Controller" | same |
   | Bus, vendor, product, version | e.g. USB 2020:0112 v0 | same |
   | Key bits, axis bits, axis ranges, properties | — | copied 1:1 |
   | Android descriptor | e.g. `6dc63f81…` | **same** (derived from the above) |
   | Android controller number | 1 | **1** |

   Games and emulators therefore keep their existing button mappings and player-1 assignment. AYN's
   pad has no force feedback, so the clone offers none either.
4. **It forwards every event unchanged**, with two deliberate switcher modes. A D-pad press that
**starts while Back is held or while the app's own bottom switcher is open** is withheld from the
clone and sent to the app instead, as a line like `HAT X -1`. Top Recents captures directions too.
The service buffers presses until Overview has a focused TaskView, because the first native direction
otherwise only exits Android's touch mode. Navigation then returns over the same socket (`NAV <key>`)
and is emitted as hat-axis presses by the existing clone, without a shell per direction. Each replay
spans two frames so Android cannot coalesce its down and neutral into a lost press. A keyboard Tab
initializes focus once after Overview opens (and after a dismissal), with queued directions released
after its delivery completes. A focus failure never closes Overview.
The service tracks the card/action rows: Up from actions returns to cards; Up from cards dismisses
only the current card using its real geometry. After a dismissal it restores keyboard focus before
releasing queued navigation. TOP takes precedence over held Back. A captured press stays captured
until release, even if Back is let go or the switcher closes first.
5. **The app runs the configured action** (`SwitcherService.handleDpad`), just as it does for a
   shoulder-button combo. The game never sees the press and never loses focus.

### Controller style switches

AYN's style switch (Xbox Style / Standard Style / Ban on Use) destroys its pad and creates a new one
with a different name and product id:

| Style | Name | Product id | D-pad |
|---|---|---|---|
| Xbox Style | Xbox Wireless Controller | 2020:0112 | hat |
| Standard Style | Odin Controller | 2020:0111 | hat |
| Ban on Use | None Controller | 2020:0111 | no input at all |

The helper notices when its pad goes away (`read()` fails with `ENODEV`) and tears the clone down.
It then takes over the new pad with its new identity. "Ban on Use" exposes no gamepad, so the
helper waits until a real pad appears again.

### Brightness and bottom-screen volume

Two of the default actions also need root, independently of the D-pad:

- **Brightness per screen.** The bottom screen's brightness is per-display state that only
  `IDisplayManager.setBrightness(displayId, …)` changes. That call needs a permission normal apps do
  not get; AYN's own "both screens" slider uses the same call. `BrightnessControl` is a short-lived
  root entry point that steps one or more displays in 15 equal steps.
- **Bottom-screen volume.** This is AYN's `secondary_screen_volume_level` setting. AYN applies it as
  soon as it changes, but Android does not let a normal app write AYN's settings, so it is set
  through the root shell.

The top screen's volume is ordinary `STREAM_MUSIC` and needs no root.

## Where root comes from

Neither the app nor the device is rooted. The Thor's firmware ships a root daemon, **`pservice`**
(uid 0, started at boot). It backs Settings → Thor → "Run Script as Root" and exposes the binder
service `PServerBinder`. `PService.kt` talks to it; swipe-to-close in the switcher already uses it.

The helper is started like this (see `PadHelper.launch`):

```
<nativeLibraryDir>/libpadhelper.so <package>.padhelper
```

pservice runs commands through `popen` and reaps the shell immediately. The command therefore goes
into a short script that starts the helper in the background and then sleeps for a second, which
gives the child time to detach.

This only works while the Thor's **SELinux is permissive, which is AYN's factory default** ("Force
SELinux" off). It is the same requirement swipe-to-close has.

## Safety

- **Own code, no dependencies.** The helper is written from scratch in C against the Linux kernel
  UAPI (`linux/input.h`, `linux/uinput.h`) and libc. It contains no third-party or proprietary code.
  The Gradle task `buildPadHelper` compiles it with the NDK's clang; there is no CMake and nothing
  is prebuilt. It ships inside the APK as `lib/arm64-v8a/libpadhelper.so`, so that Android installs
  it as an executable.
- **Authenticated channel.** The helper connects to an abstract Unix socket, which has no file
  permissions. The app therefore accepts only peers whose credentials say **uid 0**; anything else
  is disconnected.
- **It only runs when needed.** If no D-pad direction has an action and controller navigation for
  Recents is off, the helper is stopped and the pad stays untouched. Keeping controller navigation
  on keeps the helper warm so opening either switcher never recreates the pad under the first input.
- **The pad always comes back**, in every case:
  - **App or service stops:** the helper sees its socket close, destroys the clone, and recreates
    the node with the original mode, owner and SELinux label. Android re-adds the pad with
    controller number 1.
  - **Helper crashes:** the kernel removes the clone automatically. On its next start the helper
    first recreates any `/dev/input` node that the kernel still has a device for.
  - **Emergency release:** holding **Home + Back for 5 seconds** makes the helper hand the pad back
    and tells the app not to restart it until the service restarts.
  - **Style switch:** switching the controller style creates a fresh pad and node in any case.
- **A restarted helper recreates the pad.** Whenever the helper starts again (an app update, a
  reinstall, a service restart), Android sees the pad vanish and reappear with a new device id.
  RetroArch can ignore the new device until its activity has been paused and resumed, which looks like
  dead controls. Opening any other app over the game and returning to it fixes it.
- **Bounded restarts.** The app relaunches a helper that died, after a short delay and at most five
  times in a row without a successful take-over.

## Without pservice

If pservice is not reachable (SELinux enforcing, or a future firmware), the helper cannot run, and
the app falls back to `DpadOverlay`:

- **Back + D-pad still works, but games may pause** while Back is held, as described above.
- **Brightness and bottom-screen volume are unavailable**; the actions show "not available". The top
  screen's volume still works.
- The **settings screen shows a warning banner** at the top whenever a D-pad direction has an action
  but pservice is not reachable.

Everything else in the app works with or without pservice.

## Testing

The step arithmetic (`LevelSteps`), the hat-to-direction mapping (`DpadDirection.fromHat`) and the
settings (`Prefs`) are unit-tested with 100 % line coverage. The helper itself, the overlay fallback
and the root calls are device-only. They are covered by the manual procedure in
[`MANUAL-TESTING.md`](MANUAL-TESTING.md), including a pass through every controller style.

## Credit

The approach — hiding the pad behind a uinput clone that withholds a hotkey combo — was first shown
by [Thor Wayfinder](https://github.com/Thor-Wayfinder/thor-wayfinder). No code was taken from it.
This implementation differs where that kept things simpler:

- it hides the node instead of using an exclusive grab;
- it frees controller number 1 deterministically, without a race;
- it forwards events as they arrive instead of through a mapping engine.
