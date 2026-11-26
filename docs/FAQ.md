# FAQ

Questions that come up more than once. For the technical side, see
[`ARCHITECTURE.md`](ARCHITECTURE.md); for reporting a problem, the
[bug report form](../.github/ISSUE_TEMPLATE/bug_report.yml).

## Compatibility

### Which device was it actually tested on?

An **AYN Thor Max 16GB/1TB (Rainbow), 1st Batch**, running Android 13, firmware
`Thor_V1.0.0.377_20260206_165408_user`. That is the only configuration the app has been
used on day to day.

### Will it work on my Thor — Base, Pro, or Max512?

It should. Nothing in the app depends on the Max variant: all Thor models share the same
dual-screen layout and the same Back and shoulder-button hardware, which is everything
the app relies on. The variants differ in RAM and storage, and the app asks very little
of either — measured at **41.9 MiB PSS** and about 0 % CPU while idle in the background, so
the lower-specced models are not a concern. See
[Small footprint](../README.md#small-footprint) for how that was measured.

That said, "should" is honest rather than evasive: only the Max has actually been tested.
If you run it on another Thor, a [short report](../.github/ISSUE_TEMPLATE/bug_report.yml)
either way is genuinely useful.

### Will it work on a different device entirely?

Partly, at best. It is a regular Android app with `minSdk` 30, so it installs and launches
on Android 11 or later. But the features split into two groups:

- **Needs a compatible second display:** screen swapping and the bottom-screen app
  switcher. Without one, these have nothing to act on.
- **Needs the expected inputs:** every gesture starts from a hardware Back button, and
  the shortcuts additionally need L1/L2/R1/R2 to report the standard Android keycodes.

Other devices have not been tested, so full feature compatibility is not claimed. A
phone will most likely install it and do very little with it.

### Which Android versions are supported?

Android 11 (API 30) and later. The Thor ships with Android 13, which is what the app is
used on.

## Installing and updating

### Why is it not on the Play Store?

It is a hobby project for a niche device, and Play Store distribution would add an
account, a review process, and policy obligations around an accessibility service
without making the app better for the handful of people who want it. Side-loading from
[Releases](https://github.com/denissteinhorst/thor-back-button-hero/releases) is the
distribution model.

### Why do I have to enable the accessibility service myself?

Android requires you to enable the accessibility service yourself. If it is inactive,
Back Button Hero shows a prompt that opens the right Android setting. Once the service
is active, return to the app to set up your bindings.

### How do I update Back Button Hero?

The app checks GitHub when you open it. Starting with version 1.5.0, tap
**Check for new version** to download an available release inside the app. On your first
in-app update, allow Android to install apps from Back Button Hero, then return and start
the update again. Android asks you to confirm the installation each time. Developer builds
link to the GitHub release page because they use a different signing key.

### Why did the update check fail?

The app tells you when it could not connect, when GitHub has temporarily limited update
checks, or when GitHub sent a response the app could not use. If GitHub gives a wait time,
try again after that time.

### Can I install an update over an existing version?

Yes — all releases are signed with the same key, so Android treats a newer APK as an
in-place update. Your settings and the enabled accessibility service are preserved; you
do not need to uninstall first.

## Safety and privacy

### An accessibility service can see everything. Why should I trust this one?

A fair question, and the honest answer is that you should verify rather than trust:

- The source is here in full, and the release APK is built and signed by the
  [CI workflow](../.github/workflows/release.yml) from a tagged commit — not uploaded by
  hand from someone's machine.
- The release APK contains **no third-party runtime dependencies** at all. Its classpath
  resolves to the Kotlin standard library and nothing else, which you can check yourself
  with `./gradlew :app:dependencies --configuration releaseRuntimeClasspath`.
- There is no analytics or telemetry. The app contacts GitHub to check for releases
  and to download the APK when you choose to install an update.

The README's [permissions section](../README.md#accessibility-permissions--privacy)
explains what each capability is used for.

### Does it need root?

No, the device is not rooted and the app is not installed with root rights. Some features
use the root service that the Thor's firmware ships (`pservice`, the backend of Settings →
Thor → "Run Script as Root"): Back + D-pad and stick shortcuts without pausing the game, brightness and bottom-screen
volume, controller focus, AYN hardware controls, moving apps between screens, force-closing
apps and frontend compatibility. It works while the Thor's SELinux is permissive, which
is AYN's factory default. Without it the app falls back to a simpler method where one exists
and otherwise reports the action as unavailable; the settings screen warns when a D-pad
shortcut cannot use it. [`HELPER.md`](HELPER.md) explains the D-pad part in detail.

### What leaves my device?

Update checks and optional APK downloads go to GitHub over HTTPS. GitHub sees the usual
connection metadata, such as your IP address. Your settings, app list and switcher previews
stay on your device. You can also save a settings backup to a location you choose.

### What happens if I turn the accessibility service off?

Every gesture stops; the Back button goes back to being a plain Back button. Nothing is
deleted, and your settings remain. Turning it on again restores everything.

## Usage

### An app is missing from the switcher. Why?

The switcher can only know apps it has seen. Android gives no way to read another app's
task history, so the list is rebuilt from the windows the accessibility service observes
— which means it starts empty every time the service starts, and launcher and system
packages are filtered out deliberately. Open the app on that screen once and it will
appear.

### Does it work with dual-screen frontends like ES-DE, NeoStation, Cocoon or iiSU?

Frontends draw their bottom screen in one of two ways, and Back Button Hero handles both
without a per-app list. Either way the frontend's bottom screen gets its own card in bottom
Recents; selecting it brings that screen back exactly as it was.

- **Overlay window** (ES-DE Companion, NeoStation): the window is hidden while you use
  another app on the bottom screen. The frontend never notices.
- **Normal activity** (Cocoon): covering it would make Android stop it, and Cocoon would
  then switch its top screen to its single-screen layout. Instead, Back Button Hero moves
  that activity onto an invisible screen of its own, where it keeps running. If the frontend
  later starts its bottom screen again by itself (after standby or when you return from a
  game), it is moved away once more.

This needs AYN's built-in root service, which the Thor ships with. Moving a frontend away
keeps it running in the background, which costs a little battery while you use another app.

**iiSU is not supported yet.** It starts a new bottom screen as soon as any other app comes
to the front there, so it immediately covers the app you just opened. Back Button Hero
notices that and leaves iiSU alone rather than fighting it in a loop.

### Can I build it myself instead of trusting a release?

Yes, and the result is functionally identical:

```sh
./gradlew assembleDebug
```

JDK 17 is required. Note that your build is signed with your own debug key, so it cannot
be installed as an update over a release-signed copy — uninstall first, or just keep
using the releases. [`BUILD-AND-RELEASE.md`](BUILD-AND-RELEASE.md) covers the full setup.

### Will my settings survive an update?

Yes. Preference changes that could affect existing installs go through guarded one-time
migrations, which are covered by tests precisely because getting them wrong would silently
alter someone's configuration. A fresh install starts with empty bindings. Updating keeps your existing choices, including
any inputs you deliberately left empty. Use **Settings → Backup** to export your setup to a
JSON file or load it again. The app previews the changes before you apply them.
