# Schritt 31 — Baseline-Messung

## 1. Ziel

Vor jeder Performance-/Memory-Änderung konkrete Zahlen auf dem echten Gerät messen:
Speicherverbrauch, Frame-Performance, Startzeit, APK-Größe. Ohne diese Baseline darf in
Phase 4 nichts optimiert werden (globale Regel 7).

## 2. Voraussetzungen

Schritt 30 ist committet, `./tools/verify.sh` läuft grün, ein Gerät ist per `adb` verbunden.

**Zeitbox:** 30 Minuten für den Geräteanteil.

**Vorher lesen: `docs/hardening/PITFALLS.md`, insbesondere P5** — ein lokal gebautes
Release-APK ist **unsigniert und nicht installierbar**. Gemessen wird deshalb auf dem Gerät
der Debug-Build; nur die APK-Größe wird am Release-Artefakt abgelesen.

## 3. Exakte Aufgaben

1. Debug-APK installieren (das Release-APK ist lokal unsigniert — `adb install` würde es
   mit `INSTALL_PARSE_FAILED_NO_CERTIFICATES` ablehnen). Für diese App ist das vertretbar,
   weil `isMinifyEnabled = false` im Release-Buildtype gesetzt ist, es also keine
   Minifizierung gibt und die Code-Form beider Varianten praktisch identisch ist:
   ```sh
   JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
     ./gradlew installDebug --console=plain
   ```
2. APK-Größe am **Release**-Artefakt messen (dafür muss nichts installiert werden) und die
   Debug-Größe zur Information mit notieren:
   ```sh
   JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
     ./gradlew assembleRelease --console=plain
   ls -la app/build/outputs/apk/release/
   ls -la app/build/outputs/apk/debug/
   ```
   Den genauen Dateinamen aus der `ls`-Ausgabe übernehmen — er lautet lokal
   `app-release-unsigned.apk`, in CI (mit Keystore) `app-release.apk`.
3. Kaltstart-Zeit der Settings-Activity:
   ```sh
   adb shell am force-stop dev.steinhorst.backbuttonhero
   adb shell am start -W -n dev.steinhorst.backbuttonhero/.SettingsActivity
   ```
   `TotalTime`-Wert aus der Ausgabe notieren. Dreimal wiederholen (mit `force-stop`
   dazwischen), Median notieren.
4. Speicherverbrauch nach normaler Nutzung (Service läuft, Settings einmal geöffnet,
   Switcher-Overlay einmal geöffnet und wieder geschlossen — das lässt den Thumbnail-Cache
   mindestens einen Eintrag haben):
   ```sh
   adb shell dumpsys meminfo dev.steinhorst.backbuttonhero
   ```
   Komplette Ausgabe sichern, insbesondere `TOTAL PSS`, `Native Heap`, `Graphics`.
5. Grafik-Performance beim Öffnen/Schließen des Switcher-Overlays (Doppeltipp auf dem
   Gerät, vom Nutzer ausgeführt — nicht automatisierbar):
   ```sh
   adb shell dumpsys gfxinfo dev.steinhorst.backbuttonhero reset
   # Nutzer öffnet/schließt das Overlay 5x
   adb shell dumpsys gfxinfo dev.steinhorst.backbuttonhero
   ```
   `Janky frames`-Prozentsatz notieren.
6. Alles in `docs/hardening/baseline/perf-baseline.md` dokumentieren: Datum, Geräte-Modell
   (`adb shell getprop ro.product.model`), jede der obigen Zahlen mit dem exakten Befehl, der
   sie erzeugt hat (damit Schritt 36 identisch reproduzieren kann).

## 4. Nicht anfassen

- Kein Produktivcode — dieser Schritt ist eine reine Messung.
- Keine Optimierung aufgrund dieser Zahlen in diesem Schritt — das sind die Schritte 33–35,
  und nur mit konkretem Beleg aus diesem Report oder Schritt 32.

## 5. Akzeptanzkriterien

- `docs/hardening/baseline/perf-baseline.md` enthält alle 5 Messwert-Kategorien mit
  Rohdaten und den exakten Befehlen.
- `./tools/verify.sh` grün (unverändert).

## 6. Gate

`./tools/verify.sh` für den automatisierten Teil. Für den Geräteteil: innerhalb der Zeitbox,
sonst stoppen und melden, welcher Messschritt nicht durchführbar war (z. B. weil
`dumpsys gfxinfo` eine Nutzerinteraktion braucht, die nicht automatisiert ausgeführt werden
kann).

## 7. Commit-Message-Vorschlag

```
docs: record a performance baseline before any optimization

APK size, cold-start time, dumpsys meminfo, and dumpsys gfxinfo on the
real device, with the exact commands used - step 36 reproduces this
after every fix to prove no regression and no UX change.
```
