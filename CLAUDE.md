# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**Read `AGENTS.md` first.** It is the shared, owner-maintained instruction file for all
coding agents and is updated more often than this one. This file adds Claude-specific
operational detail. Where the two disagree, `AGENTS.md`, executable configuration, and
current source win over prose here.

## What this is

An Android `AccessibilityService` that overloads the hardware **Back** button on
dual-screen handhelds (built for the AYN Thor) with extra gestures — single tap
(Back or bottom launcher), double-tap bottom Recents, hold-to-swap-screens,
tap-then-hold top Recents, reassignable Back + L1/L2/R1/R2 and Back + D-pad
shortcuts, and a configurable hold shortcut that pauses/resumes the app's key
interception. Privileged operations go through AYN's preinstalled `pservice` root
daemon; nothing extra is installed on the device. Single-module Kotlin app with JVM
and instrumented tests, no DI framework, and no build variants beyond debug/release.

**Features, layout, styling, and texts are final and validated.** The test suite,
goldens, and gates described below exist to freeze exactly that state. Changes to
behavior, visuals, or wording are out of scope unless explicitly requested — if a test
goes red after a production change, the change is wrong, not the test.

## Working agreement

The app is currently stable. Preserve that stability by changing as much as necessary
and as little as possible. Keep the established code style and the existing high
quality bar; do not use feature work as an excuse for unrelated cleanup, redesign, or
speculative refactoring.

The agent acts as the senior developer and the user as project owner. Surface decisions,
trade-offs, ambiguities, and risks that could materially affect behavior, compatibility,
UX, stored data, architecture, security, releases, or maintainability. Ask the project
owner and wait for the decision instead of guessing or making a consequential product
decision autonomously. Routine, reversible implementation details within an agreed
direction remain the senior developer's responsibility.

**Ask questions directly in chat, with numbered answer choices (1–n), and wait for the
owner's reply.** Do not use `AskUserQuestion` or any other follow-up input menu or
asynchronous question tool — the owner easily overlooks them. The owner writes in
German; answer in German, while code, comments, commits, and repo docs stay English.

Untracked or modified files in the worktree belong to the owner (currently e.g.
`site/` and `.github/workflows/pages.yml`). Preserve them and never fold them into a
commit for unrelated work.

**Self-contained compatibility.** Every compatibility fix must ship entirely in this
APK and work automatically after normal setup. Never patch another app's APK, code,
files, or settings. ADB probes are development verification only — never a
prerequisite for users. Solve shared problems through general window/task/input
behavior, not package-specific exceptions, unless an app-specific distinction is
demonstrated and explicitly agreed. Other open-source projects may inform design, but
never copy or transplant their implementation.

The ongoing work is to implement features and fixes without lowering quality. Consider
`FEEDBACK.md` relevant context, but do not treat it as a strict queue. When work touches
a feedback topic, remind the project owner about related open points and identify useful
same-area synergies. Do not silently expand scope: propose the synergy and let the
project owner decide whether it belongs in the current change.

## Development workflow with the project owner

During development, the physical Android device is normally connected over USB with
USB debugging enabled and is reachable through `adb`. Re-check the actual device with
`adb devices` rather than assuming a serial. After implementing and locally verifying a
feature or fix, deploy the newest debug build to the device so the project owner can
test exactly that version. The agent owns this build/install step; do not hand the owner
an APK-install task unless device access is unexpectedly unavailable.

After every install or reinstall, restore the test prerequisites through `adb` before
exercising the app: grant `POST_NOTIFICATIONS`, enable `SwitcherService` in
`enabled_accessibility_services`, and set `accessibility_enabled` to `1`. Use the fully
qualified component
`dev.steinhorst.backbuttonhero/dev.steinhorst.backbuttonhero.SwitcherService`; the short
component form is not accepted reliably on the Thor. Confirm through `dumpsys
accessibility` that the service is both enabled and bound before testing gestures,
window events, or switcher behavior. An APK being installed successfully is not enough
evidence that these prerequisites survived an instrumentation uninstall or reinstall.

When the deployed change is ready for owner verification, create a clearly separated
chat section headed **Test task for the project owner**. It must contain:

- one short sentence explaining the feature or fix being tested;
- a focused checklist for the new behavior or bug fix;
- targeted regression checks for existing behavior that the change could affect.

Keep this task specific to the current change. Do not turn every handoff into the full
manual test suite, but include every relevant interaction identified by the applicable
risk tier in `docs/MANUAL-TESTING.md`.

Prefer logs, tests, `adb` state, and the project owner's direct description over visual
inspection by screenshot. If a visual question remains, ask the owner to describe what
they see first. Capture or inspect screenshots only when no better verification path is
available and after asking the owner; the owner may then explicitly request screenshot
inspection.

Once a feature or fix is genuinely complete and its applicable automated and owner
verification has succeeded, create one clean, focused commit containing only that work.
Never bypass hooks. Do not push the commit. The project owner will explicitly announce
when release preparation starts; do not infer, tag, publish, or push a release early.

## Build & CI commands

```sh
./gradlew assembleDebug      # build debug APK
./gradlew assembleRelease    # build release APK (unsigned locally; CI signs via env vars)
./gradlew lintDebug          # Android lint — CI gate, run after any change
```

```sh
./tools/verify.sh          # the one gate: lint, unit tests, coverage, both builds, androidTest compile
```

`./tools/verify.sh` is the required check after every change — never call bare
`./gradlew` as the gate. It resolves JDK 17 itself, because **JAVA_HOME is not on
PATH on this machine**: a bare `java -version` fails, and the keg-only JDK lives at
`/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`. For one-off Gradle
calls outside the gate, pass it explicitly:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew <task> --console=plain
```

Git hooks (`.githooks/`, wired via `core.hooksPath` and self-installing through the
`installGitHooks` task) run compile + unit tests on commit and the full gate on push.
CI (`.github/workflows/ci.yml`) runs the same `verify` task on every push/PR to `main`.
Never bypass hooks with `--no-verify`.

JDK 17 required. `minSdk` 30, `compileSdk`/`targetSdk` 34, Kotlin 2.4.21, AGP 9.4.1,
Gradle 9.8.2. Android SDK has `platforms/android-34` + `build-tools/34.0.0` (plus `36.0.0`, which AGP 9 installs on its own),
**NDK `29.0.14206865`** (only to compile the root input helper `app/src/main/cpp/padhelper.c`; the gate fails without it) and
**no emulator image** — every `androidTest`/`connectedDebugAndroidTest` needs the
physical AYN Thor attached via `adb` (device id changes; re-check with `adb devices`).

## Expected results — what green looks like

Treat a deviation from these numbers as a regression to investigate, not a new baseline
to record:

- **295 JVM tests, 0 failures, 0 skipped** across 52 test classes (as of 2026-10-30). Count from
  `app/build/test-results/testDebugUnitTest/*.xml` after a gate run.
- **Lint: 0 errors.** `lint-baseline.xml` freezes **18** pre-existing warnings
  (`DataExtractionRules`, `ObsoleteSdkInt`, 5× `IconColors`, 5× `IconLauncherShape`,
  `MonochromeLauncherIcon`, 5× `GradleDependency` — four androidTest/test libraries plus
  the pinned `compileSdk = 34`). `warningsAsErrors = true` and
  `abortOnError = true`, so any *new* warning fails the gate. Those 18 are deliberately
  **not fixed** — each fix would be a visible change (new icon, etc.).
- **JaCoCo per-class line-coverage ratchets** (in `app/build.gradle.kts`, enforced by
  `jacocoCoverageVerification`) and their measured headroom:

  | Class | Minimum | Measured |
  |---|---|---|
  | `Prefs` | 1.00 | 1.0000 |
  | `DoubleTapDetector` | 1.00 | 1.0000 |
  | `LevelSteps` | 1.00 | 1.0000 |
  | `DpadDirection*` | 1.00 | 1.0000 |
  | `CardTouch` | 1.00 | 1.0000 |
  | `BackGestureMachine` | 0.98 | 0.9865 |
  | `AccessibilityAppSource` | 0.61 | 0.6250 |
  | `UpdateChecker` | 0.46 | 0.46–0.64 (network-dependent) |

  A further 24 classes (settings, navigation, Recents routing/state, panels, key gate,
  activation toggle) carry floors of 0.95–1.00 in a name→minimum map at the end of the
  rules; see `app/build.gradle.kts`.

  Several minima sit exactly at the measured value, so coverage must not drop at all.
  The ratchet may only ever rise.

  `UpdateChecker`'s measured value is not stable: the activity tests start its real
  network thread, so coverage swings between ~0.46 and ~0.60 depending on how far the
  thread gets. The 0.46 floor is the deterministic part — never raise it on one lucky run.

  Read these numbers per JaCoCo **class**, which counts nested classes separately:
  `UpdateChecker$Version` is its own entry. Aggregating a class with its nested types
  overstates the figure the rule actually enforces. Measured values come from
  `app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml`.
- **Release APK ~3.23 MB** (`app-release-unsigned.apk`), including the ~12 KB native helper
  `lib/arm64-v8a/libpadhelper.so`. Debug is much larger because LeakCanary is bundled there.
- **Release runtime classpath contains exactly two artifacts**: `kotlin-stdlib` and
  transitively `org.jetbrains:annotations`. Verify with:
  ```sh
  ./gradlew :app:dependencies --configuration releaseRuntimeClasspath | \
    grep -iE "junit|robolectric|espresso|uiautomator|leakcanary|jacoco"   # must print nothing
  ```

## Testing: what is protected, and what is not

Automated coverage is deliberately uneven. Know which side of the line you are on
before trusting the suite:

**Well protected.** `Prefs` (defaults + the three one-time migrations), `CardTouch`,
`DoubleTapDetector`, `BackGestureMachine` (all branches), resource DE/EN parity, and the
settings UI via **deterministic View-tree text snapshots** (44 golden files in
`app/src/test/resources/golden/`) covering all six pages across theme, locale and
compact/wide layout, every shoulder/D-pad row state, the change-preview dialogs, and an
accessibility inventory. A change to the top bar touches 38 of them at once.

**Barely protected — manual device testing is the only real net.**
`SwitcherOverlay` is at about **34 %** line coverage (119/349), `SwitcherService` about **13 %**
(123/946) and `FocusIndicatorOverlay` **0 %** (0/93). The panel host, Recents task
routing, and top-Recents controller focus are likewise device-only. The entire switcher overlay UI, multi-display targeting, thumbnail capture,
and physical key-event handling are unverified by any automated test. `BackGestureMachine`
exists precisely because Handler/SystemClock key timing cannot be exercised on the JVM;
the extraction moved the *decisions* under test but left the Android side effects out.
One Espresso smoke test (`SettingsActivitySmokeTest`, device-only, **not** part of the
gate) covers activity startup against manifest/resource failures.

After touching `SwitcherService`, `SwitcherOverlay`, `AccessibilityAppSource`, the
D-pad path (`padhelper.c`, `PadHelper`, `DpadOverlay`, `LevelControl`,
`BrightnessControl`), the controller-focus path (`ControllerFocus`, `FocusIndicatorOverlay`), or anything key-event related, a green gate proves little — exercise the gestures on the
device. Scale that effort to the change: docs, build config, or test-only edits need no
device pass.

**Golden-file rule:** never re-record a golden to turn a red test green. A red snapshot
after a production change means the change is wrong. Re-record only for an intentional,
explicitly approved UI change.

## Toolchain gotchas that will otherwise cost an hour

- **Unit tests run with `app/` as the working directory**, not the repo root. Paths in
  tests (`Golden`, `ResourceIntegrityTest`) are relative to `app/`.
- **Lint also analyzes test sources** (`lintAnalyzeDebugAndroidTest`/`-UnitTest`). With
  `warningsAsErrors`, a warning in *new test code* fails the gate. Fix the test code —
  do not disable the flag or add the finding to the baseline.
- **`DoubleTapDetector` uses `0L` as a "no previous press" sentinel.** With a test clock
  at 0, a double tap is never detected. Start fake clocks above 0. This is a fixture
  requirement, not a bug to fix.
- **`SettingsActivity.onCreate` starts a real network thread** (`UpdateChecker`), so
  every Robolectric activity test actually hits `api.github.com` and can wait out a 5 s
  timeout. Failures are swallowed, tests stay green — leave it alone. Only report it if
  an activity test becomes *flaky* with `UpdateChecker`/`HttpURLConnection` in the trace.
- **`service.windowsOnAllDisplays` is unavailable under Robolectric** (may return empty,
  null, or throw). Don't restructure `AccessibilityAppSource` to make it testable.
- **Robolectric qualifier order matters**: `en-night-xxhdpi`, not `en-xxhdpi-night`.
- **A locally built release APK is unsigned** (`app-release-unsigned.apk`) and
  `adb install` rejects it. Measure on the **debug** build; `isMinifyEnabled = false`
  in release, so code shape is practically identical. Read APK *size* off the release
  artifact without installing.
- **Every `adb install` recreates the pad clone.** The service restart restarts `libpadhelper.so`,
  and the pad reappears with a new device id. RetroArch then ignores the controller until its
  activity is paused and resumed — open `SettingsActivity` over the game and return to it
  (`am start --display 0 …`). Avoid needless reinstalls during a game.
- **AYN's assistant permanently covers the bottom display** (display id 4). Anything that must be
  seen — an activity, a screenshot target for inspection — goes on the top display (id 0).
  Physical display ids for `screencap -d`: top `4630946441858561667`, bottom `4630946482288158084`.
- **`adb shell am force-stop` cannot kill this app while its accessibility service is
  enabled** — the binding keeps the process alive, and `am start -W` reports
  `HOT`/`WARM` instead of `COLD`. A true cold-start measurement requires the user to
  disable the service in Android settings first (the app cannot do it itself).
- Performance snapshots are only meaningful with **2–3 real apps open** so the switcher
  holds actual cards and thumbnails; measuring with an empty switcher understates
  memory badly and yields too few frames for `gfxinfo` to mean anything.

## Release process

**Version tag convention**: release tags are `v<versionName>` in semver form, e.g.
`v1.1.0`, matching `defaultConfig.versionName`. `UpdateChecker.parseVersion` parses the
tag and compares it against the installed `versionName` field by field, so **the tag and
`versionName` must agree exactly** — the release workflow fails fast otherwise, because a
mismatch would make every installed app miss the release permanently.

`versionCode` is separate and must **also** rise on every release: Android itself uses it
to order upgrades and refuses to install a lower one. It no longer appears in the tag.

So a release means: bump `versionName` (semver) **and** `versionCode`, then tag
`v<versionName>`.

The first two releases predate this and were tagged `v1` (1.0.0) and `v2` (1.0.1) under
an older `v<versionCode>` scheme. `parseVersion` still accepts short forms, so `v1`
resolves to `1.0.0` and stays comparable — but note that a build installed *before* the
switch carries the old integer-only parser and cannot read a semver tag at all. Such an
install needs one manual APK update before automatic checks work again. Only the author's
own device was ever affected.

`.github/workflows/release.yml` fires on `v*` tags: it decodes
`RELEASE_KEYSTORE_BASE64`, builds a signed release APK from the
`RELEASE_KEYSTORE_PATH`/`_PASSWORD`/`RELEASE_KEY_ALIAS`/`RELEASE_KEY_PASSWORD` env vars,
publishes it as `thor-back-button-hero-v<versionName>.apk` (e.g. `thor-back-button-hero-v1.1.0.apk`), then deletes the keystore.
Signing is env-only by design — no keystore is or should ever be committed
(`*.keystore`/`*.jks` are gitignored).

Before tagging: `./tools/verify.sh` green locally, CI green on `main`, `versionCode`
bumped, tag name exactly `v<versionName>`, `RELEASE_KEYSTORE_*` secrets present, and the
S4 device procedure from `docs/MANUAL-TESTING.md` (including certificate and in-place
upgrade checks).

## The signing key — backup and disaster recovery

Android refuses to install an unsigned APK at all, and it refuses to *update* an
installed app unless the new APK carries the **same** signature. That makes the signing
key the one genuinely irreplaceable artifact in this project: lose it and no future
version can ever replace an installed copy — every user would have to uninstall first
and lose their settings. There is no recovery path, and GitHub secrets are write-only,
so they cannot be read back out.

**The two things that must exist in a backup** (password manager or encrypted backup —
never in the repo, never in plaintext cloud storage):

| Artifact | Detail |
|---|---|
| `release.keystore` | created 2026-10-24, 4374 bytes, format **PKCS12** |
| its password | one password, used for **both** store and key (PKCS12) |
| alias | `thor-back-button-hero` |

The working copy lives at the repo root (`release.keystore`), ignored via `.gitignore`.
That location is a convenience, not a requirement: **nothing reads the file from a fixed
path.** CI gets the key from the `RELEASE_KEYSTORE_BASE64` secret, and a local signed
build takes the path from an environment variable. The file can sit anywhere.

### After reinstalling the machine

Releasing does **not** need the keystore on disk. CI signs from the GitHub secrets, so
as long as those four secrets still exist, tagging keeps producing signed releases and
the keystore backup stays untouched insurance for the cases below.

To get the project building again on a fresh machine:

```sh
brew install openjdk@17                   # keg-only; verify.sh finds it on its own
brew install --cask android-commandlinetools
sdkmanager "platforms;android-34" "build-tools;34.0.0" "build-tools;36.0.0" "platform-tools" "ndk;29.0.14206865"

git clone git@github.com:denissteinhorst/thor-back-button-hero.git
cd thor-back-button-hero
echo "sdk.dir=$(brew --prefix)/share/android-commandlinetools" > local.properties
./tools/verify.sh                         # also installs the git hooks (preBuild → installGitHooks)
```

`local.properties` is gitignored and therefore never part of a clone — without it (and
without `ANDROID_HOME`) AGP cannot find the SDK. Expect the first run to be slow while
Gradle downloads its dependencies; after that the numbers under "Expected results"
apply.

**If the GitHub secrets are gone** (repo recreated, secrets deleted, moved to another
account) — restore them from the backup. This is the only step that genuinely requires
the backed-up file and password:

```sh
base64 -i /path/to/your/backup/release.keystore | gh secret set RELEASE_KEYSTORE_BASE64
gh secret set RELEASE_KEY_ALIAS -b "thor-back-button-hero"
gh secret set RELEASE_KEYSTORE_PASSWORD   # prompts; same password for both
gh secret set RELEASE_KEY_PASSWORD
gh secret list                            # expect all four
```

**To build a signed release APK locally** (rarely needed; CI normally does this) — point
the four environment variables at the restored file:

```sh
RELEASE_KEYSTORE_PATH=/absolute/path/to/release.keystore \
RELEASE_KEYSTORE_PASSWORD='…' \
RELEASE_KEY_ALIAS=thor-back-button-hero \
RELEASE_KEY_PASSWORD='…' \
./gradlew assembleRelease --console=plain
```

Without `RELEASE_KEYSTORE_PATH` the signing config is skipped entirely and the build
produces `app-release-unsigned.apk`, which Android will not install
(`INSTALL_PARSE_FAILED_NO_CERTIFICATES`) — that is expected, not a misconfiguration.

**To confirm a restored key is the right one**, compare the certificate fingerprint of a
published APK against a freshly signed one (`ANDROID_HOME` is not set on this machine;
the SDK path comes from `local.properties`):

```sh
/opt/homebrew/share/android-commandlinetools/build-tools/34.0.0/apksigner \
  verify --print-certs some-release.apk
```

The SHA-256 digest must be identical to the one in earlier releases. A different digest
means the wrong key — publishing with it would break updates for everyone who installed
a previous version.

### Release-readiness notes

- **The repo is public**, which is a hard requirement for the shipped feature set:
  `UpdateChecker` calls the releases API unauthenticated and by design carries no token,
  so a private repo would answer 404 and the update check would fail permanently for
  every user (the app sends no credentials, so even the owner would never get a
  notification). The in-app repository link and the published APK assets depend on
  public visibility too. **Do not flip this repo back to private** without accepting
  that the update feature and side-load distribution die with it.
- **Until the first release is tagged, `releases/latest` legitimately returns 404**, so
  the app shows "Couldn't check for updates". That is expected pre-`v1` and resolves
  itself once the release workflow has published once. Verified unauthenticated, exactly
  as the app calls it.
- **Branch protection is available but not yet configured** (`GET .../branches/main/protection`
  answers `404 Branch not protected`). Until it is set up to require the `verify` check,
  CI is advisory only and nothing mechanically stops a direct push to `main`.
- Note when debugging the update path: unauthenticated GitHub API calls are limited to
  **60/hour per IP**, and a local `curl` loop exhausts that quickly. A `403` with
  `rate_limit.core.remaining == 0` is throttling, not a visibility or code problem —
  check `https://api.github.com/rate_limit` before concluding anything.

Dependabot is configured (weekly, Gradle + GitHub Actions). The toolchain was moved to
AGP 9.4.1 / Kotlin 2.4.21 / Gradle 9.8.2 / Robolectric 4.17 in one verified step (golden
files untouched). Take future major jumps one at a time behind a green gate, never
alongside a release. Dependabot compares against the default branch, so it can propose
versions already in use after a local bump — check the workflow files before merging.

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

**`PService`** talks to AYN's own root daemon ("pservice", binder service `PServerBinder`,
the backend of Settings → Thor → "Run Script as Root"). Swiping a card away uses it for
`am force-stop` plus `TaskRemover`, a root `app_process` entry point that calls
`removeTask` like Recents' "Clear all". It only works while the Thor's SELinux is
permissive (AYN's default); otherwise `PService.cachedAvailable()` stays false and
`SwitcherService.killOnly` silently falls back to `killBackgroundProcesses`. Every
package name reaching it must pass `Shell.isPkg` (names come from other apps).
Like `SwitcherService`, it is device-only territory: no automated coverage.

**Controller focus** (`ControllerFocus`, `FocusIndicatorOverlay`): mirrors AYN's own tool —
`setprop persist.sys.input.dispaly`, the `screen_focus_lock` setting, and `input keyevent 60`
*without* `-d` (a `-d` to the game display makes the switch to top fail). The pictogram appears
on shoulder DOWN, on the top display only, and is tappable to open the indicator popup of that
button. Settings previews go through `SwitcherService.previewFocusIndicator`. Device-only
territory.

**Back + D-pad** (`PadHelper`, `DpadOverlay`, `LevelControl`, native `padhelper.c`): the
D-pad is a hat axis, invisible to `onKeyEvent`. With pservice, a native root helper hides
AYN's pad behind an identical uinput clone (same descriptor, controller number 1) and
withholds D-pad presses made while Back is held, so the game keeps focus; it follows
controller-style switches and restores the pad on every exit path. Without pservice,
`DpadOverlay` takes window focus while Back is held (games may pause) and the settings
show a warning. Full design: `docs/HELPER.md` — read it before touching any of this.
Device-only territory as well.

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
`shoulder_defaults_v3` is the model for defaults that change for never-touched
settings: an update pins the old value (L2/R2 stay unassigned), only a fresh install
or a reset gets the factory shortcuts (L2 screenshot top, R2 controller focus). The focus pictogram defaults
(`DEFAULT_FOCUS_POSITION` center right, `DEFAULT_FOCUS_DURATION_MS` 500) apply to every button.
Shoulder actions are stored as either a bare package name (an app to launch)
or an `@`-prefixed sentinel (`SHOULDER_HOME`, `SHOULDER_FLICK_LEFT`, etc.) —
`isShoulderApp()` distinguishes the two.

**`SettingsActivity`** + **`SettingsNavigation`** + **`SettingsUiKit`**: six hand-built
pages (`SettingsSection`: Basic, Shoulders, D-pad, Exceptions, Settings, Help), no
PreferenceFragment, no XML. `SettingsNavigation` owns the top bar (`settings_top_bar`:
menu button, page title, accessibility warning on the right), lazily builds and caches
pages, and switches between a 240 dp left panel at ≥ 720 dp width and a menu dialog
below that. Navigation icons are vector drawables `ic_settings_*` with SVG sources in
`art/settings-icons/`. `SettingsChanges` builds before/after previews for per-page and
whole-app defaults/unbind; `Prefs.applySettings` writes accepted plans atomically.
`ExceptionSettingsPage` is a **WIP UI draft with no runtime effect**. The activity still
shows service health and the "repair service" flow, because Android won't let an app
re-enable its own accessibility service.

**Bottom-screen panels** (`BottomPanels`, `PanelWindows`, `PanelConnection`,
`PanelWindowHost`): third-party frontends' full-screen presentation/overlay windows on
the bottom display are captured, hidden through a root window host, shown as their own
cards in bottom Recents, and restored on every exit path. **Recents task routing**
(`RecentsTaskLauncher`, `RecentTaskQuery`, `TaskMovePolicy`, `TopRecents*`) moves
existing tasks between displays via pservice instead of relaunching packages; this is
also what hold-to-swap uses now. The `Recents*` styling classes copy the installed
launcher's look for the bottom overlay. All device-only territory — read the matching
sections of `docs/ARCHITECTURE.md` and `docs/THOR-RECENTS.md` before touching them.
`WIP_BOTTOMSCREEN_OVERWRITES.md` documents a **rejected and reverted** attempt to raise
our windows above AYN's assistant; do not retry it without first addressing its notes.

**Activation toggle** (`ServiceToggleGesture`, `KeyInterceptionGate`): a configurable
Back-hold shortcut pauses/resumes all key interception; an unassigned shortcut
re-activates interception so the app can never stay stranded.

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
- Every package name passed to a privileged shell command goes through `Shell.isPkg`.

## Definition of done

1. The requested behavior is implemented with existing invariants preserved.
2. Focused tests cover the change where practical.
3. Goldens and baselines were not weakened to mask a regression; an approved visible
   change re-records goldens and the diff is reviewed as product output.
4. `./tools/verify.sh` passes.
5. The applicable manual-test tier is reported and, with device access, completed —
   debug build installed, prerequisites restored, **Test task for the project owner**
   handed over.
6. The handoff names changed files, verification performed, and any unrun device checks
   or residual risk. Commit only after owner verification; never push.
