# Technische Fallstricke

Diese Datei sammelt die konkreten technischen Stolperfallen dieser Codebasis, die mehrere
Schritte betreffen. **Vor den Schritten 06, 07, 08, 11, 12, 13, 14, 17, 19 und 31 lesen** —
jeder dieser Punkte würde sonst zu einem roten Test führen, dessen Ursache nicht offensichtlich
ist, und zur falschen Reaktion verleiten (Produktivcode „reparieren", obwohl der Test die
falsche Annahme hat).

---

## P1 — `DoubleTapDetector` erkennt bei Uhrzeit 0 keinen Doppeltipp

`DoubleTapDetector.last` ist mit `0L` initialisiert und die Erkennung lautet:
```kotlin
if (last != 0L && now - last <= windowMs())
```
`0L` dient also als Sentinel für „es gab noch keinen Druck". Steht die Testuhr bei `0`, setzt
der erste `onPress()` `last = 0` — und der zweite Druck sieht `last != 0L` als `false` und
erkennt **keinen** Doppeltipp.

Auf einem echten Gerät ist das harmlos (`SystemClock.uptimeMillis()` ist nach dem Boot nie 0),
in einem Test mit kontrollierter Uhr aber eine Falle.

**Regel für jeden Test, der einen Doppeltipp auslöst:** die Uhr vor dem ersten Druck auf einen
Wert > 0 setzen (z. B. `FakeClock(now = 10_000L)` bzw. die Robolectric-Uhr erst einmal
vorrücken). **Das ist eine Testfixture-Anforderung, kein Bug im Produktivcode** — der Sentinel
wird nicht „korrigiert" (eiserne Regel 1).

---

## P2 — `SettingsActivity.onCreate` startet einen echten Netzwerk-Thread

`SettingsActivity.onCreate` ruft am Ende `UpdateChecker.checkInBackground(this)` auf. Das
startet einen rohen `Thread`, der tatsächlich `https://api.github.com/...` kontaktiert — auch
in jedem Robolectric-Test, der die Activity baut (Schritte 08, 11, 12, 13, 14, 18).

Konsequenzen, die **erwartet und akzeptiert** sind:
- Die Tests sind langsamer als nötig (bis zu 5 s Timeout pro Test bei fehlender Verbindung —
  `connectTimeout`/`readTimeout` sind auf 5000 ms gesetzt).
- Ohne Netzwerk schlägt der Call intern fehl; `checkInBackground` fängt das über
  `runCatching { ... }.getOrNull()` ab und der `callback` ist in `onCreate` `null`. **Die Tests
  gehen davon nicht rot.**
- In CI kann GitHubs Rate-Limit für nicht authentifizierte Anfragen (60/Stunde) greifen. Auch
  das lässt die Tests nicht rot werden, aus demselben Grund.

**Was zu tun ist:** nichts. Diesen Punkt kennen und **nicht** versuchen, `UpdateChecker`
testfreundlicher zu machen, um die Tests zu beschleunigen — das wäre eine
Produktivcode-Änderung außerhalb des Scopes (der zulässige Seam ist ausschließlich Schritt 26,
und der betrifft nur das JSON-Parsing, nicht den Thread).

**Wann es doch zu melden ist:** wenn ein Activity-Test *flaky* wird (manchmal grün, manchmal
rot) und der Stacktrace auf `UpdateChecker`/`HttpURLConnection`/`Thread` zeigt. Dann stoppen und
dem Nutzer melden — nicht selbst umbauen.

---

## P3 — `AccessibilityService`-Fenster sind unter Robolectric nicht verfügbar

`AccessibilityAppSource` ruft `service.windowsOnAllDisplays` auf. Diese Methode ist `final` und
greift intern auf eine Service-Verbindung zu, die unter Robolectric nicht existiert. Je
Robolectric-/API-Kombination kann sie:
- eine leere `SparseArray` liefern (bester Fall — `?: return` und `.orEmpty()` im
  Produktivcode greifen dann sauber), **oder**
- `null` liefern, **oder**
- eine `IllegalStateException` werfen.

**Regel:** jeder Test in Schritt 07, der `record()`, `topApp()`, `isCovered()` oder
`appsOnDisplay()` aufruft, muss damit rechnen. Schritt 07 beschreibt, welche Aufrufe als sicher
gelten und was zu tun ist, falls einer doch wirft: den betroffenen Testfall **auslassen** und in
`TEST-GAPS.md` ergänzen — **nicht** `AccessibilityAppSource` umbauen, um ihn testbar zu machen.

---

## P4 — JaCoCo braucht `enableUnitTestCoverage` im Debug-Buildtype

`app/build.gradle.kts` hat derzeit **nur** einen `release { }`-Block in `buildTypes`. Ohne
```kotlin
buildTypes {
    debug { enableUnitTestCoverage = true }
    release { /* unverändert */ }
}
```
erzeugt `testDebugUnitTest` **keine** `.exec`-Datei, und jeder JaCoCo-Report ist leer („No
data"), ohne dass der Build rot wird. Schritt 19 enthält diese Ergänzung — sie darf nicht
übersehen werden.

Außerdem: in `violationRules` zählt `limit { }` **ohne** expliziten `counter` die
**INSTRUCTION**-Coverage, nicht LINE. Alle Schwellen in diesem Plan sind als LINE-Coverage
gemeint, deshalb muss jede Regel `counter = "LINE"` und `value = "COVEREDRATIO"` setzen.

---

## P5 — Ein unsigniertes Release-APK lässt sich nicht installieren

`./gradlew assembleRelease` erzeugt lokal `app-release-unsigned.apk` (die Signing-Config greift
nur, wenn `RELEASE_KEYSTORE_PATH` gesetzt ist — das ist bewusst nur in CI der Fall, siehe
Kommentar in `app/build.gradle.kts`). **`adb install` lehnt eine unsignierte APK ab.**

Für die Performance-Messung in Schritt 31/36 heißt das: auf dem Gerät wird der **Debug**-Build
gemessen. Das ist für diese App vertretbar, weil `isMinifyEnabled = false` im Release-Buildtype
gesetzt ist — es gibt also keine Minifizierung/Shrinking, die Code-Form von Debug und Release ist
praktisch identisch. Die **APK-Größe** wird dagegen am unsignierten Release-Artefakt gemessen
(dafür muss nichts installiert werden).

---

## P6 — Robolectric-Qualifier und das `DayNight`-Theme

Die App nutzt `@android:style/Theme.DeviceDefault.DayNight`. Ob Robolectric beim Qualifier
`night` tatsächlich auf die dunkle Variante umschaltet, ist versionsabhängig.

**Prüfpflicht in Schritt 11:** wenn die Golden-Datei für `_dark` **byte-identisch** zur
`_light`-Datei ist, hat der `night`-Qualifier nicht gegriffen und der dunkle Snapshot ist
wertlos (er schützt nichts). In dem Fall: **stoppen und melden**, nicht den nutzlosen Snapshot
committen und als Erfolg verbuchen.

---

## P7 — Arbeitsverzeichnis von Gradle-Unit-Tests

Android-Unit-Tests laufen mit dem Arbeitsverzeichnis des **Moduls**, also `app/` — nicht des
Repo-Roots. Alle Dateipfade in Tests (`Golden.verify`, `ResourceIntegrityTest`) sind deshalb
relativ zu `app/` angegeben: `src/test/resources/golden/...`, `src/main/res/values/strings.xml`.

Falls ein Test die Datei nicht findet: `System.getProperty("user.dir")` in die Fehlermeldung
aufnehmen, den echten Pfad ablesen und die Pfadkonstante anpassen — **nicht** die Testlogik
umbauen.

---

## P8 — `lint` analysiert auch die Testquellen

`lintDebug` führt `lintAnalyzeDebugAndroidTest` und `lintAnalyzeDebugUnitTest` mit aus (im
Baseline-Build sichtbar). Nach Schritt 24 (`warningsAsErrors = true`) kann deshalb **neuer
Testcode** den Gate-Befehl rot machen, obwohl der Produktivcode unberührt ist.

Das ist gewollt (Testcode soll auch sauber sein). Aber: wenn der Gate nach dem Hinzufügen einer
Testdatei an einer Lint-Warnung *in dieser Testdatei* scheitert, ist die Lösung, die Warnung in
der Testdatei zu beheben — **nicht** `warningsAsErrors` wieder abzuschalten und nicht die
Lint-Baseline um den Testfund zu erweitern.
