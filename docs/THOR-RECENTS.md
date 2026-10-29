# Thor system Recents reference

Inspected on the USB Thor on 2026-10-29, without screenshots. Package
`com.android.launcher3`, versionName `13`, versionCode `33`, installed APK
`/system_ext/priv-app/Launcher3QuickStepEX6/Launcher3QuickStepEX6.apk`.
Evidence: live `dumpsys activity com.android.launcher3`, installed APK resource/XML
inspection with `aapt2`, and DEX disassembly with `apkanalyzer`.

## Implementation and display indicator

This is Launcher3/Quickstep with OEM extensions, rather than a separate display-indicator
overlay. `com.android.quickstep.views.TaskView` extends
`com.sprd.ext.lockicon.FrameLayoutWithLockIcon`. The `com.sprd` namespace establishes OEM
integration; it does not establish who originally authored each modification.

`TaskView.updateIvDisplayIcon()` reads `Task.key.displayId` and selects indicator level
1 for display 0, otherwise level 2. `Task.TaskKey(TaskInfo)` obtains the value from
`android.app.TaskInfo.displayId`. The base class creates `mIvDisplayIcon` and sets its
image level through `setTaskDisplayWhichScreen(int)`.

Resources in the installed Launcher APK:

- `drawable/task_ext_display_state`: level-list selecting `task_ext_display1` and
  `task_ext_display2` (vector drawables showing two displays and a numbered active display).
- `drawable/lock_icon_ext` and `unlock_icon_ext`: separate OEM task-lock affordances.
- The lock icon uses 28 dp size, 3 dp internal padding, 30 dp top margin, 10 dp right margin,
  and TOP|END gravity. The display icon copies those layout parameters with an additional
  80 **pixel** right margin, but does **not** copy the internal padding. These details are
  preserved in `RecentsBadges`; the 80-pixel offset is an OEM detail, not a dp specification.
- `show_windows` / `ic_select_windows` is a different affordance, not the display indicator.

The bottom switcher represents packages observed through accessibility, not
system tasks. `RecentsStateReader` now supplements those entries with the system's recent tasks
and the launcher's existing lock store, through a read-only root entry point. Indicators use the
most recent native task of each package. The overlay refreshes these values while open; its
bulk-close path obtains a fresh snapshot and protects the whole package if any of its tasks is
locked, because our existing force-stop operation acts on packages. A package can have multiple tasks/displays, and a historical observation
is not authoritative current placement. `RecentsBadges` reads the original drawables and dimensions from the installed launcher, including
the exact 80-pixel offset; it does not redraw or copy the assets into this repository. Do not label
historical package entries as current system tasks. The same task identity now also drives system snapshot loading. Task launch/move or lock controls
would be separate behavior/architecture work.

The installed `SecondaryDisplayLauncher.getOverviewPanel()` returns null. Its existence and the
multi-display badges therefore do not by themselves establish a reusable native Overview on the
bottom screen. Moving or embedding system Recents would require a separate compatibility study.

A useful related investigation for the task-preserving swap feedback is Quickstep's
`ActivityManagerWrapper.startActivityFromRecents(TaskKey, ActivityOptions)`, which launches
an existing task rather than a package's launcher intent. Its privileges and display
behavior must be checked before adopting it.

## Lock semantics

`FrameLayoutWithLockIcon.changeLockState()` toggles `TaskLockStatus.setLockState()`.
The state lives in the launcher's private SharedPreferences file `recents_lockedtasks`,
keyed by user ID plus task affinity (or the base activity component as a fallback).
It is therefore not simply a per-task-ID or per-package boolean.

`RecentsView.removeUnlockTaskViews()` skips locked TaskViews while
removing the others. This is protection against bulk removal from Recents, not Android
screen pinning and not a guarantee that Android will keep the process in RAM. The bottom overlay displays and honors this same saved status. It does not write the launcher's
preference file: that risks stale in-process caches and is not a supported synchronization API.
In this installed build, `onFinishInflate()` does not attach the existing lock-click lambda or
initialize the lock touch delegate; the original lock images themselves are status indicators.
No independent bottom-only lock or misleading local toggle is introduced.

If task/lock metadata is unavailable or malformed, indicators remain unknown and bulk-close is
refused with the existing unavailable message. It must not silently force-stop a protected package.

## Background and system theme

`OverviewState.getWorkspaceScrimColor()` resolves the launcher's `overviewScrimColor` attribute,
not a translucent black overlay. Light mode uses `color/overview_scrim`; dark mode uses
`color/overview_scrim_dark`. These are dynamic Android palette resources (the latter also applies
an L* value). On the inspected device the resolved values were `#ffc6c8b9` and `#ff52534d`.
A device-side comparison of `RecentsTheme.load()` with the original Launcher themes matched
both values exactly, without changing the device's global theme for that probe.

`RecentsTheme` selects the same AppTheme light/dark and wallpaper-hint variants and resolves
background, primary text, memory label and control highlight colors from that context. The service
refreshes the open overlay on configuration changes without rebuilding the carousel or changing
its selection. Screenshot/clear-all icons follow the primary text color. The OEM lock and monitor
vectors have fixed colors and no night variants in this APK; they are loaded unchanged from the
same themed package context.

## Cards and motion

Live landscape top display reference: 1920 × 1080 pixels at 369 dpi. Each task's snapshot
is 1116 × 628 pixels (about 58.125% of the display in both dimensions). Above it is a
centered 48 dp icon view, with a 44 dp drawable and 16 dp gap to the snapshot. Horizontal
page spacing is 16 dp. There is no app-name header. The row starts at 24 dp from the top.
The actions follow the snapshots, with a 12 dp gap before the 24 dp memory row.

`RecentsCardStyle` reads the installed launcher's dimensions and theme corner radius,
with local defaults when that package/resources are unavailable. The lower display's
aspect ratio is retained; top/bottom screenshots are not interchangeable.

Quickstep `PagedView` uses `Interpolators.SCROLL`: `1 + (t - 1)^5`. The installed
`config_pageSnapAnimationDuration` is 750 ms (550 ms at sw600dp). Fling timing uses a
sine-adjusted distance and a minimum snap velocity; resources are
`min_fling_velocity` (250 dp/s) and `min_page_snap_velocity` (1500 dp/s for this device).
`RecentsCarousel` uses those resources and timing for finger settling and D-pad paging.
There is no wrap from the last card back to the first.

This reproduces the card presentation and paging within our accessibility overlay; it
does not embed Quickstep's privileged task views or its system transition animations.

## RAM indicator

OEM `MeminfoHelper.getAvailMemString()` reads `/proc/meminfo`'s `MemAvailable` rather than
`ActivityManager.MemoryInfo.availMem`. The advertised total is the kernel total rounded in
SI units; available memory is multiplied by advertised total / kernel total using Float arithmetic.
`Formatter.formatShortFileSize()` formats both values. `RecentsMemory` follows that calculation,
with the same ActivityManager fallback when proc data is unavailable. This fixes the substantial
8.5-vs-11 GB discrepancy observed in the first screenshot comparison.

The visual comparison also found an actual layout shadowing error: unqualified `top` and `right`
inside ImageView construction resolved to the View's coordinates rather than the OEM margins.
Explicitly named pixel margins fix it. Adaptive app icons use Quickstep's mask-area normalization;
status drawables, including the display icon's lack of internal padding, retain their original geometry.

## Final device comparison

Both switchers were captured on the physical Thor in light and dark mode after settling.
The screenshot background RGB values matched exactly in each mode (`#52534e` dark and
`#c6c8ba` light after screenshot color-space conversion). For the same monitor/lock state,
the 65×65 pixel lock and display patches matched with zero differing pixels after alignment
at their respective card edges. RAM labels both showed `11 GB available | 16 GB`.
The owner's original night-mode setting was restored after verification.

An existing OEM discrepancy was observed during that test: after changing system night mode,
Quickstep's monitor indicator changed to 1 for Chrome and remained there after reopening,
although ActivityTaskManager and the activity display dump still placed its sole task on display 4.
The bottom indicator correctly retained monitor 2 from authoritative TaskInfo. No OEM state was
modified to conceal this discrepancy. The initial comparison still used our screenshot cache for previews; the subsequent missing-preview
fix now loads the same system snapshot source as Overview, retaining the lower display's aspect ratio.

## Missing-preview fix

The accessibility screenshot cache only contained apps captured in the foreground since service
startup. Background/recent apps could therefore have an empty card even though system Recents
had a snapshot. `RecentsStateReader snapshot` now obtains `IActivityTaskManager.getTaskSnapshot`
for the task identified by the metadata query. It restores the OS-owned snapshot from disk when
needed, rather than capturing the visible overlay or launching background apps.

Each image is transferred through a caller-created private cache file, scaled to the card width,
decoded off the UI thread, then the transfer file is deleted. The root entry point validates the
destination against the app's cache directory and preserves the existing file owner. It never
writes launcher state. The app retains a 12 MiB LRU keyed by task ID, activity time, display and
output width; eviction does not recycle images still held by a card. Session/task checks discard
late callbacks after close, reopen or task changes. Failed requests have bounded retries per open.
Accessibility screenshots remain a fallback where system snapshots cannot be obtained.

The former active-card foreground stroke has been removed completely. Controller selection and
paging remain logical state; selecting a preview no longer draws a white or black border.

Device verification used fast switches among Settings, Files and Chrome after reinstalling the
service so the screenshot cache was empty for the short-lived foreground apps. Their native
snapshots loaded while paging through the bottom cards, and screenshots confirmed no preview
border. Snapshot transfer files were absent after loading completed.
