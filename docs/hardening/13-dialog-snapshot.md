# Schritt 13 — Snapshot: Dialoge

## 1. Ziel

Die 6 `AlertDialog`-Flows der Settings-Seite (Single-Action-Chooser, Shoulder-Action-Chooser,
Shoulder-App-Chooser, Repair-Service, Reset-Settings, Update-Check mit 3 Ergebnisvarianten)
als View-Tree-Snapshot einfrieren.

## 2. Voraussetzungen

Schritt 12 ist committet und `./tools/verify.sh` läuft grün.

## 3. Exakte Aufgaben

Datei anlegen `app/src/test/java/dev/steinhorst/backbuttonhero/SettingsDialogSnapshotTest.kt`.
Dialoge werden über Robolectrics `ShadowAlertDialog`/`ShadowDialog`-Mechanik abgegriffen:
nach dem Klick, der den Dialog öffnet, liefert
`org.robolectric.shadows.ShadowDialog.getLatestDialog()` die Instanz, deren
`window?.decorView` dann snapshotbar ist.

```kotlin
package dev.steinhorst.backbuttonhero

import android.app.Dialog
import dev.steinhorst.backbuttonhero.support.Golden
import dev.steinhorst.backbuttonhero.support.ViewTree
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-xxhdpi")
class SettingsDialogSnapshotTest {

    private fun buildActivity() = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

    private fun latestDialog(): Dialog =
        ShadowDialog.getLatestDialog() ?: error("No dialog was shown")

    private fun snapshotDialog(name: String) {
        val dialog = latestDialog()
        val decorView = dialog.window?.decorView ?: error("Dialog has no window")
        Golden.verify(name, ViewTree.describe(decorView))
    }

    @Test
    fun `single-tap action chooser dialog`() {
        val activity = buildActivity()
        clickRowWithText(activity, activity.getString(R.string.single_title))
        snapshotDialog("dialog_single_action_chooser")
    }

    @Test
    fun `reset settings confirmation dialog`() {
        val activity = buildActivity()
        clickRowWithText(activity, activity.getString(R.string.reset_settings))
        snapshotDialog("dialog_reset_settings_confirm")
    }

    @Test
    fun `repair service explanation dialog`() {
        val activity = buildActivity()
        clickRowWithText(activity, activity.getString(R.string.repair_service))
        snapshotDialog("dialog_repair_service")
    }

    private fun clickRowWithText(activity: SettingsActivity, text: String) {
        val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        val target = findTextView(content, text) ?: error("No row found with text: $text")
        var view: android.view.View = target
        while (!view.isClickable) {
            view = view.parent as? android.view.View ?: error("No clickable ancestor for: $text")
        }
        view.performClick()
    }

    private fun findTextView(root: android.view.View, text: String): android.view.View? {
        if (root is android.widget.TextView && root.text.toString() == text) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findTextView(root.getChildAt(i), text)?.let { return it }
            }
        }
        return null
    }
}
```

**Scope dieses Schritts sind genau diese drei Dialoge.** Die Shoulder-Action-/
Shoulder-App-Chooser und die drei Update-Check-Ergebnisdialoge sind bewusst **nicht**
enthalten:
- Die Shoulder-Rows tragen alle den Titel `L1`/`R1`/`L2`/`R2` und darunter den
  Aktionstext — `clickRowWithText(activity, "L1")` ist eindeutig und funktioniert, falls das
  gewünscht ist; es ist aber kein Pflichtteil dieses Schritts, weil Schritt 12 die
  Shoulder-Rows schon im Hauptseiten-Snapshot abdeckt.
- Die drei Update-Check-Dialoge hängen am `callback` von
  `UpdateChecker.checkInBackground`, der aus einem rohen `Thread` heraus über
  `Handler(Looper.getMainLooper()).post { ... }` zugestellt wird. Unter Robolectric ist
  dieser Thread nicht deterministisch synchronisierbar (siehe `PITFALLS.md` P2).
  **Diese drei Dialoge werden nicht gesnapshotet.** Stattdessen in
  `docs/hardening/TEST-GAPS.md` einen Abschnitt „Update-Check-Dialoge" ergänzen, der das
  begründet. Das ist kein Scheitern dieses Schritts, sondern das dokumentierte Ergebnis.

Golden-Dateien wie gewohnt mit `UPDATE_GOLDENS=1` aufnehmen, dann manuell prüfen, dann grün
ohne das Flag bestätigen.

**Hinweis zum Snapshot-Inhalt:** `dialog.window?.decorView` enthält bei einem `AlertDialog`
mit `setSingleChoiceItems` eine `ListView`, deren Zeilen erst bei einem Layout-Pass
existieren. Enthält die Golden-Datei nur den Dialograhmen ohne Auswahleinträge, ist das
**kein Fehler** — es ist der Zustand, den Robolectric ohne Measure/Layout-Pass liefert, und
als Characterization genau richtig eingefroren. Nicht versuchen, durch manuelle
`measure()`/`layout()`-Aufrufe mehr Inhalt zu erzwingen.

## 4. Nicht anfassen

- Kein Produktivcode.
- `UpdateChecker.kt` wird in diesem Schritt nicht angefasst, auch nicht um es
  testfreundlicher zu machen — das wäre eine Vorwegnahme von Schritt 26.

## 5. Akzeptanzkriterien

- `./tools/verify.sh` grün.
- Mindestens die drei Dialoge aus dem Codeskelett (Single-Action-Chooser, Reset-Confirm,
  Repair-Service) sind gesnapshotet und committet.
- Falls Update-Check-Dialoge ausgelassen wurden: `TEST-GAPS.md` ist entsprechend ergänzt.

## 6. Gate

`./tools/verify.sh`. Bei Rot: stoppen, melden, keine Golden-Datei neu aufnehmen, um grün zu
erzwingen.

## 7. Commit-Message-Vorschlag

```
test: snapshot the single-action, reset, and repair-service dialogs

Update-check dialogs are documented as a gap in TEST-GAPS.md if the
raw Thread in UpdateChecker.checkInBackground can't be synchronized
deterministically within this step's time box.
```
