# Architecture

For the product behavior and its protected invariants, see
[`hardening/00-OVERVIEW.md`](hardening/00-OVERVIEW.md). This document describes how the
implementation is divided and how those boundaries are tested.

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
and SystemClock-driven key timing cannot otherwise be exercised deterministically on the JVM;
the verbatim extraction and its constraints are documented in
[`hardening/16-back-gesture-machine-seam.md`](hardening/16-back-gesture-machine-seam.md).

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
manifest, resource, and framework-integration failures that a JVM model cannot reproduce. A
manual device checklist and a golden gesture trace cover the remaining accessibility-service,
multi-display, overlay, and physical-key behavior. The deliberate automation boundaries and
their substitute checks are maintained in
[`hardening/TEST-GAPS.md`](hardening/TEST-GAPS.md).

`./tools/verify.sh` is the shared local, hook, and CI gate. It runs lint, JVM tests, the JaCoCo
per-class ratchets, and both debug and release builds; the device smoke and manual checklist
remain explicit release activities.
