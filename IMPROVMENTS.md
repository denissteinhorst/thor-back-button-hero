# Improvements

Audit of memory, performance, code quality, docs and inline comments (2026-10-30, after
commit "Apply per-app exceptions to Back gestures and combos"). Nothing here is
implemented yet. Like `FEEDBACK.md`, this is context, not a queue: each item needs the
project owner's go before it lands, and device-only code needs a device pass.

## Baseline

Measured on the Thor while idle, after a forced GC (`am dumpheap`): about **37 MB PSS**,
23 threads, about 7 MB Java heap. No leak that grows with uptime. Bitmaps are bounded:
the 12 MB `RecentsThumbnailCache` LRU, plus thumbnails that are recycled on replace and
on forget. Sockets and the pad helper are closed on every exit path.

## A. Memory

1. **`SwitcherOverlay.dismiss()` keeps the last view tree alive.** `memoryLabel`,
   `emptyView`, `closeAllButton`, `screenshotButton` and `windowManager` are never
   cleared. Through the parent chain they hold the frame, the cards, the thumbnail
   bitmaps and the window context until the switcher is shown again.
   Confirmed on the device: after a GC, `dumpsys meminfo` reports **13 Views with 0
   ViewRootImpl**, which is exactly the tree of an empty switcher. With many cards
   this is several MB.
   *Fix:* null those fields in `dismiss()`. Risk: very low.
2. **Executors are never shut down.** `ControllerFocus.worker` and `LevelControl.worker`
   each leave a thread behind on every service rebind.
   *Fix:* `shutdown()` from `SwitcherService.stopDpad()`. Risk: low.

## B. Performance (by impact)

3. **The root channel competes with itself.** `PService.exec` serializes every command
   (`execLock`). While the bottom switcher is open:
   - `refreshBottomTaskStates` starts a root `app_process` VM every second.
   - `loadBottomThumbnails` starts **one VM per card**.

   A controller-focus switch or a volume/brightness step queues behind them, which can
   show up as noticeable latency and CPU load.
   *Fix options:* fetch all snapshots in one `RecentsStateReader` call, and/or refresh
   less often. This changes timing. Risk: medium, device-only.
4. **`captureFrontApp` copies a full-size bitmap on the main thread.** The copy is
   about 5 MB, and both the copy and the hardware wrapper are left to the GC. This
   happens after window changes, at most every 1.1 s. `capturePanel` already does the
   same work correctly on a worker thread.
   *Fix:* reuse that path. Risk: low, device-only.
5. **`dlog(...)` builds its strings even with `DEBUG_GESTURES = false`.** There are 22
   calls on the key path (`SwitcherService`, `BackGestureMachine`). The `EXCEPTION`
   log on every Back DOWN also calls `controllerDisplayId()`, a Settings lookup made
   only for the log.
   *Fix:* a lazy/inline `dlog { ... }`. Risk: low.
6. **Every window event and every Back DOWN does more lookups than needed.**
   - `currentException()` and `AccessibilityAppSource.topApp()` resolve `it.root` (a
     binder call) for **all** windows through an eager `mapNotNull`, although only the
     first match is used. `asSequence()` would stop early.
   - `Prefs.appExceptions` is decoded again on every call.
   - `bottomDisplay()` queries `DisplayManager` over IPC every time.

   *Fix:* laziness and a small cache, invalidated by the prefs listener and a
   `DisplayListener`. Risk: low to medium.
7. **`PanelConnection` wakes a thread every 3 s for the service's whole lifetime**, even
   when no panel connection exists. Minor.

## C. Code quality

8. **`SwitcherService` is a god class.** It has 1577 lines and about 40 mutable state
   fields, about 20 of them for top Recents. Extracting a `TopRecentsSession` is the
   biggest lever, but it is a risky refactor of device-only code. Not recommended
   without an explicit decision.
9. **Duplication:**
   - `onDestroy`/`onUnbind` are about 90 % identical.
   - The top-Recents state reset is repeated 13 lines at a time in
     `openNativeTopRecents` and `restoreRecentsFocus`.
   - The launcher3 root lookup appears three times.
   - Hardware-bitmap → scaled thumbnail appears three times: `captureFrontApp`,
     `capturePanel` and `RecentsStateReader.writeSnapshot`.
   - `closeSocket` exists in both `PadHelper` and `PanelConnection`.
   - `Thread { }.apply { isDaemon = true }.start()` appears about 27 times. The thread
     in `saveScreenshot` is the only non-daemon one.
10. **Small items:**
    - `onPadHelperActiveChanged` has a redundant `when` branch: both
      `topRecentsControllerActive` cases yield `TOP`.
    - `val consumed = backGesture.handleBack(event); return consumed`.
    - Two consecutive KDoc blocks on `PadHelper.setPassthrough`; the first one is
      silently dropped.

## D. Docs and comments

11. **`CLAUDE.md` → Architecture** still describes `SwitcherService` as the Back state
    machine (`holdRunnable`, `tapHoldRunnable`, `pressFollowsTap`). That logic lives in
    `BackGestureMachine` now.
12. **`UpdateChecker`:** the comment "The only two lines to touch once the repo is
    created under its final name." is stale. The repository exists.
13. **Needs an owner decision (visible text):** the notification channel name "App
    updates" in `UpdateChecker.notify` is hard-coded English and not localized.

Resolved during the audit: the JVM test count in `AGENTS.md`/`CLAUDE.md` was corrected
to 316 in the exceptions commit.

## Suggested order

1. Low-risk batch: items 1, 2, 4, 5, 6, 7, 10, 11, 12.
2. Then item 3, on its own, with a focused device pass on the bottom switcher.
3. Item 9 only alongside related work; item 8 only on explicit request.
