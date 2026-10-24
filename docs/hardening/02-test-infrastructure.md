# Schritt 02 — Test-Infrastruktur

## 1. Ziel

Test- und Instrumentierungs-Dependencies sowie die Robolectric-Konfiguration einrichten und
mit genau einem grünen Smoke-Test beweisen, dass der Testharness funktioniert.

## 2. Voraussetzungen

Schritt 01 ist committet und grün.

**Vorher lesen: `docs/hardening/PITFALLS.md`, insbesondere P7** (Arbeitsverzeichnis von
Unit-Tests ist `app/`, nicht das Repo-Root — relevant für alle späteren dateibasierten Tests).

## 3. Exakte Aufgaben

1. `app/build.gradle.kts` öffnen. Im `android { }`-Block **innerhalb** von `defaultConfig { }`
   folgende Zeile ergänzen (an beliebiger Stelle innerhalb des Blocks, z. B. nach
   `versionName`):
   ```kotlin
   testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
   ```
   Direkt nach dem `defaultConfig { }`-Block, noch innerhalb von `android { }`, neu ergänzen:
   ```kotlin
   testOptions {
       unitTests {
           isIncludeAndroidResources = true
       }
   }
   ```
2. Am Ende von `app/build.gradle.kts` (nach der schließenden `}` von `android { }`) einen
   neuen `dependencies { }`-Block anfügen — exakt diese Versionen, keine anderen:
   ```kotlin
   dependencies {
       testImplementation("junit:junit:4.13.2")
       testImplementation("org.robolectric:robolectric:4.12.2")
       testImplementation("androidx.test:core:1.5.0")
       testImplementation("androidx.test.ext:junit:1.1.5")
       androidTestImplementation("androidx.test.ext:junit:1.1.5")
       androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
       androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
   }
   ```
   `debugImplementation("com.squareup.leakcanary:...")` kommt **nicht** jetzt — das ist
   Schritt 32. Hier nur die sechs Zeilen oben.
3. Datei anlegen `app/src/test/resources/robolectric.properties`:
   ```properties
   sdk=34
   ```
4. Datei anlegen `app/src/test/java/dev/steinhorst/backbuttonhero/SmokeTest.kt`:
   ```kotlin
   package dev.steinhorst.backbuttonhero

   import androidx.test.core.app.ApplicationProvider
   import org.junit.Assert.assertNotNull
   import org.junit.Test
   import org.junit.runner.RunWith
   import org.robolectric.RobolectricTestRunner

   @RunWith(RobolectricTestRunner::class)
   class SmokeTest {
       @Test
       fun `Prefs can be constructed against a Robolectric application context`() {
           val prefs = Prefs(ApplicationProvider.getApplicationContext())
           assertNotNull(prefs)
       }
   }
   ```
5. Testlauf:
   ```sh
   JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
     ./gradlew testDebugUnitTest --console=plain
   ```

## 4. Nicht anfassen

- Keine Zeile in `app/src/main/**` ändern.
- Keine weiteren Dependencies außer den sechs oben genannten hinzufügen.
- Keine `implementation(...)`-Zeile — alle neuen Dependencies sind `test`/`androidTest`-scoped.

## 5. Akzeptanzkriterien

- `JAVA_HOME=... ./gradlew testDebugUnitTest --console=plain` → `BUILD SUCCESSFUL`,
  Testreport zeigt `1 test completed, 0 failed`.
- `JAVA_HOME=... ./gradlew assembleDebug lintDebug --console=plain` ist weiterhin grün
  (Dependencies dürfen den bestehenden Build nicht brechen).

## 6. Gate

Noch kein `verify`-Befehl. Gate ist der Befehl aus Aufgabe 5 plus der
Akzeptanzkriterien-Zusatzlauf. Bei Rot: `git restore app/build.gradle.kts` und die neuen
Dateien löschen, stoppen, melden.

**Erwartbare Stolperstellen beim allerersten Robolectric-Lauf:**
- Robolectric lädt beim ersten Testlauf ein `android-all-instrumented`-JAR (~100 MB) von
  Maven Central nach. Der erste Lauf dauert deshalb mehrere Minuten und **braucht
  Netzwerkzugang**. Das ist normal, kein Fehler. Schlägt der Download fehl, zeigt die
  Fehlermeldung eine Maven-Koordinate — dann Netzwerk/Proxy prüfen, nicht die Konfiguration
  umbauen.
- Meldet der Lauf einen Fehler zu `sdk=34` (z. B. „Unable to resolve sdk"), ist die
  Robolectric-Version zu alt für API 34. Dann **melden**, nicht auf `sdk=33` ausweichen —
  `compileSdk`/`targetSdk` der App sind 34, und ein Test gegen eine andere API-Ebene würde
  anderes Verhalten charakterisieren als das ausgelieferte.

## 7. Commit-Message-Vorschlag

```
test: add Robolectric/JUnit/Espresso test infrastructure

Adds test-/androidTest-/debug-scoped dependencies only (JUnit, Robolectric,
androidx.test, Espresso, UiAutomator). Production dependency graph is
unchanged. One smoke test proves the Robolectric harness runs.
```
