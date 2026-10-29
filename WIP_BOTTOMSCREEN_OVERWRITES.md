# Bottom-screen foreground priority — reverted attempt

2026-10-29: The owner reported this implementation as a completely buggy mess.
**It was fully reverted. This is an investigation note, not a working solution.**

## Goal and confirmed cause

Single-tap appdrawer and double-tap bottom Recents should appear above persistent
launcher/OEM panels. Everything must ship in our APK using existing PService root
access, without separate installations or permanent device modifications.

The inspected Android 13 Thor placed AYN's assistant at TYPE_BOOT_PROGRESS (2021),
base layer 341000, above our TYPE_ACCESSIBILITY_OVERLAY (2032), layer 311000.
Normal launcher Activities were below both; bringToFront cannot cross these types.

## Files and attempted changes

Production paths are relative to app/src/main/java/dev/steinhorst/backbuttonhero/.

| File | Changes and purpose |
|---|---|
| ForegroundWindows.kt (new) | Launch an APK-owned app_process window worker through PService. Communicate over a random, UID-authenticated local socket using RECENTS, DRAWER, RESET and PING. Wait for acknowledgement before showing UI; cancel stale callbacks and reconnect after transport failure. |
| ForegroundWindowRoot.kt (new) | Reflectively register DisplayAreaOrganizer feature 4; raise bottom WindowedMagnification:0:31 to Int.MAX_VALUE above AYN. For the drawer, register feature 2 and lower bottom Leaf:3:12 to -1 so ordinary app overlays sit below tasks. Reapply on 300 ms heartbeats. Unregister/release on dismissal, launcher departure, keyguard lock, disconnect or two seconds without heartbeat. Binder death also restores surfaces. |
| ForegroundWindowPolicy.kt (new) | Gate to the inspected area names and non-default display IDs; root path supports API 33 only and refuses occupied organizers. ForegroundWindowLease allows two seconds for launcher arrival, then ends when its package leaves the foreground. |
| SwitcherService.kt | Start/stop the worker with the service; wrap bottom goHome() and showSwitcher(); reset on dismissal/failure. Add switcherOpening, switcherRequest and cancelPendingSwitcher() to cancel delayed opening callbacks. |
| app/src/test/java/dev/steinhorst/backbuttonhero/ForegroundWindowPolicyTest.kt (new) | Four tests for area/display guards and launcher lease arrival, departure and timeout. |
| docs/HELPER.md; docs/MANUAL-TESTING.md | Document the mechanism and C0a–C0d panel, dismissal, focus, service/crash and lock checks. These additions were reverted too. |

PService.kt, PadHelper.kt and padhelper.c were not modified.

## What a retry must address

The full automated gate passed (180 tests), and device dumps showed layer changes
and cleanup after dismissal, app launch, service shutdown and heartbeat expiry.
**This did not establish correct real-world UX/input behavior.** The owner rejected
the result; specific symptoms were not recorded and must be investigated first.

Risk areas: raising an entire display area instead of our individual window;
organizers registering across displays despite bottom-only layer writes; internal
API/surface-name dependencies; asynchronous gesture timing; detecting the drawer by
launcher package rather than actual drawer visibility. These are investigation
points, not confirmed diagnoses. Other launchers and complete physical controller,
dismissal and lock flows were not owner-validated.

Preserve the earlier Close-all lock override, empty-state/theme/strings, previews,
existing tests and Recents documentation. FEEDBACK.md and the separately appearing
RecentsMemoryDeviceTest.kt were unrelated and must remain untouched.
