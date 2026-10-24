# Manuelle Gesten-Checkliste

Diese Checkliste deckt alles ab, was auf der JVM nicht automatisiert testbar ist (siehe
`TEST-GAPS.md`): echtes Dual-Screen-Timing, das Switcher-Overlay, der verbundene
Service-Zustand. Sie wird vom **Nutzer** auf einem echten AYN Thor abgearbeitet — Sonnet
installiert die App und weist auf die Checkliste hin, führt sie aber nicht selbst aus
(kein Zugriff auf das physische Gerät für Touch-Gesten).

Auszufüllen nach: Schritt 15 (Erstaufnahme), Schritt 16 (nach der `BackGestureMachine`-
Extraktion — Pflicht, da Produktivcode geändert wurde), Schritt 18 (nach dem
Espresso-Smoke), und am Ende von Phase 4 (Schritt 36 — nach allen Performance-Fixes, um zu
beweisen, dass keine UX-Differenz entstanden ist).

Vorbereitung: `SwitcherService.DEBUG_GESTURES` bleibt `false` für den Normalbetrieb. Nur für
den Logcat-Trace in Schritt 15 temporär auf `true` setzen, danach **vor dem Commit** wieder
auf `false` zurücksetzen — das ist keine Verhaltensänderung, aber eine versehentlich
committete `true` wäre eine (lautes Logging bei jedem Back-Press in Produktion).

## Back-Taste

- [ ] Einzeltipp mit `Single tap = Normal Back`: entspricht System-Back
- [ ] Einzeltipp mit `Single tap = Open bottom screen launcher`: öffnet App-Drawer unten
- [ ] Doppeltipp: Switcher-Overlay öffnet sich auf dem unteren Screen
- [ ] Doppeltipp bei bereits offenem Overlay: Overlay schließt sich
- [ ] Halten (Standard 1000 ms): Apps von oberem und unterem Screen tauschen die Plätze
- [ ] Tippen, dann innerhalb des Tap-Hold-Fensters erneut halten: Top-Screen zeigt
      System-Recents statt Swap
- [ ] Mit deaktiviertem Doppeltipp: Einzeltipp feuert sofort, ohne Wartezeit
- [ ] Mit deaktiviertem Hold: Halten tut nichts, Back wird beim Loslassen normal verarbeitet

## Shoulder-Buttons (L1/L2/R1/R2, je mit Back gehalten)

- [ ] L1 Standard (Navigation-Flick Left): Quickstep-Flick nach links sichtbar
- [ ] R1 Standard (Navigation-Flick Right): Quickstep-Flick nach rechts sichtbar
- [ ] Ein Shoulder-Button auf „Launch app" gesetzt: öffnet die gewählte App auf dem
      gewählten Screen (Top und Bottom je einmal testen)
- [ ] Ein Shoulder-Button auf „Home": geht zum Launcher auf dem gewählten Screen
- [ ] Ein Shoulder-Button auf „Recents": öffnet System-Recents
- [ ] Ein Shoulder-Button auf „Take screenshot": Screenshot wird ausgelöst
- [ ] Je ein Shoulder-Button auf 3 der 11 Settings-Panels (z. B. Bluetooth, Display,
      Battery Saver): öffnet das jeweilige System-Panel, mit Fallback falls das OEM-Panel
      fehlt
- [ ] Unbelegter Shoulder-Button während eines Spiels: normaler Input kommt beim Spiel an,
      nichts wird geschluckt

## Switcher-Overlay

- [ ] Karten zeigen Icon, Label und Thumbnail korrekt für mehrere offene Apps
- [ ] Tap auf eine Karte: öffnet die App, Overlay schließt
- [ ] Swipe-up auf eine Karte: Karte schließt sich (Animation), App wird beendet
- [ ] Horizontaler Scroll über die Kartenreihe: scrollt, schließt keine Karte
- [ ] „Close all" ohne „Both screens": schließt nur Apps des unteren Screens
- [ ] „Close all" mit „Both screens": schließt Apps auf beiden Screens
- [ ] Keine offenen Apps: „No recent apps"-Text erscheint statt der Kartenreihe
- [ ] Eine andere App überdeckt den unteren Screen beim Öffnen einer Karte: Toast
      „covered_hint" erscheint

## Settings-Bildschirm

- [ ] Service läuft und ist verbunden: Warning-Row ist unsichtbar, Status-Zeile zeigt „on"
- [ ] Accessibility-Service in Android-Settings ausschalten: Warning-Row erscheint, Status
      wechselt auf „off"
- [ ] Service eingeschaltet aber abgestürzt/unresponsive (falls reproduzierbar): Status
      zeigt „unresponsive", „Repair service" ist sichtbar statt „Open accessibility settings"
- [ ] „Repair service" tippen → Dialog → „Open accessibility settings" führt zu den
      System-Settings
- [ ] „Reset settings" mit Bestätigung: alle Slider/Toggles/Shoulder-Zuweisungen auf
      Standard zurück, Toast „Settings reset" erscheint
- [ ] „Check for new version" bei bestehender Internetverbindung: zeigt entweder „Up to
      date" oder „New version available" mit funktionierendem „Open release"-Link
- [ ] „Check for new version" ohne Internetverbindung (Flugmodus): zeigt
      „Couldn't check for updates"
- [ ] GitHub-Repository-Link öffnet den Browser auf der richtigen Seite
- [ ] Dunkles und helles Theme (Systemeinstellung umschalten): alle Texte lesbar, keine
      abgeschnittenen Labels

## Lokalisierung

- [ ] Systemsprache auf Deutsch stellen, App neu öffnen: alle Sektionen, Dialoge und Toasts
      zeigen deutsche Texte, keine englischen Reste, keine abgeschnittenen Labels durch
      längere deutsche Strings

## Nach jeder Produktivcode-Änderung in diesem Plan (Schritte 16, 26, 27, 33, 34, 35)

- [ ] Mindestens die Back-Taste-Sektion oben erneut komplett durchlaufen
- [ ] Bei Schritt 16 zusätzlich: den in Schritt 15 aufgenommenen Golden-Logcat-Trace mit
      einem frischen Trace nach der Extraktion vergleichen (Diff bis auf Zeitstempel = leer)
