# Schritt 20 — Suite-Stabilität

## 1. Ziel

Beweisen, dass die bis hier aufgebaute Test-Suite nicht flaky ist, bevor sie als Baseline für
alle folgenden Phasen gilt.

## 2. Voraussetzungen

Schritt 19 ist committet und `./tools/verify.sh` läuft grün.

## 3. Exakte Aufgaben

1. Fünf unabhängige Läufe der Unit-Test-Suite, jeweils mit `--rerun` um Gradles
   Up-to-date-Caching zu umgehen (sonst würde Lauf 2–5 nur den gecachten Erfolg von Lauf 1
   wiederholen, statt die Tests wirklich neu auszuführen):
   ```sh
   for i in 1 2 3 4 5; do
     JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
       ./gradlew testDebugUnitTest --rerun --console=plain \
       > /tmp/stability-run-$i.txt 2>&1
     echo "run $i exit: $?" >> /tmp/stability-run-$i.txt
   done
   grep -l "BUILD FAILED" /tmp/stability-run-*.txt || echo "all 5 runs green"
   ```
2. Ergebnis in `docs/hardening/baseline/stability-run.md` dokumentieren: Datum, alle 5
   Laufzeiten/Ergebnisse, und falls ein Lauf rot war — welcher Test, welche Fehlermeldung.
3. **Falls ein Test flaky ist** (in manchen Läufen grün, in anderen rot): die Ursache
   identifizieren (häufigste Quelle in dieser Codebasis: ein Test, der reale
   `SystemClock`-Zeit statt der injizierten `FakeClock`/`ShadowSystemClock` verwendet, oder
   Testreihenfolge-Abhängigkeit durch geteilten `SharedPreferences`-Zustand zwischen Tests
   ohne Robolectric-Isolation). Den betroffenen Test in seiner ursprünglichen Schritt-Datei
   (04–17) korrigieren — **nicht** den Test löschen oder die Assertion abschwächen, um
   Flakiness zu "verstecken". Danach die 5 Läufe wiederholen.
4. Erst wenn alle 5 Läufe grün sind: committen.

## 4. Nicht anfassen

- Kein Produktivcode.
- Keine Testdatei abschwächen (z. B. eine strikte `assertEquals` durch eine lockere
  Toleranzprüfung ersetzen), um Flakiness zu umgehen — die eigentliche Ursache beheben.

## 5. Akzeptanzkriterien

- `docs/hardening/baseline/stability-run.md` dokumentiert 5/5 grüne Läufe.
- `./tools/verify.sh` grün.

## 6. Gate

`./tools/verify.sh` plus die 5-fache Wiederholung aus Aufgabe 1. Bei anhaltender Flakiness
nach einem Korrekturversuch: stoppen, dem Nutzer den konkreten flakyen Test und die
vermutete Ursache melden, nicht endlos weiter korrigieren.

## 7. Commit-Message-Vorschlag

```
test: verify suite stability with 5 independent unit-test runs

Documents 5/5 green runs in docs/hardening/baseline/stability-run.md
before the suite is treated as a trustworthy baseline for later phases.
```
