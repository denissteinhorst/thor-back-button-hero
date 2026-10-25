# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android `AccessibilityService` that overloads the hardware **Back** button on
dual-screen handhelds (built for the AYN Thor) with extra gestures — double-tap
app switcher, hold-to-swap-screens, tap-then-hold recents, and four
reassignable shoulder-button (L1/L2/R1/R2) shortcuts. No root, no companion
app. Single-module Kotlin app with JVM and instrumented tests, no DI framework,
and no build variants beyond debug/release.

**Features, layout, styling, and texts are final and validated.** The test suite,
goldens, and gates described below exist to freeze exactly that state. Changes to
behavior, visuals, or wording are out of scope unless explicitly requested — if a test
goes red after a production change, the change is wrong, not the test.

## Build & CI commands

```sh
./gradlew assembleDebug      # build debug APK
./gradlew assembleRelease    # build release APK (unsigned locally; CI signs via env vars)
./gradlew lintDebug          # Android lint — CI gate, run after any change
```

```sh
./tools/verify.sh          # the one gate: lint, unit tests, coverage, both builds
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
Gradle 9.8.2. Android SDK has `platforms/android-34` + `build-tools/34.0.0` (plus `36.0.0`, which AGP 9 installs on its own) and
**no emulator image** — every `androidTest`/`connectedDebugAndroidTest` needs the
physical AYN Thor attached via `adb` (device id changes; re-check with `adb devices`).

## Expected results — what green looks like

Treat a deviation from these numbers as a regression to investigate, not a new baseline
to record:

- **91 JVM tests, 0 failures, 0 skipped** across 16 test classes.
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
  | `CardTouch` | 0.90 | 1.0000 |
  | `BackGestureMachine` | 0.91 | 0.9130 |
  | `AccessibilityAppSource` | 0.60 | 0.6000 |
  | `UpdateChecker` | 0.46 | 0.4655 |

  Several minima sit exactly at the measured value, so coverage must not drop at all.
  The ratchet may only ever rise.

  `UpdateChecker`'s measured value is not stable: the activity tests start its real
  network thread, so coverage swings between ~0.46 and ~0.60 depending on how far the
  thread gets. The 0.46 floor is the deterministic part — never raise it on one lucky run.

  Read these numbers per JaCoCo **class**, which counts nested classes separately:
  `UpdateChecker` is 0.4655 while `UpdateChecker$Version` is its own entry. Aggregating a
  class with its nested types overstates the figure the rule actually enforces.
- **Release APK ~2.81 MB** (`app-release-unsigned.apk`). Debug is much larger
  (~6.8 MB) because LeakCanary is bundled there.
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
settings UI via **deterministic View-tree text snapshots** (golden files in
`app/src/test/resources/golden/`) covering the main page across theme and locale,
every shoulder-row state, three dialogs, and an accessibility inventory. These goldens
genuinely bite: changing a single visible string turns 7 tests red.

**Barely protected — manual device testing is the only real net.**
`SwitcherOverlay` is at **0 %** line coverage (0/147) and `SwitcherService` at **5.8 %**
(10/172). The entire switcher overlay UI, multi-display targeting, thumbnail capture,
and physical key-event handling are unverified by any automated test. `BackGestureMachine`
exists precisely because Handler/SystemClock key timing cannot be exercised on the JVM;
the extraction moved the *decisions* under test but left the Android side effects out.
One Espresso smoke test (`SettingsActivitySmokeTest`, device-only, **not** part of the
gate) covers activity startup against manifest/resource failures.

After touching `SwitcherService`, `SwitcherOverlay`, `AccessibilityAppSource`, or
anything key-event related, a green gate proves little — exercise the gestures on the
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
bumped, tag name exactly `v<versionCode>`, `RELEASE_KEYSTORE_*` secrets present, and a
device pass over the gestures and the switcher overlay (the part no test covers).

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
sdkmanager "platforms;android-34" "build-tools;34.0.0" "build-tools;36.0.0" "platform-tools"

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
