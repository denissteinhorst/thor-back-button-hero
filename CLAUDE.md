# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android `AccessibilityService` that overloads the hardware **Back** button on
dual-screen handhelds (built for the AYN Thor) with extra gestures — double-tap
app switcher, hold-to-swap-screens, tap-then-hold recents, and four
reassignable shoulder-button (L1/L2/R1/R2) shortcuts. No root, no companion
app. Single-module Kotlin app, no tests, no DI framework, no build variants
beyond debug/release.

## Build & CI commands

```sh
./gradlew assembleDebug      # build debug APK
./gradlew assembleRelease    # build release APK (unsigned locally; CI signs via env vars)
./gradlew lintDebug          # Android lint — CI gate, run after any change
```

There is no test suite (`./gradlew test` has nothing to run). Verifying a
change means building, installing, and exercising gestures on-device or
checking `adb logcat` with `DEBUG_GESTURES` flipped on (see below) — there's
no way to unit-test key-event timing logic in this repo currently.

JDK 17 required. `minSdk` 30, `compileSdk`/`targetSdk` 34, Kotlin 1.9.24, AGP
8.5.2. CI (`.github/workflows/ci.yml`) runs `assembleDebug` + `lintDebug` on
every push/PR to `main`. Release (`.github/workflows/release.yml`) fires on
`v*` tags, signs with secrets, and publishes the APK to GitHub Releases.

**Version tag convention**: release tags are `v<versionCode>` (e.g. `v2`),
matching `defaultConfig.versionCode` in `app/build.gradle.kts`.
`UpdateChecker.kt` parses that tag format to detect new releases — bump
`versionCode` before tagging, and keep the two in sync.

## Architecture

Everything lives in one package: `dev.steinhorst.backbuttonhero`. Two
Android entry points declared in the manifest: `SettingsActivity` (launcher
UI) and `SwitcherService` (the accessibility service doing the real work).

**`SwitcherService`** is the core. It intercepts `KEYCODE_BACK` and the four
shoulder-button keycodes via `onKeyEvent`, and is a state machine over a
single Back press's DOWN/UP sequence:
- A `Handler`-scheduled `Runnable` for each delayed outcome (`holdRunnable`,
  `tapHoldRunnable`, `singleTap`), posted/cancelled as the press evolves.
- `pressFollowsTap` is latched at DOWN time (not recomputed at UP) because a
  pending `singleTap` callback can fire on its own mid-hold before the UP
  event arrives — see the comment at its declaration for why this matters.
- `DoubleTapDetector` is a tiny separate press-interval tracker consulted
  independently of the single/hold timers.
- Shoulder buttons only act while `backDown` is true; otherwise the event is
  left unconsumed so games/apps see normal input.
- `DEBUG_GESTURES` (const, off by default) gates verbose `dlog()` tracing of
  every DOWN/UP decision — flip it on when debugging timing issues, don't
  leave it on (noisy on every Back press).

**`AccessibilityAppSource`** (implements `AppSource`) is the only source of
"what apps are running" — a normal app can't query other apps' tasks, so it
reconstructs a most-recently-used list per display purely from
`windowsOnAllDisplays` seen by the accessibility service. Consequences:
- Only apps seen *since the service started* are known.
- A fixed `ignored` set filters out launcher/systemUI/OEM dual-screen
  packages so they never appear in the switcher.
- Thumbnails are cached separately in-memory (`thumbnails` map) and captured
  opportunistically (see `captureFrontApp` in `SwitcherService`), not derived
  from window state.

**`SwitcherOverlay`** draws the switcher UI as an `AccessibilityOverlay`
window bound to a specific display's `Display` via
`createDisplayContext(...).createWindowContext(...)` — this is what keeps the
overlay pinned to the bottom screen only. Built entirely with manual
`View`/`LinearLayout` code (no XML layouts, no Compose). Card
tap-vs-swipe-up gesture discrimination is hand-rolled in `CardTouch`.

**`Prefs`** wraps `SharedPreferences` and is also where one-time preference
*migrations* live (see the `init` block: `navigation_flick_defaults_v1`,
`navigation_flick_directions_v2`, and the removed `@swap_screens` shoulder
action). When changing a default or renaming a stored key, add a guarded
migration here rather than silently breaking existing installs — follow the
existing pattern of a versioned boolean flag guarding a one-time rewrite.
Shoulder actions are stored as either a bare package name (an app to launch)
or an `@`-prefixed sentinel (`SHOULDER_HOME`, `SHOULDER_FLICK_LEFT`, etc.) —
`isShoulderApp()` distinguishes the two.

**`SettingsActivity`** is a single hand-built settings screen (no
PreferenceFragment, no XML), built programmatically in `build()`. Shows
accessibility-service health and has a "repair service" flow because Android
won't let an app re-enable its own accessibility service.

**`UpdateChecker`** polls the GitHub Releases API directly (`HttpURLConnection`
+ manual JSON parsing, no HTTP/JSON library) and posts a notification linking
to the release page. No in-app APK download/install by design (would need
`REQUEST_INSTALL_PACKAGES` and a download manager — considered unnecessary
surface for a side-loaded single-user app).

## Conventions worth preserving

- No layout XML, no Compose, no third-party dependencies — everything is
  framework Kotlin + hand-built Views. Keep new UI consistent with that.
- Comments in this codebase explain *why*, not what — timing quirks,
  Android API gotchas, OEM-specific workarounds. Match that style; don't add
  narrative comments describing obvious code.
- Display-targeting logic always distinguishes `Display.DEFAULT_DISPLAY`
  (top screen) from the dynamically-detected bottom display (see
  `bottomDisplay()`: the non-default display with the highest ID). Don't
  hardcode display IDs elsewhere.
