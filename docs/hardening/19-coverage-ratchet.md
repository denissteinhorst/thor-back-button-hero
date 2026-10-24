# Schritt 19 — Coverage-Ratchet

## 1. Ziel

JaCoCo einrichten, die **tatsächlich erreichte** Coverage der kritischen Klassen messen und
diese gemessenen Werte als Mindestschwellen festschreiben, die künftig nur steigen dürfen.

## 2. Voraussetzungen

Schritt 18 ist committet und `./tools/verify.sh` läuft grün.

**Vorher lesen: `docs/hardening/PITFALLS.md`, insbesondere P4** — ohne
`enableUnitTestCoverage` entsteht gar keine Coverage-Datei, und `limit { }` ohne `counter`
misst etwas anderes als gemeint.

## 3. Exakte Aufgaben

### 3.1 JaCoCo-Plugin aktivieren

In `app/build.gradle.kts`, im `plugins { }`-Block ganz oben, ergänzen:
```kotlin
id("jacoco")
```
Im **root** `build.gradle.kts` ist **keine** Änderung nötig (`jacoco` ist ein
Gradle-Core-Plugin und braucht keinen `apply false`-Eintrag).

### 3.2 Unit-Test-Coverage im Debug-Buildtype aktivieren — nicht überspringen

`app/build.gradle.kts` hat derzeit nur einen `release { }`-Block. Den `buildTypes { }`-Block
erweitern, sodass er so aussieht:
```kotlin
buildTypes {
    debug {
        enableUnitTestCoverage = true
    }
    release {
        if (releaseKeystorePath != null) {
            signingConfig = signingConfigs.getByName("release")
        }
        isMinifyEnabled = false
    }
}
```
Der `release`-Block bleibt **wortgleich** wie vorher — nur der `debug`-Block kommt hinzu.
Ohne `enableUnitTestCoverage = true` erzeugt `testDebugUnitTest` keine `.exec`-Datei und jeder
Report bleibt leer, **ohne dass der Build rot wird** (stiller Fehlschlag).

### 3.3 Report-Task

In `app/build.gradle.kts`, nach dem `android { }`-Block, ergänzen:
```kotlin
jacoco {
    toolVersion = "0.8.11"
}

private val coverageClassFilter = listOf(
    "**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*",
)

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    sourceDirectories.setFrom(files("src/main/java"))
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
            exclude(coverageClassFilter)
        }
    )
    executionData.setFrom(
        fileTree(layout.buildDirectory) { include("**/testDebugUnitTest.exec") }
    )
}
```

### 3.4 Erst messen, dann die Schwellen festlegen

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew jacocoTestReport --console=plain
open app/build/reports/jacoco/jacocoTestReport/html/index.html
```
**Zuerst prüfen, ob überhaupt Daten da sind.** Zeigt der Report „No data" oder keine Klassen:
```sh
find app/build -name "*.exec"
find app/build -name "*.class" -path "*backbuttonhero*" | head -3
```
und die Pfade in 3.3 an die tatsächlichen Fundorte anpassen. Erst weitermachen, wenn der
Report echte Zahlen zeigt.

Dann für diese sechs Klassen die **Line-Coverage** aus dem Report ablesen und notieren:
`BackGestureMachine`, `Prefs`, `DoubleTapDetector`, `AccessibilityAppSource`,
`SettingsUiKit` (bzw. `SettingsUiKitKt` für die Top-Level-Funktionen), `UpdateChecker`.

### 3.5 Schwellen = gemessener Wert, abgerundet auf 5 %

Für jede Klasse die Schwelle auf den **gemessenen Wert, abgerundet auf das nächste
Fünf-Prozent-Vielfache** setzen. Beispiel: gemessen 0.93 → Schwelle `"0.90"`; gemessen 0.87 →
Schwelle `"0.85"`; gemessen 1.00 → Schwelle `"1.00"`.

**Warum nicht feste Wunschwerte:** eine Schwelle über dem gemessenen Wert macht den
Gate-Befehl sofort und dauerhaft rot und blockiert jeden weiteren Schritt. Eine Schwelle auf
dem gemessenen Wert ist ein echter Ratchet: sie kann nie unbemerkt sinken, und sie lässt sich
später anheben, wenn Tests hinzukommen.

**Erwartungswerte zur Orientierung** (nur als Plausibilitätsprüfung — gilt der gemessene
Wert, nicht dieser): `DoubleTapDetector` sehr hoch (~1.00), `BackGestureMachine` hoch
(~0.85–0.95), `Prefs` hoch (~0.85–0.95), `AccessibilityAppSource` niedrig (~0.30–0.60, weil
`record`/`isCovered`/der Screenshot-Pfad laut `TEST-GAPS.md` nicht testbar sind),
`UpdateChecker` sehr niedrig (~0.05–0.20, der Seam kommt erst in Schritt 26).

Liegt ein gemessener Wert **deutlich** unter dem Orientierungsbereich (z. B.
`BackGestureMachine` unter 0.60): das ist ein Hinweis, dass Schritt 17 Testfälle nicht
ausführt oder die Klassenpfade in 3.3 falsch sind. Dann **nicht** einfach eine niedrige
Schwelle einzementieren, sondern die Ursache klären bzw. melden.

### 3.6 Verification-Task mit den ermittelten Werten

```kotlin
tasks.register<JacocoCoverageVerification>("jacocoCoverageVerification") {
    dependsOn("jacocoTestReport")
    sourceDirectories.setFrom(files("src/main/java"))
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
            exclude(coverageClassFilter)
        }
    )
    executionData.setFrom(
        fileTree(layout.buildDirectory) { include("**/testDebugUnitTest.exec") }
    )

    violationRules {
        // One rule per class. `counter` and `value` are mandatory: without them JaCoCo
        // measures INSTRUCTION coverage, not LINE. Raise these numbers when tests grow -
        // never lower them.
        rule {
            element = "CLASS"
            includes = listOf("dev.steinhorst.backbuttonhero.BackGestureMachine")
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "<gemessen, abgerundet>".toBigDecimal()
            }
        }
        // ... je eine weitere rule für Prefs, DoubleTapDetector, AccessibilityAppSource
    }
}
```
Regeln für `CardTouch` (Schritt 27) und `UpdateChecker` (Schritt 26) kommen **nicht** jetzt
dazu — die Seams existieren noch nicht. Beide Schritte ergänzen dort je eine weitere `rule`.

Für `AccessibilityAppSource` und `UpdateChecker` jetzt nur dann eine Regel aufnehmen, wenn
der gemessene Wert ≥ 0.20 ist — bei einer Schwelle unter 0.20 schützt die Regel nichts und
erzeugt nur Rauschen; in dem Fall die Klasse hier weglassen und in der Commit-Message
vermerken.

### 3.7 Gate erweitern

Im **root** `build.gradle.kts` den `verify`-Task aus Schritt 03 um genau eine Zeile erweitern:
```kotlin
dependsOn(
    ":app:lintDebug",
    ":app:testDebugUnitTest",
    ":app:jacocoCoverageVerification",
    ":app:assembleDebug",
    ":app:assembleRelease",
)
```

## 4. Nicht anfassen

- Keine Schwelle über den gemessenen Wert setzen (macht den Gate dauerhaft rot).
- Keine Schwelle später senken — Senken ist ein Regelbruch, kein Fix.
- Kein Produktivcode-Verhalten ändern, um Coverage zu erhöhen.

## 5. Akzeptanzkriterien

- `./tools/verify.sh` grün, inklusive `jacocoCoverageVerification`.
- `app/build/reports/jacoco/jacocoTestReport/html/index.html` zeigt echte Zahlen, nicht
  „No data".
- Jede `rule` im Build-File nennt explizit `counter = "LINE"` und `value = "COVEREDRATIO"`.
- Die gemessenen Ausgangswerte sind in der Commit-Message festgehalten (damit später
  nachvollziehbar ist, von welchem Niveau der Ratchet gestartet ist).

## 6. Gate

`./tools/verify.sh`. Bei Rot wegen zu niedriger Coverage: prüfen, ob die Schwelle über dem
gemessenen Wert liegt (dann Schwelle auf den gemessenen Wert korrigieren — das ist kein
Senken, sondern das erstmalige korrekte Setzen). Bei Rot wegen leerem Report: Pfade gemäß 3.4
korrigieren.

## 7. Commit-Message-Vorschlag

```
build: add JaCoCo coverage with measured per-class minimums

enableUnitTestCoverage is now on for debug. Thresholds are set to the
measured line coverage rounded down to the nearest 5%, so the gate is
green from the start and can only ratchet upward:
  BackGestureMachine <x>, Prefs <x>, DoubleTapDetector <x>,
  AccessibilityAppSource <x>
CardTouch and UpdateChecker join in steps 27 and 26.
```
