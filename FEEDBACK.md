# Community-Feedback

Stand: 6. November 2026; Abgleich mit dem aktuellen Repository-Stand (v1.4.1).
Alle drei Threads direkt im Browser gelesen, einschließlich der ausklappbaren Antworten.
Doppelte Meldungen zusammengeführt; reines Lob und allgemeine Diskussionen ausgelassen.
„Umgesetzt“ bezeichnet den Code-Stand; eine Bestätigung des Melders ist ausdrücklich vermerkt.

Quellen: [Initialer Release][initial] · [v1.3.0][v130] · [v1.4.0][v140]

## Offen / teilweise umgesetzt

- Bug / Feature: Launcher beim Screen-Swap auf ihrem Display lassen. Trotz Ausnahmen wandert Jarngreipr nach Spielstart mit nach oben (Mjolnir, Beacon oben, Jarngreipr unten). **Fix unveröffentlicht:** Hold-Ausnahmen schützen jetzt beide Swap-Partner unabhängig vom Controller-Fokus; nur die andere App bewegt sich. Auf dem Thor mit gewöhnlichen App-Tasks geprüft; Originalsetup und Owner-Abnahme noch offen. Bewusste Recents-Auswahl bleibt erlaubt. — **u/zerocean**, [v1.3.0][v130], [v1.4.0][v140].
- Bug: WatermelonDS/Azahar verlieren beim Spielstart den unteren Spielbildschirm an den Launcher; Swap wird anschließend als nicht möglich gemeldet. Ursache und genauer Launcher noch ungeklärt. — **u/cellobear**, [v1.4.0][v140].
- Bug: App funktioniert trotz erteilter Accessibility-Berechtigung auf dem Thor nicht; konkrete Schritte, Version und Ursache fehlen. — **u/rtmish**, [initial][initial].
- Bug / Kompatibilität: iiSU holt seinen unteren Bildschirm sofort zurück und verhindert den gewünschten Wechsel. Weiterhin nicht unterstützt. — **u/kekLawnn**, [v1.3.0][v130].
- Bug / bewusste Einschränkung: Nach Beenden eines geswappten Spiels kehren ES-DE/Companion nicht automatisch zurück. In 1.4.0 implementiert, in 1.4.1 bewusst entfernt; derzeit manuell zurückswappen. — **u/moosedude451**, [v1.3.0][v130]; aktueller Stand: [Architektur](docs/ARCHITECTURE.md).
- Feature: Alle Aktionen frei auf alle Gesten/Hotkeys legen können, etwa „beide Apps schließen“ auf Tap + Hold. — **u/Aggravating-Taro5133**, [v1.4.0][v140].
- Feature: Back + Stick oben/unten/links/rechts als zusätzliche Hotkeys, etwa für Helligkeit. — **u/Aggravating-Taro5133**, [v1.4.0][v140].
- Feature: Zwischen Dual-Screen, nur oberem und nur unterem Display umschalten. — **u/Coxinigo**, [v1.4.0][v140].
- Feature: Eine einzelne App auf das andere Display verschieben, ohne beide Apps zu tauschen. Über die App-Auswahl in Recents bereits möglich; eine eigene direkte Aktion fehlt. — **u/TophersTalk**, [initial][initial].

## Rückfragen / noch zu bestätigen

- Kompatibilität: Funktioniert die App auch auf dem RP Duo? Test zugesagt, Ergebnis im Thread noch offen. — **u/Paol3tto**, [v1.4.0][v140].
- Anwendungsfall: Guide unten öffnen, während WatermelonDS/Azahar oben weiterspielt. Vom Autor bejaht; keine konkrete Emulator-Testbestätigung im Thread, außerdem separater Fehlerbericht von u/cellobear oben. — **u/Manager_National**, [v1.3.0][v130].

## Bereits umgesetzt / behoben / beantwortet

- Bug: Recents setzt Auto-lock auf „unten fest“; RetroArch-Swap verändert ebenfalls den Controller-Fokus. In **1.4.1** behoben; **vom Melder bestätigt**. — **u/moosedude451**, [v1.4.0][v140].
- Bug: Cocoon übernimmt unten wieder den Bildschirm. Frontend-Parking hält den Dual-Screen-Betrieb aufrecht (**1.4.0**). — **u/snipe4hire**, [v1.3.0][v130].
- Kompatibilität: NeoStations Bottom-Panel blockiert andere Apps. Panel wird ausgeblendet und über seine Recents-Karte zurückgeholt (**1.4.0**). — **u/Alternative-Ease-702** (Rückfrage), **u/New_Association_1017** (ursprüngliche Beobachtung), [v1.3.0][v130].
- Verbesserung: Spiele wechseln nicht flüssig zwischen Displays. Bestehende Tasks werden seit **1.4.0** verschoben; emulatorbedingte Größenwechsel bleiben möglich. Keine nachträgliche Bestätigung des Melders. — **u/zerocean**, [v1.3.0][v130].
- Feature: Bottom-Recents optional mit Apps beider Displays (**1.4.0**). — **u/moosedude451**, [v1.3.0][v130].
- Feature: Recents mit Controller bedienen (**1.4.0**). — **u/the_chief_dior**, [v1.3.0][v130].
- Feature: Tasks per Taste ohne Touch schließen; Controller-Recents und Active App Control vorhanden (**1.4.0**). — **u/YoungProNooB**, [v1.3.0][v130].
- Feature: Back-Gesten und optionale Hotkeys verständlich gruppieren, Sammel-Unbind besser auffindbar machen (**1.4.0**). — **u/moosedude451**, [v1.3.0][v130].
- Feature: Leere optionale Belegungen oder empfohlenes Preset. „Clear bindings“ und „Load defaults“ mit Vorschau vorhanden (**1.4.0**); ab Werk bleiben Hotkeys belegt. — **u/moosedude451**, [v1.3.0][v130].
- Feature: Controller-Fokus oben/unten per Hotkey umschalten (**1.3.0**); auch festes Display wählbar. — **u/therico**, [initial][initial].
- Feature / Rückfrage: Untere App per Hotkey minimieren. Active App Control vorhanden (**1.4.0**). — **u/kypris**, [v1.4.0][v140].
- Konfiguration: Nur Lautstärke/Helligkeit behalten und alles andere deaktivieren. Bereits möglich. — **u/Dragon_Small_Z**, [v1.4.0][v140]; ähnlicher Wunsch nach reduziertem Umfang von **u/Erik_REF**, [v1.3.0][v130].
- Rückfrage: Screenshot per Tastenkombination. Bereits vorhanden; Funktion von **u/NoVariation3249** bestätigt. — **u/GNaRLZFUNk2ThaLORE**, [initial][initial].
- Dokumentation: Einrichtung erklären. Landingpage/Installationsanleitung vorhanden und verlinkt; der separate Funktionsfehler bleibt oben offen. — **u/rtmish**, [initial][initial].
- Kommunikation: AI-Unterstützung im Release-Post klar offenlegen. Ergänzt und im 1.4.0-Post direkt genannt. — **u/Chimera_Gaming**, [v1.3.0][v130], [v1.4.0][v140].
- Produktwunsch (dauerhaft): Schlank bleiben und Feature-Bloat vermeiden; als Projektrichtung beibehalten. — **u/gstreet88**, [initial][initial].

[initial]: https://www.reddit.com/r/AynThor/comments/1xf70dc/thor_back_button_hero/
[v130]: https://www.reddit.com/r/AynThor/comments/1xfj5ew/back_button_hero_130_one_button_many_possibilities/
[v140]: https://www.reddit.com/r/AynThor/comments/1xjw6mi/thor_back_button_hero_v140_out_now/
