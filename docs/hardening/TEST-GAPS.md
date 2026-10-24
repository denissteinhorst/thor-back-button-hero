# Bekannte Test-Lücken

Diese Liste existiert, damit kein Schritt versucht, etwas automatisiert zu testen, das mit
den in diesem Plan erlaubten Mitteln (Robolectric-JVM-Tests, ein optionaler Espresso-Smoke)
nicht erreichbar ist. Wenn ein Schritt auf eine dieser Lücken stößt: nicht improvisieren,
sondern auf den hier beschriebenen Ersatzmechanismus verweisen (Snapshot, Report, manuelle
Checkliste) und weitermachen.

## 1. `SwitcherService.isConnected` — Zustand `ServiceStatus.ON` nicht erreichbar

`SwitcherService.isConnected` hat `private set` im `companion object` und wird nur in
`onServiceConnected()`/`onDestroy()`/`onUnbind()` gesetzt — Methoden, die der
Accessibility-Service-Lebenszyklus aufruft, nicht ein Unit-Test. Aus einem Robolectric-Test
heraus ist `isConnected` immer `false`.

**Konsequenz:** `currentServiceStatus()` in `SettingsUiKit.kt` kann aus einem Unit-Test nur
die Zustände `OFF` und `UNRESPONSIVE` erreichen, nie `ON`.

**Ersatz:** `serviceStatusText(status: ServiceStatus)` nimmt das Enum direkt als Parameter
und ist damit für **alle drei** Zustände unit-testbar (Schritt 08) — getestet wird die
Text-Zuordnung, nicht der Zustandsübergang. Der volle `refresh()`-Zyklus mit echtem
„Service läuft und ist verbunden" gehört in `MANUAL-GESTURE-CHECKLIST.md`.

## 2. `AccessibilityService`-Methoden sind `final`

`performGlobalAction(int)` und `getWindowsOnAllDisplays()` sind `final` in der
Android-Framework-Klasse `AccessibilityService`. Sie können in keinem Fake/Mock/Shadow
überschrieben werden, ohne den gesamten `AccessibilityService` zu shadowen (siehe Punkt 3).

**Konsequenz:** Jeder Codepfad, der direkt `performGlobalAction` aufruft (Shoulder-Aktionen
`SHOULDER_RECENTS`, `SHOULDER_SCREENSHOT`, `SHOULDER_QUICK_SETTINGS`,
`SHOULDER_NOTIFICATIONS`, `openTopRecents()`, `onSingleTap()`'s `SINGLE_BACK`-Zweig) wird
**nicht** durch die `BackGestureMachine`-Unit-Tests aus Schritt 17 abgedeckt — die Extraktion
in Schritt 16 trennt genau an dieser Stelle: die Entscheidungslogik (welcher Zweig feuert)
wandert in `BackGestureMachine` und ist testbar, der eigentliche `performGlobalAction`-Aufruf
bleibt in `SwitcherService` über das `Actions`-Interface und ist es nicht.

**Ersatz:** Golden-Logcat-Trace (Schritt 15) + `MANUAL-GESTURE-CHECKLIST.md`.

## 3. `SwitcherOverlay` ist über Robolectric nicht zuverlässig testbar

`SwitcherOverlay.show()` verwendet `service.createDisplayContext(display)
.createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)` und fügt
das Ergebnis über einen echten `WindowManager` ein. Robolectrics Unterstützung für
Multi-Display-`WindowContext`s dieser Art ist lückenhaft und je Robolectric-Version instabil.

**Konsequenz:** Die komplette Overlay-UI (Karten-Layout, Swipe-Geste, „Close all",
Leerzustand) wird **nicht** per View-Tree-Snapshot geschützt.

**Ersatz:**
- Die reine Gestenmathematik in `CardTouch` wird in Schritt 27 in eine eigene Datei
  extrahiert und dort pur unit-getestet (keine View-Abhängigkeit nötig, nur
  `MotionEvent`-Koordinaten und der `threshold`-Wert).
- Die visuelle Erscheinung des Overlays wird ausschließlich über
  `MANUAL-GESTURE-CHECKLIST.md` auf dem echten Gerät geprüft.

## 4. `UpdateChecker.fetchLatestRelease()` ist nicht injizierbar

Die Methode ist `private`, baut die Request-URL aus privaten `const val`s zusammen und öffnet
direkt eine `HttpURLConnection` — kein Interface, kein injizierter Client.

**Konsequenz:** Vor Schritt 26 ist der komplette Netzwerk-Pfad nicht unit-testbar.

**Ersatz:** Schritt 26 extrahiert ausschließlich die reine Parse-Funktion
(`parseRelease(json: String): Release?`) als `internal` Seam — der Netzwerk-Aufruf selbst
bleibt ungetestet und bewusst so (manuelles „Check for updates" in der
`MANUAL-GESTURE-CHECKLIST.md` deckt den Ende-zu-Ende-Pfad ab).

## 5. Lokalisierung: `values-de/strings.xml` hat 87, `values/strings.xml` hat 88 Einträge

Das ist **kein Bug** — `app_name` ist in `values/strings.xml` als `translatable="false"`
markiert und hat deshalb bewusst kein DE-Pendant. Schritt 09 (`ResourceIntegrityTest`) prüft
das explizit mit, damit kein späterer Schritt das versehentlich als Lücke "korrigiert".
