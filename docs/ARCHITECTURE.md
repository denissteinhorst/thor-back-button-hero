# Architecture

This document describes how the implementation is divided and how those boundaries are
tested. For the development workflow, the gate command, expected test results, and the
toolchain gotchas, see [`../CLAUDE.md`](../CLAUDE.md).

## Components

`SettingsActivity` is the launcher UI. It reads and writes all user configuration through
`Prefs`, reports accessibility-service health, and invokes `UpdateChecker` for automatic and
manual GitHub release checks. `Prefs` owns the SharedPreferences schema, defaults, and
one-time migrations; both the activity and the service use the same wrapper.

`SwitcherService` is the runtime coordinator. As an `AccessibilityService`, it receives
hardware key events and performs Android side effects such as global actions, display-aware
activity launches, screenshots, and screen swaps. It delegates Back-key timing decisions to
`BackGestureMachine`, maintains the recent-app model through `AccessibilityAppSource`, and
creates `SwitcherOverlay` to render and operate the bottom-screen app switcher.

`BackGestureMachine` contains the gesture state and timing decisions but none of the Android
side effects. Clock, scheduling, and completed actions enter through small interfaces, while
`SwitcherService` supplies their Android implementations. The seam exists because Handler-
and SystemClock-driven key timing cannot otherwise be exercised deterministically on the
JVM. It was extracted verbatim: the decision logic, including the `pressFollowsTap`
latching quirk, moved across unchanged, so the machine reproduces the original behavior
rather than a cleaned-up version of it.

`AccessibilityAppSource` derives a per-display recent-app history from accessibility windows
and supplies labels, icons, and captured thumbnails. `SwitcherOverlay` consumes that model and
builds its accessibility-overlay UI using framework Views; the extracted `CardTouch` class
owns the independently testable tap-versus-swipe calculation. `UpdateChecker` remains separate
from both UI paths: it talks to the GitHub Releases API and exposes results to
`SettingsActivity`, while its JSON parser is isolated as a JVM-testable seam.

## Test pyramid

The broad base is the Robolectric/JUnit suite: it covers preferences and migrations, resource
integrity, UI helpers, release parsing, `CardTouch`, accessibility-window bookkeeping, and all
branches of `BackGestureMachine`. Deterministic View-tree snapshots protect the settings page,
row states, dialogs, and accessibility inventory without depending on pixel rendering.

One Espresso instrumented smoke test runs `SettingsActivity` on a real Android device to catch
manifest, resource, and framework-integration failures that a JVM model cannot reproduce.

The automation boundary is deliberate and worth stating plainly: `SwitcherOverlay` has no
automated coverage at all and `SwitcherService` has almost none, because accessibility-service
windows, multi-display targeting, and physical key events are not reachable from Robolectric
and there is no emulator image for a dual-screen device. Manual testing on the AYN Thor is
the only net under those paths — see [`../CLAUDE.md`](../CLAUDE.md) for which changes warrant
a device pass.

`./tools/verify.sh` is the shared local, hook, and CI gate. It runs lint, JVM tests, the JaCoCo
per-class ratchets, and both debug and release builds; the device smoke and manual checklist
remain explicit release activities.
