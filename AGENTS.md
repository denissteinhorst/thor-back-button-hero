# AGENTS.md

Repository instructions for coding agents. Read this file before making changes. Use
the more detailed documents linked below when working in their area.

## Project at a glance

Thor Back Button Hero is a small, single-module Android app for the dual-screen AYN
Thor. Its `AccessibilityService` extends the hardware Back button with timed gestures,
an app switcher, screen swapping, and configurable Back + shoulder/D-pad shortcuts.

- Package/application ID: `dev.steinhorst.backbuttonhero`
- Production language: Kotlin, plus one small C input helper
- UI: framework Views built in Kotlin; no Compose and no layout XML
- Android entry points: `SettingsActivity` and `SwitcherService`
- Build variants: `debug` and `release`
- Toolchain: JDK 17, AGP 9.4.1, Kotlin 2.4.21, Gradle 9.8.2
- Android: min SDK 30, compile/target SDK 34, NDK 29.0.14206865
- Tests: JUnit 4 + Robolectric; one physical-device instrumentation smoke test

The current features, visual design, and wording are intentionally frozen. Do not
change behavior, layout, styling, strings, defaults, or assets unless the task asks for
it. A production change that breaks an existing test or golden is normally the bug.

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

Ask questions directly in chat with numbered answer choices (1–n), and wait for the
project owner's reply before continuing work. Do not use follow-up input menus or
asynchronous question tools; the owner easily overlooks them.

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

## Start here

Before editing, inspect the relevant implementation and its tests. Then use these docs
as needed:

- `CLAUDE.md`: detailed operational knowledge, toolchain traps, coverage baselines,
  release signing/recovery, and component notes
- `docs/ARCHITECTURE.md`: runtime flows and component collaboration
- `docs/PROJECT-STRUCTURE.md`: file-by-file map
- `docs/TESTING.md`: automated test strategy and exact commands
- `docs/MANUAL-TESTING.md`: risk tiers and device procedures
- `docs/HELPER.md`: mandatory reading before changing the D-pad/root-helper path
- `docs/MAINTENANCE.md`: dependencies, releases, and repository maintenance
- `README.md`: user-visible product contract

Prefer executable configuration and current source over prose when they disagree. Do
not casually rewrite unrelated documentation while implementing a focused change.

## Repository map

- `app/src/main/java/dev/steinhorst/backbuttonhero/`: one flat production package
- `app/src/main/cpp/padhelper.c`: native root input helper, compiled by Gradle
- `app/src/main/res/`: English/German strings, icons, accessibility config
- `app/src/test/`: JVM/Robolectric tests and snapshot helpers
- `app/src/test/resources/golden/`: deterministic View-tree snapshots
- `app/src/androidTest/`: physical-device startup smoke test
- `app/build.gradle.kts`: Android config, signing, native helper, lint, JaCoCo ratchets
- `build.gradle.kts`: root plugins, git-hook installation, aggregate `verify` task
- `tools/verify.sh`: canonical local quality gate
- `.github/workflows/`: CI and signed release publication

The untracked or modified files in the worktree belong to the user. Preserve them and
do not fold unrelated changes into the task.

## Architecture invariants

### Gesture and key handling

`SwitcherService` coordinates Android side effects and physical keys. Back timing
decisions live in the side-effect-free `BackGestureMachine`; `DoubleTapDetector` tracks
press intervals independently. Preserve the DOWN-time latch of `pressFollowsTap`: the
pending single-tap callback may fire before the later UP event.

Shoulder actions run only while Back is down. Otherwise shoulder events must remain
unconsumed so the foreground game/app receives them. `PhysicalKeyTracker` deduplicates
the Thor's mirrored controller devices. `DEBUG_GESTURES` must be `false` in committed
production code.

### Displays and overlays

The top screen is `Display.DEFAULT_DISPLAY`. Discover the bottom screen dynamically;
never introduce hard-coded logical display IDs. Display-bound overlays use a display
context/window context so they stay on the intended screen.

`AccessibilityAppSource` reconstructs per-display recents only from accessibility
windows observed since service startup. Its thumbnail cache is populated separately by
`SwitcherService`; do not treat it as a system task list.

### Preferences and compatibility

`Prefs` owns the stored schema, defaults, and one-time migrations. When renaming a key
or changing a default for existing installs, add a versioned, guarded migration rather
than silently changing persisted behavior. Preserve the distinction between app package
names and `@`-prefixed action sentinels.

### D-pad and privileged operations

The D-pad is a hat axis and does not arrive through normal key events. `PadHelper` plus
`padhelper.c` interposes an identical uinput controller through AYN's `pservice` root
daemon; `DpadOverlay` is the focus-taking fallback. Read `docs/HELPER.md` completely
before editing this path. Preserve every controller-restoration exit path.

All package names passed to privileged shell commands must be validated through
`Shell.isPkg`. Keep the controller-focus command sequence OEM-compatible, including
`input keyevent 60` without a display argument.

### UI and dependencies

Keep UI code consistent with the existing hand-built framework Views. Do not introduce
Compose, layout XML, DI, or a runtime library without explicit justification and user
approval. Comments should explain why an Android/OEM/timing workaround exists, not
narrate obvious code.

English resources are the source of truth; maintain German parity. User-visible changes
must be deliberate because snapshot and resource-integrity tests freeze the output.

### Self-contained compatibility

All compatibility fixes must ship entirely in this app's APK and work automatically
after its normal setup. Never patch another app's APK, code, files, or settings to make
our integration work. Device-side probes are development verification only; no probe,
ADB command, companion patch, or extra manual configuration may be a prerequisite for
other users. Adapt how our app observes and controls existing windows/tasks instead.

Solve shared compatibility problems through general window, task, and input behavior,
not package-specific exceptions, unless an app-specific distinction is demonstrated
and explicitly agreed. Keep effects on other apps and existing workflows in view.

Implement our own solutions. Other open-source projects may inform investigation and
design, but never copy their implementation verbatim or transplant it into this app.

## Build and verification

Use the wrapper, never a system Gradle. The canonical post-change gate is:

```sh
./tools/verify.sh
```

It resolves the local JDK 17 installation and runs lint, JVM tests, JaCoCo verification,
debug/release builds, and androidTest compilation. It does not run device tests. Do not
substitute a bare Gradle task for this final gate, and do not bypass git hooks.

For targeted investigation before the final gate, set JDK 17 explicitly on this machine:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew <task> --console=plain
```

Current expected baseline:

- 321 JVM tests in 54 test classes; 0 failed, 0 skipped
- lint: 0 new findings; the checked-in baseline contains 18 intentional warnings
- all per-class JaCoCo ratchets in `app/build.gradle.kts` pass
- debug and unsigned local release APKs build
- debug androidTest APK compiles

Treat a baseline deviation as something to investigate, not something to normalize.
Never lower a coverage ratchet, weaken lint, or expand the lint baseline merely to make
the gate green.

### Test traps

- JVM tests run with `app/` as their working directory.
- Lint analyzes test and androidTest sources too, with warnings as errors.
- Start fake clocks above `0L`; `DoubleTapDetector` uses zero as its sentinel.
- Robolectric qualifiers must be ordered like `en-night-xxhdpi`.
- `SettingsActivity` starts the real `UpdateChecker` network thread in Robolectric;
  failures are swallowed and a timeout can add about five seconds.
- `windowsOnAllDisplays` is unreliable/unavailable under Robolectric.
- A local release APK is unsigned unless all release-signing environment variables are
  supplied; install debug for ordinary device testing.

### Golden files

Do not re-record a golden to hide a regression. Update snapshots only when the user has
explicitly requested the corresponding visible UI/text change, and review the snapshot
diff as product output.

## Manual device testing

Automated tests do not meaningfully exercise the accessibility connection, real key
events, two-display routing, the switcher UI, the root helper, or most overlays. Use the
risk tiers and numbered procedures in `docs/MANUAL-TESTING.md`.

In particular, changes to `SwitcherService`, `SwitcherOverlay`,
`AccessibilityAppSource`, controller focus, D-pad/helper code, key-event handling,
display targeting, the manifest/accessibility config, or the Android/Kotlin/Gradle
toolchain require a physical AYN Thor pass. Scale device testing to the highest tier of
any touched file. Documentation and test-only changes do not need a device pass.

Before device work, re-check the connected device with `adb devices`; do not assume a
stable serial or display ID. Avoid unnecessary reinstalls during a game because service
restart recreates the virtual pad and the game may need to be paused/resumed.

## Release safety

A release requires both an increased `versionCode` and a semantic `versionName`. The tag
must be exactly `v<versionName>` (for example `v1.3.0`). The release workflow enforces
this and `UpdateChecker` compares the release tag with installed `versionName`.

Never commit a keystore, certificate, password, token, or signing environment value.
Do not print secret values while diagnosing release builds. Local unsigned releases are
expected; CI signs from environment-backed GitHub secrets. Do not tag, publish, alter
repository visibility, or change secrets unless explicitly requested.

Before an explicitly requested release: run the full gate, ensure CI is green, complete
the S4 device procedure, verify the tag/version pair, and verify the published APK's
certificate and in-place upgrade behavior as documented.

## Definition of done

For a code/configuration change:

1. The requested behavior is implemented with existing invariants preserved.
2. Focused tests cover the change where practical.
3. Existing goldens and baselines were not weakened to mask a regression.
4. `./tools/verify.sh` passes.
5. The applicable manual-test tier is reported and, when device access is available,
   completed.
6. The final handoff names changed files, verification performed, and any unrun physical
   device checks or residual risk.
