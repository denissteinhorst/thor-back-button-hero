# Schritt 14 — A11y-Inventar (Report, kein Fix)

## 1. Ziel

Jede klickbare Row der Settings-Seite auf `contentDescription`, Touch-Target-Größe und
Fokussierbarkeit prüfen, das Ergebnis als Golden-Snapshot einfrieren und als lesbaren Report
dokumentieren — **ohne** einen einzigen gefundenen Mangel zu beheben (globale Regel 6).

## 2. Voraussetzungen

Schritt 13 ist committet und `./tools/verify.sh` läuft grün.

**Vorher lesen: `docs/hardening/PITFALLS.md`, P2 und P7.**

## 3. Exakte Aufgaben

1. Datei anlegen
   `app/src/test/java/dev/steinhorst/backbuttonhero/AccessibilityInventoryTest.kt`:
   ```kotlin
   package dev.steinhorst.backbuttonhero

   import dev.steinhorst.backbuttonhero.support.Golden
   import org.junit.Test
   import org.junit.runner.RunWith
   import org.robolectric.Robolectric
   import org.robolectric.RobolectricTestRunner
   import org.robolectric.annotation.Config

   @RunWith(RobolectricTestRunner::class)
   @Config(qualifiers = "en-xxhdpi")
   class AccessibilityInventoryTest {

       @Test
       fun `every clickable row reports contentDescription, min height in dp, and focusability`() {
           val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
           val root = activity.findViewById<android.view.View>(android.R.id.content)
           val report = StringBuilder()
           collectClickableRows(root, activity.resources.displayMetrics.density, report)
           Golden.verify("a11y_inventory", report.toString())
       }

       private fun collectClickableRows(view: android.view.View, density: Float, out: StringBuilder) {
           if (view.isClickable) {
               val minHeightDp = (view.minimumHeight / density).toInt()
               out.append(
                   "clickable=${view.javaClass.simpleName} " +
                       "minHeightDp=$minHeightDp " +
                       "focusable=${view.isFocusable} " +
                       "contentDescription=${view.contentDescription ?: "(none)"}\n"
               )
           }
           if (view is android.view.ViewGroup) {
               for (i in 0 until view.childCount) collectClickableRows(view.getChildAt(i), density, out)
           }
       }
   }
   ```
2. Golden aufnehmen (`UPDATE_GOLDENS=1`, wie in Schritt 11 beschrieben).
3. `docs/hardening/A11Y-REPORT.md` schreiben: die aufgenommene Golden-Datei lesen und jede
   Zeile mit `minHeightDp < 48` (Android-Mindest-Touch-Target) oder
   `contentDescription=(none)` bei einem Row-Typ, der nicht ohnehin schon sichtbaren Text
   trägt (z. B. `chooserRow`/`toggleRow` haben sichtbaren Titeltext — für die zählt ein
   fehlendes `contentDescription` nicht als Mangel, weil Screenreader den sichtbaren Text
   vorlesen; nur reine Icon-Buttons ohne Text wären kritisch) in einer Tabelle auflisten:
   Zeile, Fund, Schweregrad (informativ — da kein Fix in diesem Plan erfolgt), Verweis auf die
   Zeile in `SettingsUiKit.kt`/`SettingsActivity.kt`.

## 4. Nicht anfassen

- **Keine einzige gefundene A11y-Lücke beheben** — auch keine triviale wie das Ergänzen
  eines `contentDescription`. Das wäre eine UX-Änderung (globale Regel 6).
- Kein Produktivcode.

## 5. Akzeptanzkriterien

- `./tools/verify.sh` grün.
- `docs/hardening/A11Y-REPORT.md` existiert, listet jeden Fund mit Datei+Zeile-Referenz oder
  stellt explizit fest „keine Funde".

## 6. Gate

`./tools/verify.sh`. Bei Rot: stoppen, melden.

## 7. Commit-Message-Vorschlag

```
test: freeze an accessibility inventory of every clickable settings row

Report only - no accessibility fix is applied in this step, per the
no-UX-change rule in 00-OVERVIEW.md.
```
