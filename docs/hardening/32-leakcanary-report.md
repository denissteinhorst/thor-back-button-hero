# Schritt 32 — LeakCanary + Leak-Inventar-Report

## 1. Ziel

LeakCanary als `debugImplementation`-only Dependency einbinden und einen Report über
gefundene Leak-Kandidaten schreiben — in diesem Schritt wird **nichts gefixt**, nur
gemessen und dokumentiert.

## 2. Voraussetzungen

Schritt 31 ist committet (`perf-baseline.md` existiert) und `./tools/verify.sh` läuft grün.

## 3. Exakte Aufgaben

1. In `app/build.gradle.kts`, `dependencies { }`, ergänzen:
   ```kotlin
   debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
   ```
   **Nur** `debugImplementation` — niemals `implementation`. LeakCanary registriert sich
   automatisch über einen `ContentProvider` in seiner eigenen Manifest-Datei, es ist **keine**
   Code-Änderung in `app/src/main/**` nötig.
2. Debug-APK bauen und auf dem Gerät installieren:
   ```sh
   JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
     ./gradlew installDebug --console=plain
   ```
3. Den Nutzer bitten, eine typische Nutzungssitzung zu fahren: Settings öffnen/schließen
   mehrfach, Switcher-Overlay mehrfach öffnen/schließen, mehrere Apps über den Switcher
   wechseln, Screens mehrfach per Hold tauschen — mindestens 10 Minuten aktive Nutzung, da
   LeakCanary Leaks typischerweise erst nach GC-Zyklen meldet.
4. LeakCanary-Benachrichtigungen/-App auf dem Gerät prüfen (LeakCanary installiert eine
   eigene Leaks-App `com.squareup.leakcanary:leakcanary-android` sichtbar im Launcher):
   ```sh
   adb shell dumpsys activity | grep -i leakcanary
   ```
   oder die LeakCanary-App direkt öffnen und jeden gemeldeten Leak-Trace per
   `adb shell run-as dev.steinhorst.backbuttonhero` / Screenshot sichern.
5. Für jeden gefundenen Leak in `docs/hardening/LEAK-REPORT.md` dokumentieren: betroffene
   Klasse, Retention-Pfad (aus dem LeakCanary-Trace), vermutete Ursache mit Verweis auf die
   konkrete Zeile in `app/src/main/java/.../*.kt`. Falls **keine** Leaks gefunden werden:
   das ebenso explizit vermerken („0 Leaks nach 10 Minuten Nutzung, Stand <Datum>") statt die
   Datei leer zu lassen.
6. Mit den Perf-Beobachtungen aus `docs/hardening/00-OVERVIEW.md` (Abschnitt
   „Performance-/Memory-Beobachtungen") abgleichen: bestätigt LeakCanary einen der dort
   genannten Kandidaten (Thumbnail-Cache, Bitmap-Churn, `SwitcherOverlay.windowManager`)?
   Das Ergebnis dieses Abgleichs ist die Entscheidungsgrundlage für Schritte 33–35.

## 4. Nicht anfassen

- **Kein einziger gefundener Leak wird in diesem Schritt gefixt.** Das ist Schritte 33–35,
  und nur, wenn dieser Report (oder die Messung aus Schritt 31) ihn belegt.
- Keine Code-Änderung außer der einen Dependency-Zeile.

## 5. Akzeptanzkriterien

- `docs/hardening/LEAK-REPORT.md` existiert mit entweder dokumentierten Funden oder einer
  expliziten „keine Funde"-Feststellung.
- `./tools/verify.sh` grün — insbesondere `assembleRelease` darf durch die
  `debugImplementation`-Dependency nicht beeinflusst sein (LeakCanary darf nicht im
  Release-Klassenpfad auftauchen, das wird in Schritt 37 final bewiesen, hier reicht eine
  Stichprobe):
  ```sh
  JAVA_HOME=... ./gradlew :app:dependencies --configuration releaseRuntimeClasspath --console=plain | grep -i leakcanary
  ```
  Dieser Befehl darf **keine** Ausgabe liefern.

## 6. Gate

`./tools/verify.sh` plus der `releaseRuntimeClasspath`-Stichprobe aus Aufgabe 5. Bei Rot:
`git restore app/build.gradle.kts`, stoppen, melden.

## 7. Commit-Message-Vorschlag

```
build: add LeakCanary (debug-only) and record a leak inventory report

LEAK-REPORT.md documents what LeakCanary found (or didn't) after a
real usage session. No fix applied here - that's steps 33-35, gated
on evidence from this report.
```
