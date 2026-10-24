# Schritt 10 — View-Tree-Snapshot-Infrastruktur

## 1. Ziel

Eine plattformunabhängige, deterministische Mechanik schaffen, mit der ein View-Baum als
Text serialisiert und gegen eine Golden-Datei verglichen werden kann — die Grundlage für den
UI-Schutz in den Schritten 11–13.

## 2. Voraussetzungen

Schritt 09 ist committet und `./tools/verify.sh` läuft grün.

## 3. Exakte Aufgaben

1. Verzeichnis `app/src/test/resources/golden/` anlegen (leer, mit `.gitkeep`).
2. Datei anlegen `app/src/test/java/dev/steinhorst/backbuttonhero/support/ViewTree.kt`:

   ```kotlin
   package dev.steinhorst.backbuttonhero.support

   import android.view.View
   import android.view.ViewGroup
   import android.widget.CompoundButton
   import android.widget.SeekBar
   import android.widget.TextView

   /**
    * Deterministic, platform-independent text serialization of a View tree: enough detail to
    * catch an accidental layout/styling/text regression, nothing that varies between machines
    * (no absolute pixel positions unless pinned by a Robolectric density qualifier).
    */
   object ViewTree {

       fun describe(root: View, indent: String = ""): String {
           val sb = StringBuilder()
           describeInto(root, indent, sb)
           return sb.toString()
       }

       private fun describeInto(view: View, indent: String, sb: StringBuilder) {
           sb.append(indent).append(lineFor(view)).append('\n')
           if (view is ViewGroup) {
               for (i in 0 until view.childCount) {
                   describeInto(view.getChildAt(i), "$indent  ", sb)
               }
           }
       }

       private fun lineFor(view: View): String {
           val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
           val parts = mutableListOf(
               view.javaClass.simpleName,
               "visibility=${visibilityName(view.visibility)}",
               "minHeight=${view.minimumHeight}",
               "padding=[${view.paddingLeft},${view.paddingTop},${view.paddingRight},${view.paddingBottom}]",
               "clickable=${view.isClickable}",
               "focusable=${view.isFocusable}",
           )
           if (lp != null) {
               parts += "margin=[${lp.marginStart},${lp.topMargin},${lp.marginEnd},${lp.bottomMargin}]"
           }
           view.contentDescription?.let { parts += "contentDescription=\"$it\"" }
           when (view) {
               is TextView -> {
                   parts += "text=\"${view.text}\""
                   parts += "textSizePx=${view.textSize}"
                   parts += "textColor=${String.format("#%08X", view.currentTextColor)}"
                   parts += "maxLines=${view.maxLines}"
                   parts += "bold=${view.typeface?.isBold == true}"
               }
               is CompoundButton -> parts += "checked=${view.isChecked}"
               is SeekBar -> {
                   parts += "max=${view.max}"
                   parts += "progress=${view.progress}"
               }
           }
           return parts.joinToString(" ")
       }

       private fun visibilityName(v: Int) = when (v) {
           View.VISIBLE -> "VISIBLE"
           View.INVISIBLE -> "INVISIBLE"
           View.GONE -> "GONE"
           else -> "UNKNOWN($v)"
       }
   }
   ```

3. Datei anlegen `app/src/test/java/dev/steinhorst/backbuttonhero/support/Golden.kt`:

   ```kotlin
   package dev.steinhorst.backbuttonhero.support

   import org.junit.Assert.assertEquals
   import org.junit.Assert.fail
   import java.io.File

   /**
    * Compares [actual] against the golden file at
    * app/src/test/resources/golden/[name].txt. Set UPDATE_GOLDENS=1 to (re)write it instead
    * of comparing — only ever run with that flag after an explicit, user-approved UI change.
    */
   object Golden {
       fun verify(name: String, actual: String) {
           val file = File("src/test/resources/golden/$name.txt")
           if (System.getenv("UPDATE_GOLDENS") == "1") {
               file.parentFile?.mkdirs()
               file.writeText(actual)
               return
           }
           if (!file.exists()) {
               fail(
                   "Golden file $file does not exist. If this is an intentional new " +
                       "snapshot, re-run with UPDATE_GOLDENS=1 - only after explicit user " +
                       "approval, never to make a failing test pass."
               )
           }
           assertEquals(file.readText(), actual)
       }
   }
   ```

4. Smoke-Test für die Infrastruktur selbst anlegen
   `app/src/test/java/dev/steinhorst/backbuttonhero/support/ViewTreeSmokeTest.kt`:
   ```kotlin
   package dev.steinhorst.backbuttonhero.support

   import android.widget.TextView
   import org.junit.Assert.assertTrue
   import org.junit.Test
   import org.junit.runner.RunWith
   import org.robolectric.RobolectricTestRunner
   import org.robolectric.RuntimeEnvironment

   @RunWith(RobolectricTestRunner::class)
   class ViewTreeSmokeTest {
       @Test
       fun `describe renders class name and text for a simple TextView`() {
           val view = TextView(RuntimeEnvironment.getApplication()).apply { text = "hello" }
           val described = ViewTree.describe(view)
           assertTrue(described.contains("TextView"))
           assertTrue(described.contains("text=\"hello\""))
       }
   }
   ```

## 4. Nicht anfassen

- Kein Produktivcode. Dieser Schritt legt ausschließlich Testinfrastruktur unter
  `app/src/test/java/.../support/` an.
- `Golden.verify` darf in diesem Schritt noch nicht für echte App-Screens benutzt werden —
  das sind die Schritte 11–13.

## 5. Akzeptanzkriterien

- `./tools/verify.sh` grün.
- `ViewTreeSmokeTest` grün.

## 6. Gate

`./tools/verify.sh`. Bei Rot: das neue `support/`-Verzeichnis unter `src/test` zurücknehmen,
stoppen, melden.

## 7. Commit-Message-Vorschlag

```
test: add deterministic View-tree snapshot infrastructure

ViewTree.describe() serializes a View subtree to stable text (no raw
pixel positions); Golden.verify() compares it against a committed file,
gated behind UPDATE_GOLDENS=1 for intentional, approved re-recording.
```
