# Schritt 36 — Re-Messung + Vergleichsreport

## 1. Ziel

Jede in Phase 4 tatsächlich vorgenommene Änderung (Schritte 33–35) gegen die Baseline aus
Schritt 31 nachmessen und belegen: keine Regression, keine sichtbare UX-Differenz.

## 2. Voraussetzungen

Schritte 33, 34, 35 sind jeweils entweder umgesetzt oder bewusst als „übersprungen — kein
Messbeleg" markiert, und `./tools/verify.sh` läuft grün.

## 3. Exakte Aufgaben

1. **Exakt dieselben Befehle wie in Schritt 31**, Schritt für Schritt wiederholen:
   `installDebug`, APK-Größe am Release-Artefakt ablesen, `am start -W` ×3 mit `force-stop`
   dazwischen, `dumpsys meminfo` nach identischer Nutzungssequenz, `dumpsys gfxinfo
   reset`/-Auslesen nach 5× Switcher-Öffnen/Schließen.

   **Dieselbe Variante wie in Schritt 31 verwenden** — gemessen wird der Debug-Build, weil
   das lokale Release-APK unsigniert und nicht installierbar ist (`PITFALLS.md` P5). Ein
   Vergleich Debug-vorher gegen Release-nachher wäre wertlos.
2. Ergebnis in `docs/hardening/PERF-RESULT.md` als Vergleichstabelle gegen
   `docs/hardening/baseline/perf-baseline.md`:

   | Messung | Baseline (Schritt 31) | Nach Phase 4 | Differenz |
   |---|---|---|---|
   | APK-Größe | … | … | … |
   | Kaltstart (Median) | … | … | … |
   | TOTAL PSS | … | … | … |
   | Native Heap | … | … | … |
   | Graphics | … | … | … |
   | Janky frames % | … | … | … |

3. Für jede Zeile mit einer Verschlechterung: Ursache untersuchen — eine Verschlechterung
   nach reinen Performance-Fixes ist unerwartet und muss verstanden werden, bevor dieser
   Schritt abgeschlossen wird.
4. **Verpflichtende UX-Gegenprobe:** der Nutzer arbeitet
   `docs/hardening/MANUAL-GESTURE-CHECKLIST.md` vollständig ab (alle Sektionen, nicht nur
   Back-Taste — Schritte 33–35 betreffen potenziell Switcher-Overlay-Thumbnails und
   App-Icons). Jede Abweichung vom erwarteten Verhalten ist ein Blocker für diesen Schritt.

## 4. Nicht anfassen

- Keine weitere Optimierung „weil gerade dabei" — dieser Schritt ist ausschließlich Messung
  und Dokumentation der bereits in 33–35 vorgenommenen Änderungen.

## 5. Akzeptanzkriterien

- `docs/hardening/PERF-RESULT.md` existiert mit vollständiger Vergleichstabelle.
- Keine Zeile zeigt eine Verschlechterung ohne dokumentierte, verstandene Begründung.
- Die manuelle Checkliste ist vom Nutzer vollständig und ohne Abweichung abgehakt.
- `./tools/verify.sh` grün.

## 6. Gate

`./tools/verify.sh` plus die beiden manuellen Bestätigungen aus Aufgabe 3/4. Bei einer
unerklärten Verschlechterung oder einer UX-Abweichung: den zugehörigen Fix-Schritt (33, 34
oder 35) per `git revert` zurücknehmen, stoppen, melden.

## 7. Commit-Message-Vorschlag

```
docs: re-measure after Phase 4 fixes, confirm no regression

PERF-RESULT.md compares every metric against the step-31 baseline.
Manual gesture checklist confirms zero observable UX difference.
```
