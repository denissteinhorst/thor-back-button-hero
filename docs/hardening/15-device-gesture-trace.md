# Schritt 15 — Device: Golden-Logcat-Trace + manuelle Checkliste

## 1. Ziel

Vor der einzigen Produktivcode-Extraktion in diesem Plan (Schritt 16) einen Referenz-Trace
jeder Back-Tasten-Gestenentscheidung aufnehmen, gegen den Schritt 16 später verglichen wird.

## 2. Voraussetzungen

Schritt 14 ist committet und `./tools/verify.sh` läuft grün. Ein AYN Thor ist per `adb`
verbunden (`adb devices` zeigt ein Gerät mit Status `device`, nicht `unauthorized`).

**Zeitbox:** maximal 30 Minuten für den Geräteanteil dieses Schritts. Abbruchkriterium: wenn
nach drei Versuchen kein Build auf das Gerät installierbar ist (z. B. wegen fehlender
Nutzer-Freigabe für die Accessibility-Permission, die nicht automatisierbar ist), stoppen,
dem Nutzer exakt melden, wo es hängt, und auf eine manuelle Fortsetzung warten statt
weiter zu retryen.

## 3. Exakte Aufgaben

1. `SwitcherService.DEBUG_GESTURES` **temporär** auf `true` setzen (Datei
   `app/src/main/java/dev/steinhorst/backbuttonhero/SwitcherService.kt`, companion object).
2. Debug-APK bauen und installieren:
   ```sh
   JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
     ./gradlew installDebug --console=plain
   ```
3. Falls die Accessibility-Permission noch nicht erteilt ist: den Nutzer bitten, die App
   einmal zu öffnen und den Dienst in den Android-Bedienungshilfen zu aktivieren (nicht
   automatisierbar, da Android App-eigene Reaktivierung verweigert — siehe
   `SettingsActivity`'s „Repair service"-Flow).
4. Logcat-Aufnahme starten, dann den Nutzer bitten, **exakt** die Back-Tasten-Sektion aus
   `MANUAL-GESTURE-CHECKLIST.md` durchzugehen (nicht die Shoulder-/Overlay-/Settings-Sektionen
   — die sind für diesen Trace nicht relevant):
   ```sh
   adb logcat -c
   adb logcat -s BackButtonHero:D > /tmp/gesture-trace-raw.txt &
   # Nutzer führt jetzt die Back-Taste-Checkliste aus MANUAL-GESTURE-CHECKLIST.md durch
   # Danach: kill %1  (oder Ctrl+C im Vordergrund-Fall)
   ```
5. Rohdatei bereinigen: Zeitstempel-Spalte entfernen, damit der Trace später diff-stabil
   gegen eine neue Aufnahme ist (nur die `dlog(...)`-Nachrichten behalten, keine
   PID/TID/Timestamp-Präfixe):
   ```sh
   grep -oE 'BackButtonHero: .*' /tmp/gesture-trace-raw.txt > docs/hardening/baseline/gesture-trace.txt
   ```
6. `SwitcherService.DEBUG_GESTURES` **zurück auf `false`** setzen — vor dem Commit. Eine
   versehentlich committete `true` wäre eine Verhaltensänderung für den Produktionsbetrieb
   (lautes Logging bei jedem Back-Press).
7. `docs/hardening/MANUAL-GESTURE-CHECKLIST.md` existiert bereits — in diesem Schritt nur
   bestätigen, dass sie vollständig ist (sie wurde beim Erstellen der Deliverables aus dem
   Plan bereits geschrieben); falls beim Durchgehen eine Gesten-Lücke auffällt, die Datei um
   den fehlenden Punkt ergänzen.
8. Build neu erstellen mit `DEBUG_GESTURES = false` und erneut installieren, damit das
   Gerät nicht mit der Debug-Logging-Version zurückbleibt:
   ```sh
   JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
     ./gradlew installDebug --console=plain
   ```

## 4. Nicht anfassen

- Jede Zeile in `SwitcherService.kt` außer der einen `DEBUG_GESTURES`-Konstante bleibt
  unverändert — und auch die wird am Ende dieses Schritts wieder auf `false` zurückgesetzt,
  sodass der committete Zustand identisch zum Vorher ist.
- Keine andere Datei in `app/src/main/**` anfassen.

## 5. Akzeptanzkriterien

- `docs/hardening/baseline/gesture-trace.txt` existiert, enthält Zeilen für mindestens:
  `DOWN`, `UP`, `DETECTOR double-tap recognized`, `RUNNABLE holdRunnable fired`, `RUNNABLE
  tapHoldRunnable fired`, `RUNNABLE singleTap fired`, `ACTION onSingleTap`.
- `grep "DEBUG_GESTURES = false" app/src/main/java/dev/steinhorst/backbuttonhero/SwitcherService.kt`
  liefert einen Treffer (zurückgesetzt).
- `./tools/verify.sh` grün (die JVM-Suite ist von diesem Geräteschritt unberührt).

## 6. Gate

`./tools/verify.sh` für den automatisierten Teil. Für den Geräteteil: falls das Gerät nicht
erreichbar ist oder die Zeitbox überschritten wird, **stoppen und exakt melden, welcher
Teilschritt hängt** (Build, Install, Permission, Logcat) — nicht beliebig weiter versuchen.

## 7. Commit-Message-Vorschlag

```
docs: record a golden gesture trace before the BackGestureMachine seam

Captures logcat output for every Back-key decision with DEBUG_GESTURES
temporarily on, then restores it to false. Used by step 16 to prove
the extraction didn't change behavior.
```
