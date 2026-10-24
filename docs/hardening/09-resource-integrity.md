# Schritt 09 — Ressourcen-Integrität

## 1. Ziel

Lokalisierungs-Parität zwischen `values/strings.xml` und `values-de/strings.xml`
automatisiert absichern und das heutige Lint-Warnungs-Inventar als Golden-Datei einfrieren,
damit eine neue, bisher unbekannte Lint-Warnung sofort auffällt.

## 2. Voraussetzungen

Schritt 08 ist committet und `./tools/verify.sh` läuft grün.

## 3. Exakte Aufgaben

1. Datei anlegen `app/src/test/java/dev/steinhorst/backbuttonhero/ResourceIntegrityTest.kt`.
   Diese Tests parsen die beiden XML-Dateien direkt (kein Robolectric nötig — reines
   `javax.xml`/`java.io`):

   ```kotlin
   package dev.steinhorst.backbuttonhero

   import org.junit.Assert.assertEquals
   import org.junit.Assert.assertTrue
   import org.junit.Test
   import java.io.File
   import javax.xml.parsers.DocumentBuilderFactory

   class ResourceIntegrityTest {

       private fun stringEntries(path: String): Map<String, Boolean> {
           // name -> translatable (default true, since the attribute is only set to override)
           val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
           val nodes = doc.getElementsByTagName("string")
           return buildMap {
               for (i in 0 until nodes.length) {
                   val el = nodes.item(i) as org.w3c.dom.Element
                   val name = el.getAttribute("name")
                   val translatable = el.getAttribute("translatable") != "false"
                   put(name, translatable)
               }
           }
       }

       @Test
       fun `every translatable string in values has a DE counterpart, and nothing extra exists`() {
           val en = stringEntries("src/main/res/values/strings.xml")
           val de = stringEntries("src/main/res/values-de/strings.xml")
           val expectedDeKeys = en.filterValues { it }.keys
           assertEquals(expectedDeKeys, de.keys)
       }

       @Test
       fun `app_name is marked non-translatable and has no DE entry`() {
           val en = stringEntries("src/main/res/values/strings.xml")
           val de = stringEntries("src/main/res/values-de/strings.xml")
           assertEquals(false, en["app_name"])
           assertTrue("app_name" !in de)
       }

       @Test
       fun `every placeholder in a DE string also appears in the EN original`() {
           // ${'$'} is how a literal dollar sign is written in Kotlin - a bare $ would be
           // parsed as the start of a string template and fail to compile.
           val placeholderRegex = Regex("""%\d*${'$'}?[sd]""")
           fun placeholders(path: String): Map<String, Set<String>> {
               val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
               val nodes = doc.getElementsByTagName("string")
               return buildMap {
                   for (i in 0 until nodes.length) {
                       val el = nodes.item(i) as org.w3c.dom.Element
                       put(el.getAttribute("name"), placeholderRegex.findAll(el.textContent).map { it.value }.toSet())
                   }
               }
           }
           val en = placeholders("src/main/res/values/strings.xml")
           val de = placeholders("src/main/res/values-de/strings.xml")
           de.forEach { (name, dePlaceholders) ->
               assertEquals("placeholder mismatch in $name", en[name].orEmpty(), dePlaceholders)
           }
       }
   }
   ```

   **Pfad-Hinweis:** Gradle-Unit-Tests laufen mit dem Arbeitsverzeichnis `app/`, daher sind
   die Pfade relativ zu `app/` angegeben (`src/main/res/...`), nicht `app/src/main/res/...`.
   Falls der Testlauf die Dateien nicht findet: `System.getProperty("user.dir")` in einer
   Fehlermeldung ausgeben lassen und den Pfad entsprechend anpassen, bevor an der Testlogik
   etwas geändert wird.

2. Lint-Warnungs-Inventar als Golden ablegen. Lint erneut laufen lassen und das Ergebnis mit
   der Baseline aus Schritt 01 vergleichen:
   ```sh
   JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
     ./gradlew lintDebug --console=plain
   diff docs/hardening/baseline/lint-baseline-report.txt app/build/reports/lint-results-debug.txt
   ```
   Der Diff muss leer sein (dieser Schritt fügt keine Produktivcode-Änderung hinzu, die neue
   Warnungen erzeugen könnte). Datei kopieren:
   ```sh
   cp app/build/reports/lint-results-debug.txt docs/hardening/baseline/lint-warning-inventory.txt
   ```

## 4. Nicht anfassen

- Keine `strings.xml`-Datei inhaltlich ändern — auch nicht, um eine fehlende Übersetzung zu
  ergänzen. Entdeckt dieser Schritt eine echte Lücke: **nicht fixen**, sondern dem Nutzer
  melden und in `docs/hardening/TEST-GAPS.md` oder einem neuen Fund-Abschnitt dokumentieren.
- Keine Produktivcode-Datei ändern.

## 5. Akzeptanzkriterien

- `./tools/verify.sh` grün.
- Alle 3 `ResourceIntegrityTest`-Fälle grün (bestätigt die 88/87-Differenz ist korrekt).
- `diff` zwischen Baseline- und aktuellem Lint-Report ist leer.

## 6. Gate

`./tools/verify.sh`. Bei Rot (insbesondere falls Test 1 eine echte Lücke in `values-de`
findet): **nicht den Test anpassen, um ihn grün zu bekommen** — stattdessen stoppen und dem
Nutzer die konkrete fehlende/überschüssige Übersetzung melden. Die Entscheidung, ob das vor
dem Release gefixt wird, liegt beim Nutzer.

## 7. Commit-Message-Vorschlag

```
test: lock DE/EN string parity and freeze the lint warning inventory

Catches a future string added to one locale but not the other, and any
new lint warning beyond the 15 recorded in the baseline.
```
