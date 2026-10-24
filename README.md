# Back Button Hero

A tiny Android accessibility service that gives the **Back button** on
dual-screen handhelds (built for the [AYN Thor](https://www.ayntec.com/products/ayn-thor)) a lot
more jobs: app switching, screen swapping, task switching, and up to four
fully customizable shoulder-button shortcuts — on top of what Back already
does.

No root or companion app required.

## What the Back button does

![Single tap, double tap and hold shown on a simplified diagram of the device](docs/gestures.gif)

| Gesture | Action |
|---|---|
| **Single tap** | Normal Back, or opens the bottom screen's launcher — your choice |
| **Double tap** | Opens an app-switcher overlay on the bottom screen — something the Thor doesn't have on its own — with cards for every app that's been opened there |
| **Hold** | Swaps the apps on the top and bottom screens |
| **Tap, then hold** | Opens the system's Recents (task switcher) on the top screen instead of swapping |
| **Hold + L1 / L2 / R1 / R2** | Runs whatever action is assigned to that shoulder button — see below |

In the switcher overlay: tap a card to open it, swipe a card up to close it,
or use "Close all" (optionally across both screens at once).

All timings (double-tap window, hold duration), the single-tap behavior, and
whether double-tap/hold/tap-hold are enabled at all are configurable from
the app's settings screen.

## Shoulder-button shortcuts

Hold Back and press L1, L2, R1, or R2 to run a per-button action, independent
of what Back alone does. L1 and R1 default to a navigation flick (quick
previous/next-app swipe), but every button — including L1/R1 — can be
freely reassigned to whatever action you want from the list below:

- Launch a chosen app, on the top or bottom screen
- A navigation flick (quick previous/next-app swipe)
- Home, Recents, or a screenshot
- Open Quick Settings or the notification shade
- Jump straight to a system settings panel: Wi-Fi & internet, Bluetooth,
  Night Light, Extra Dim, Display, Sound, Location, Cast, Do Not Disturb, or
  Battery Saver

An unassigned button is left alone, so games and apps still see its normal
input.

## Settings & service health

The settings screen shows whether the accessibility service is on, and has
a "Repair service" shortcut for the rare case where gestures stop
responding — Android won't let an app re-enable its own accessibility
service, so this walks you straight to the right toggle instead.

- **Reset settings** restores every gesture timing, switch, and
  shoulder-button assignment to its default. It doesn't touch Android
  permissions or the accessibility-service state.
- **Check for new version** manually asks GitHub Releases for a newer
  build, on top of the automatic background check — useful right after
  install, when there's nothing to compare against yet.

## Installing

This isn't on the Play Store. Grab the latest APK from
[Releases](../../releases), install it, then:

1. Open the app once and grant it the Accessibility permission it asks for.

The app checks GitHub Releases for updates and notifies you when a newer one
is available — no auto-install, it just links to the release page.

## Building from source

```sh
./gradlew assembleDebug
```

Requires JDK 17. `minSdk` is 30 (Android 11).

## About this project

This app was built with AI assistance: I directed, reviewed, and tested
every part of it myself, using OpenAI's Codex (GPT-5.6-sol) and Anthropic's
Claude Code (Sonnet 5.5) as the coding tools. As an application developer
with several years of professional experience, I built it to the best of
my knowledge and have used it extensively on my own AYN Thor. Still, it's a
hobby project and may contain bugs. I'm happy to look at issues and fix
things when I can, but — see the license below — I'm not taking on any
obligation to do so.

## License

0BSD — see [LICENSE](LICENSE).
