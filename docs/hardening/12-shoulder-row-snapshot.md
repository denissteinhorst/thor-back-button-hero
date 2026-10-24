# Schritt 12 — Snapshot: Shoulder-Rows

## 1. Ziel

Jede der vier Shoulder-Button-Rows in jedem relevanten Action-Zustand (unbelegt, App
zugewiesen, `@`-Sentinel-Aktion zugewiesen) einfrieren — inklusive der bedingten
Sichtbarkeit des „Use top screen"-Toggles.

## 2. Voraussetzungen

Schritt 11 ist committet und `./tools/verify.sh` läuft grün.

**Vorher lesen: `docs/hardening/PITFALLS.md`, P2 und P7.**

## 3. Exakte Aufgaben

Datei anlegen `app/src/test/java/dev/steinhorst/backbuttonhero/ShoulderRowSnapshotTest.kt`.
Jeder Test setzt per `Prefs` einen Zustand, bevor die Activity gebaut wird, und snapshotet
dann die gesamte Seite (die Shoulder-Rows sind Teil der Hauptseite — kein isoliertes View):

```kotlin
package dev.steinhorst.backbuttonhero

import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.support.Golden
import dev.steinhorst.backbuttonhero.support.ViewTree
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-xxhdpi")
class ShoulderRowSnapshotTest {

    private fun snapshotWith(name: String, configure: Prefs.() -> Unit) {
        Prefs(ApplicationProvider.getApplicationContext()).apply(configure)
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val root = activity.findViewById<android.view.View>(android.R.id.content)
        Golden.verify(name, ViewTree.describe(root))
    }

    @Test
    fun `all four shoulder buttons unassigned`() = snapshotWith("shoulder_all_unassigned") {
        ShoulderButton.entries.forEach { setShoulderAction(it, "") }
    }

    @Test
    fun `L1 assigned to a settings sentinel shows the top-screen toggle`() =
        snapshotWith("shoulder_l1_sentinel_with_toggle") {
            setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_HOME)
            setShoulderDisplay(ShoulderButton.L1, Prefs.DISPLAY_TOP)
        }

    @Test
    fun `R1 assigned to a non-screen action hides the top-screen toggle`() =
        snapshotWith("shoulder_r1_screenshot_no_toggle") {
            setShoulderAction(ShoulderButton.R1, Prefs.SHOULDER_SCREENSHOT)
        }

    @Test
    fun `L2 assigned to launch an app shows the top-screen toggle`() =
        snapshotWith("shoulder_l2_app_with_toggle") {
            setShoulderAction(ShoulderButton.L2, "com.android.chrome")
        }

    @Test
    fun `defaults - L1 and R1 are the navigation-flick sentinels, no toggle shown`() =
        snapshotWith("shoulder_defaults_flick_no_toggle") {
            // no-op: constructing Prefs already applies the v1 navigation-flick defaults
        }
}
```

Golden-Dateien aufnehmen wie in Schritt 11 beschrieben (`UPDATE_GOLDENS=1`, dann manuell
gegen `00-OVERVIEW.md` → Feature-Inventar → Shoulder-Buttons prüfen, dann ohne das Flag
erneut grün laufen lassen).

**Hinweis zu `com.android.chrome`:** falls das Testgerät/Robolectric-Framework diese App
nicht kennt, zeigt `describeShoulderAction` den Fallback-Zweig (`runCatching { ... }
.getOrDefault(action)`, also den rohen Package-Namen als Text) — das ist korrektes,
bestehendes Verhalten und gehört unverändert in den Snapshot, nicht gefixt.

## 4. Nicht anfassen

- Kein Produktivcode.
- Keine Golden-Datei aus Schritt 11 überschreiben.

## 5. Akzeptanzkriterien

- `./tools/verify.sh` grün.
- 5 neue Golden-Dateien committet, alle 5 Tests grün bei wiederholtem Lauf ohne
  `UPDATE_GOLDENS`.

## 6. Gate

`./tools/verify.sh`. Bei Rot: stoppen, melden, nicht die Golden-Dateien neu aufnehmen, um
grün zu erzwingen.

## 7. Commit-Message-Vorschlag

```
test: snapshot every shoulder-button row state

Covers unassigned, sentinel-with-screen-toggle, sentinel-without-toggle,
and app-assigned states for all four buttons.
```
