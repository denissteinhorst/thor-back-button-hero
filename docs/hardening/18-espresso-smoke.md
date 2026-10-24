# Schritt 18 — Device: Espresso-Smoke auf `SettingsActivity`

## 1. Ziel

Einen einzigen, zeitlich begrenzten instrumentierten Test auf dem echten Gerät, der beweist,
dass `SettingsActivity` auf echtem Android (nicht nur Robolectric) startet und ihre
Kern-Rows anzeigt.

## 2. Voraussetzungen

Schritt 17 ist committet und `./tools/verify.sh` läuft grün. Ein Gerät ist per `adb`
verbunden.

**Zeitbox:** maximal 30 Minuten. Abbruchkriterium: wenn nach drei
`connectedDebugAndroidTest`-Läufen derselbe Fehler auftritt (z. B. Signing-/
Instrumentation-Runner-Problem), stoppen, exakt die Fehlermeldung melden, nicht weiter
variieren.

## 3. Exakte Aufgaben

1. Datei anlegen
   `app/src/androidTest/java/dev/steinhorst/backbuttonhero/SettingsActivitySmokeTest.kt`:
   ```kotlin
   package dev.steinhorst.backbuttonhero

   import androidx.test.core.app.ActivityScenario
   import androidx.test.ext.junit.runners.AndroidJUnit4
   import org.junit.Assert.assertTrue
   import org.junit.Test
   import org.junit.runner.RunWith

   @RunWith(AndroidJUnit4::class)
   class SettingsActivitySmokeTest {

       @Test
       fun settingsActivityLaunchesAndShowsCoreSections() {
           ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
               scenario.onActivity { activity ->
                   val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                   val allText = StringBuilder()
                   collectText(content, allText)
                   val text = allText.toString()
                   assertTrue(text.contains(activity.getString(R.string.single_title)))
                   assertTrue(text.contains(activity.getString(R.string.shoulder_title)))
                   assertTrue(text.contains(activity.getString(R.string.help_debug_title)))
               }
           }
       }

       private fun collectText(view: android.view.View, out: StringBuilder) {
           if (view is android.widget.TextView) out.append(view.text).append('\n')
           if (view is android.view.ViewGroup) {
               for (i in 0 until view.childCount) collectText(view.getChildAt(i), out)
           }
       }
   }
   ```
2. Ausführen:
   ```sh
   JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
     ./gradlew connectedDebugAndroidTest --console=plain
   ```

## 4. Nicht anfassen

- Kein Produktivcode.
- Kein zweiter instrumentierter Test in diesem Schritt — bewusst nur dieser eine Smoke-Test,
  um die Zeitbox einzuhalten; weitere `androidTest`-Abdeckung ist kein Teil dieses Plans.

## 5. Akzeptanzkriterien

- `connectedDebugAndroidTest` zeigt `1 test, 0 failed` auf dem angeschlossenen Gerät.
- `./tools/verify.sh` bleibt grün (der `verify`-Task ruft `connectedDebugAndroidTest`
  **nicht** auf — das Gate bleibt geräteunabhängig, wie in `00-OVERVIEW.md` festgelegt).

## 6. Gate

`./tools/verify.sh` für den automatisierten Teil, plus der manuelle
`connectedDebugAndroidTest`-Lauf aus Aufgabe 2. Bei Rot innerhalb der Zeitbox: stoppen,
exakte Fehlermeldung melden.

## 7. Commit-Message-Vorschlag

```
test: add a time-boxed instrumented smoke test for SettingsActivity

Proves the activity launches and shows its core sections on real
Android, not just Robolectric. Not part of the automated ./tools/verify.sh
gate - requires a connected device.
```
