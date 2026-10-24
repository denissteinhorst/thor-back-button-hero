# Manual testing procedures

The automated gate cannot see most of what this app does. `SwitcherOverlay` has **0 %**
line coverage and `SwitcherService` **5.8 %**, because an accessibility connection, a
second display and real hardware key events are not reachable from Robolectric, and no
emulator image exists for a dual-screen device.

So for part of this codebase a green gate proves very little, and a human has to look.
This document decides *when* that is necessary and *exactly what* to do — so the decision
is not re-argued per change, and so a pass is reproducible rather than a vague
"seemed fine".

Run the applicable procedure **before pushing**, not after. The `pre-push` hook enforces
the automated gate; the manual tier is a judgement the hook cannot make for you.

## Step 1 — rate the change

Find the highest tier any touched file falls into. Mixed changes take the highest.

| Tier | Triggered by | Procedure | Time |
|---|---|---|---|
| **S0** | `docs/**`, `README.md`, `CLAUDE.md`, `.github/**`, `.githooks/**`, test sources, goldens, `lint-baseline.xml` | None | — |
| **S1** | `Prefs.kt`, `SettingsUiKit.kt`, `SettingsActivity.kt`, `UpdateChecker.kt`, `res/values*/strings.xml`, `colors.xml`, version bumps | [A — Settings smoke](#procedure-a--settings-smoke) | ~3 min |
| **S2** | `BackGestureMachine.kt`, `DoubleTapDetector.kt`, `CardTouch.kt`, `ShoulderButton.kt` | A + [B — Gesture pass](#procedure-b--gesture-pass) | ~12 min |
| **S3** | `SwitcherService.kt`, `SwitcherOverlay.kt`, `AccessibilityAppSource.kt`, `RunningApp.kt`, `AndroidManifest.xml`, `accessibility_config.xml`, or any Kotlin/AGP/Gradle version change | A + B + [C — Overlay and shoulder pass](#procedure-c--overlay-and-shoulder-pass) | ~30 min |
| **S4** | Publishing a release | S3 plus [D — Release acceptance](#procedure-d--release-acceptance) | ~40 min |

Two notes on the boundaries. A **version bump alone is S1** even though it edits
`build.gradle.kts`, because the only behavioral change is a string in the settings footer.
A **toolchain bump is S3** even though no app source changes, because a new Kotlin or AGP
major version recompiles everything and can alter behavior the tests do not reach — that
is precisely why those updates are never bundled with a release.

If a change is behavior-neutral by intent — a refactor or an extraction — add the
[trace comparison](#method-gesture-trace-comparison) rather than relying on your
impression of "still works".

## Step 0 — device preparation

Every procedure assumes this starting state.

```sh
adb devices                       # confirm exactly one device; the id changes
./gradlew installDebug            # or install the signed APK for procedure D
```

Then on the device:

1. Open the app once. On a fresh install Android asks for the notification permission —
   grant it.
2. If the accessibility service is off, the settings screen shows a warning row. Enable
   **Back Button Hero** in Android's accessibility settings. Android does not let the app
   enable its own service, so this is always manual.
3. Return to the app and confirm the status line reads **on**.
4. Open two or three ordinary apps (a browser and one more), so the switcher has real
   cards with thumbnails. **An empty switcher invalidates most of procedure C.**

Record what you had open — the issue template asks reporters for the same thing, for the
same reason: behavior depends on which app sits on which screen.

## Procedure A — Settings smoke

Confirms the app starts, renders, and reads its own state correctly.

| # | Step | Expected |
|---|---|---|
| A1 | Open the app | Settings screen renders fully, no blank areas, no crash |
| A2 | Read the status line | **on**, and the warning row is absent |
| A3 | Read the version footer | Matches the `versionName` you built |
| A4 | Exercise the row you changed | Behaves as intended; the value persists |
| A5 | Close the app fully, reopen | Your change is still in effect |
| A6 | Switch the system theme light↔dark, reopen | All text legible, nothing clipped |

If the change touched `UpdateChecker`, add: tap **Check for new version** and confirm one
of the three outcomes appears as a dialog. Before any release exists, or right after a
reboot with no network yet, the expected result is the failure dialog — the check is a
single attempt per app open, with no retry.

## Procedure B — Gesture pass

Covers every Back-press decision. Timing matters: the decisions depend on it, so hold
durations are part of the test.

| # | Step | Expected |
|---|---|---|
| B1 | Single tap, with *Single tap = Normal Back* | Behaves exactly like system Back |
| B2 | Single tap, with *Single tap = Open bottom screen launcher* | Bottom screen's app drawer opens |
| B3 | Double tap | Switcher overlay opens on the **bottom** screen |
| B4 | Double tap while the overlay is open | Overlay closes |
| B5 | Hold (default ~1000 ms) | Top and bottom screen apps swap places |
| B6 | Tap, then hold again within the tap-hold window | Top screen shows system **Recents**, and does *not* swap |
| B7 | Disable double tap, then single tap | Fires immediately, with no waiting delay |
| B8 | Disable hold, then hold Back | Nothing happens on hold; Back is processed normally on release |

Restore any settings you changed afterwards (or use **Reset settings**).

B6 is the one most likely to regress and the easiest to misread. If it swaps instead of
showing Recents, that is the `pressFollowsTap` latching behavior breaking — see
[`ARCHITECTURE.md`](ARCHITECTURE.md).

## Procedure C — Overlay and shoulder pass

The part with no automated coverage at all. Needs the two or three apps from step 0.

### Switcher overlay

| # | Step | Expected |
|---|---|---|
| C1 | Open the overlay (double tap) | Cards show correct icon, label and thumbnail for each open app |
| C2 | Tap a card | That app opens, overlay closes |
| C3 | Swipe a card upward | Card closes with its animation, the app is terminated |
| C4 | Scroll the row horizontally | Scrolls; **no** card closes |
| C5 | "Close all" with the both-screens toggle **off** | Only bottom-screen apps close |
| C6 | "Close all" with the toggle **on** | Apps on both screens close |
| C7 | Open the overlay with no apps open | "No recent apps" text appears instead of cards |
| C8 | Have another app cover the bottom screen, then open a card | The covered-screen hint toast appears |

C3 and C4 are the two halves of the same arbitration: if a horizontal scroll ever closes a
card, or a swipe up only scrolls, `CardTouch` is the suspect.

### Shoulder buttons

Each is pressed **while holding Back**.

| # | Step | Expected |
|---|---|---|
| C9 | L1 at its default (navigation flick left) | Visible flick to the previous app |
| C10 | R1 at its default (navigation flick right) | Visible flick to the next app |
| C11 | One button set to *Launch app*, target **top** screen | The chosen app opens on the top screen |
| C12 | Same, target **bottom** screen | It opens on the bottom screen |
| C13 | One button set to *Home* | Launcher on the configured screen |
| C14 | One button set to *Recents* | System Recents opens |
| C15 | One button set to *Take screenshot* | A screenshot is taken |
| C16 | Three different system panels (e.g. Bluetooth, Display, Battery Saver) | Each panel opens; a missing OEM panel falls back instead of failing |
| C17 | An **unassigned** button during a game | The game receives the input normally — nothing is swallowed |

C17 protects the promise that the app stays out of the way. It is easy to break by
consuming a key event unconditionally, and no test can catch it.

### Service health and localisation

| # | Step | Expected |
|---|---|---|
| C18 | Turn the accessibility service off in Android settings | Warning row appears, status switches to **off** |
| C19 | Tap "Repair service" → "Open accessibility settings" | Lands on the correct system screen |
| C20 | Turn it back on | Status returns to **on**, gestures work again |
| C21 | "Reset settings", confirm | Every timing, switch and shoulder assignment back to default; confirmation toast appears |
| C22 | Switch the system language to German, reopen the app | All sections, dialogs and toasts in German; no English remnants, no clipped labels |

## Procedure D — Release acceptance

Run **after** the release workflow has published, on the **signed release artifact** —
not on a debug build. The debug build differs in signing and bundles LeakCanary, which
adds a second "Leaks" launcher icon.

```sh
gh release download v<version> -D /tmp
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  /opt/homebrew/share/android-commandlinetools/build-tools/34.0.0/apksigner \
  verify --print-certs /tmp/thor-back-button-hero-v<version>.apk
```

| # | Step | Expected |
|---|---|---|
| D1 | Compare the certificate SHA-256 against previous releases | Identical. A different digest breaks updates for every existing install |
| D2 | Install the signed APK **over** the previous version | Succeeds **without** asking to uninstall first |
| D3 | `adb shell dumpsys package dev.steinhorst.backbuttonhero \| grep -E "versionName\|firstInstallTime\|lastUpdateTime"` | New `versionName`; `firstInstallTime` **unchanged**, `lastUpdateTime` new — this is the proof it was a true in-place update |
| D4 | Check the service and your settings | Service still enabled, settings preserved |
| D5 | Count launcher icons | Exactly **one** — no "Leaks" icon in a release build |
| D6 | Run procedures A, B and C on this build | All pass |
| D7 | Tap "Check for new version" | Reports up to date |

Verifying the artifact rather than the workflow status matters: a release can be reported
green while publishing the wrong thing. D1 and D3 are the two checks that cannot be
inferred from CI output.

## Method: gesture trace comparison

For changes that are meant to be behavior-neutral — refactors, extractions, toolchain
bumps — an impression is not evidence. Compare the actual decisions before and after.

1. Set `DEBUG_GESTURES = true` in `SwitcherService.kt`, build and install.
2. Record a trace while performing a fixed sequence (single tap, double tap,
   tap-then-hold twice, hold):
   ```sh
   adb logcat -c
   # perform the sequence on the device
   adb logcat -d | grep -oE 'BackButtonHero: .*' > /tmp/trace-before.txt
   ```
3. Apply the change, rebuild, install, repeat with the **same** sequence into
   `trace-after.txt`.
4. Diff them. Apart from timestamps the decisions must be identical:
   ```sh
   diff /tmp/trace-before.txt /tmp/trace-after.txt
   ```
5. **Set `DEBUG_GESTURES` back to `false` before committing.** Left on, it logs on every
   Back press in production. Confirm with `git diff` that the file is byte-identical to
   the committed state.

A trace that differs in anything but timing means the change was not behavior-neutral,
regardless of what the suite says.

## If something fails

Stop and treat it as a finding, not as noise:

1. Do **not** push.
2. Note which numbered step failed, and the full device state — which app was on which
   screen, what was open, which settings deviated from default. The same information the
   [issue template](../.github/ISSUE_TEMPLATE/bug_report.yml) asks reporters for.
3. If a golden test also went red, the golden is the better evidence — read that diff
   first.
4. Revert the change (`git restore`, or `git revert` if already committed) rather than
   patching forward from an unknown state.

A failure here is the system working. These procedures exist because this is the only
place such a failure can be caught at all.
