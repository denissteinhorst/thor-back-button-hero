# Schritt 37 — Lizenz- & Dependency-Audit

## 1. Ziel

Mechanisch beweisen, dass trotz aller in Phase 1/4 hinzugefügten Test-/Debug-Dependencies
der Release-Klassenpfad ausschließlich Kotlin-Stdlib + Annotations enthält — die
Grundlage, auf der die 0BSD-Lizenzentscheidung dieses Plans beruht.

## 2. Voraussetzungen

Schritt 36 (bzw. die bewusst übersprungenen Phase-4-Schritte) ist committet und
`./tools/verify.sh` läuft grün.

## 3. Exakte Aufgaben

1. Vollständigen Release-Klassenpfad ausgeben:
   ```sh
   JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
     ./gradlew :app:dependencies --configuration releaseRuntimeClasspath --console=plain \
     > docs/hardening/baseline/release-runtime-classpath.txt
   ```
2. Datei öffnen und jede Zeile mit einem tatsächlichen Artefakt (nicht nur Baum-Grafik)
   prüfen. Erwartet sind ausschließlich Einträge aus diesen beiden Gruppen:
   - `org.jetbrains.kotlin:kotlin-stdlib*`
   - `org.jetbrains:annotations*`
   Zusätzlich darf `androidx.annotation:annotation*` auftauchen (von AGP selbst für
   `@NonNull`/`@Nullable`-Interop automatisch eingebunden, kein von uns hinzugefügtes
   Test-Artefakt).
3. Explizit nach jedem in diesem Plan hinzugefügten Test-/Debug-Artefakt suchen — **keines**
   darf auftauchen:
   ```sh
   grep -iE "junit|robolectric|espresso|uiautomator|leakcanary|roborazzi|jacoco" \
     docs/hardening/baseline/release-runtime-classpath.txt
   ```
   Dieser Befehl muss **keine Ausgabe** liefern. Liefert er eine: **stoppen und melden** —
   das wäre ein Bruch der in diesem Plan getroffenen Lizenz-/Scope-Entscheidung, keine
   Kleinigkeit, die man einfach weiterlaufen lässt.
4. Ergebnis in `docs/hardening/LICENSE-AUDIT.md` dokumentieren: der Befehl aus Aufgabe 3,
   seine leere Ausgabe, und eine kurze Tabelle aller tatsächlich im Release-Klassenpfad
   vorhandenen Artefakte mit ihrer Lizenz (Kotlin-Stdlib: Apache-2.0,
   `org.jetbrains:annotations`: Apache-2.0, `androidx.annotation`: Apache-2.0 — alle drei
   bedingungsfrei genug, um neben 0BSD zu bestehen, siehe Begründung in
   `docs/hardening/00-OVERVIEW.md`).
5. Zusätzlich den kompletten Dependency-Baum für `debugRuntimeClasspath` separat ausgeben
   (zur Information, nicht zur Behauptung der Freiheit von Test-Code — Debug-Builds
   **dürfen** LeakCanary enthalten):
   ```sh
   JAVA_HOME=... ./gradlew :app:dependencies --configuration debugRuntimeClasspath --console=plain \
     > docs/hardening/baseline/debug-runtime-classpath.txt
   ```

## 4. Nicht anfassen

- Keine Dependency-Deklaration in `app/build.gradle.kts` ändern — dieser Schritt prüft nur,
  er korrigiert nicht. Fände er doch ein Leck, wäre die Korrektur ein eigener, vom Nutzer zu
  bestätigender Schritt, kein automatischer Nebeneffekt dieses Audits.

## 5. Akzeptanzkriterien

- `docs/hardening/baseline/release-runtime-classpath.txt` existiert.
- Der `grep`-Befehl aus Aufgabe 3 liefert keine Treffer.
- `docs/hardening/LICENSE-AUDIT.md` dokumentiert das Ergebnis mit Lizenztabelle.
- `./tools/verify.sh` grün.

## 6. Gate

`./tools/verify.sh` plus der `grep`-Beweis aus Aufgabe 3. Bei einem Treffer: stoppen, exakt
melden, welches Artefakt und über welchen Abhängigkeitspfad es in den Release-Klassenpfad
gelangt ist — keine eigenständige Korrektur versuchen.

## 7. Commit-Message-Vorschlag

```
docs: mechanically prove the release classpath stays dependency-free

releaseRuntimeClasspath contains only Kotlin stdlib + annotations -
no test/debug-only artifact leaked in, confirmed via grep against
every dependency added in this hardening plan.
```
