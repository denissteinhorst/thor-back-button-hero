# Reddit-Feedback zu Back Button Hero 1.3.0

Quelle: [Release-Post in r/AynThor](https://www.reddit.com/r/AynThor/comments/1xfj5ew/back_button_hero_130_one_button_many_possibilities/)  
Stand der Auswertung: 27. Oktober 2026 (RSS-Stand: 14:45 Uhr, Europe/Berlin)  
Umfang: kompletter zu diesem Zeitpunkt per Reddit-RSS sichtbarer Thread mit 28 Kommentaren und allen verschachtelten Antworten; der Startbeitrag selbst wurde nicht als Kommentar gezählt.

## Kurzfazit

Das Gesamtbild ist klar positiv: Mehrere Personen beschreiben die App als schneller, verlässlicher und leichter verständlich als Wayfinder. Besonders viel Mehrwert liefern der Bottom-Screen-App-Switcher, zuverlässiges Screen-Swapping, der schnelle Zugriff auf den App-Drawer und die Hotkeys für Lautstärke und andere Aktionen. Es gibt keine Berichte über Abstürze, Datenverlust, Tracking- oder Datenschutzprobleme.

Der wichtigste Handlungsbereich ist die Kompatibilität mit Drittanbieter-Launchern und Frontends. Danach folgen Controller-Bedienung des eigenen Switchers, eine optional größere App-Auswahl im Switcher und eine verständlichere Gruppierung der Einstellungen. Die mehrfach gelobte schlanke Ausrichtung sollte dabei erhalten bleiben.

## Wo steht die App gerade? Einfach eingeordnet

| Bereich | Stand | Einfache Einordnung |
|---|---|---|
| Kernidee und Nutzen | 🟢 Stark | Die Leute verstehen sofort, wofür die App da ist, und mehrere wollten genau diese Funktionen schon vorher. |
| Screen-Swap und App-Drawer | 🟢 bis 🟡 Gut mit Ausnahmen | Im normalen Setup funktioniert beides überzeugend; spezielle Launcher-/Frontend-Setups verursachen jedoch relevante Probleme. |
| Bottom-Screen-App-Switcher | 🟢 Stark | Das ist das am deutlichsten gelobte Einzel-Feature und löst einen echten Thor-Alltagsfrust. |
| Einfachheit statt Feature-Bloat | 🟢 Sehr stark | Die schlanke Ausrichtung ist ein zentraler Wechselgrund gegenüber Wayfinder und sollte nicht verwässert werden. |
| Frontend-/Launcher-Kompatibilität | 🔴 Größte Schwäche | Cocoon, NeoStation, iiSU und Zwei-Launcher-Setups sind der wichtigste offene Problemkomplex. |
| Controller-only-Bedienung | 🟡 Ausbaufähig | Die Hotkeys sind nützlich, der Switcher selbst zwingt Nutzer aber noch zum Touchscreen. |
| Einstellungen und Defaults | 🟡 Funktional, aber nicht optimal erklärt | Alles Nötige existiert bereits, doch optionale Hotkeys und „Unbind optionals“ sind nicht dort erklärt oder platziert, wo Nutzer sie zuerst suchen. |
| Vertrauen und Transparenz | 🟢 im Projekt, 🟡 in der Außenwirkung | Repository, Tests und Datenschutz überzeugen; AI-Unterstützung muss in Community-Posts sofort sichtbar sein, weil das Thema polarisiert. |

Kurz gesagt: **Die App wirkt nicht wie ein unfertiges Experiment, sondern wie ein bereits nützliches 1.3.0-Produkt mit einer klaren technischen Integrationsbaustelle.** Der Kern kommt sehr gut an. Vor neuen großen Funktionen ist vor allem sicherzustellen, dass der Kern auch mit den verbreiteten Thor-Frontends zuverlässig bleibt.

## Was den Leuten wie wichtig ist

Diese Reihenfolge ist eine qualitative Einschätzung aus dem vollständigen Thread, keine repräsentative Nutzerumfrage:

1. **Zuverlässiges Screen-Swapping und schneller App-Drawer — sehr wichtig.** Mehrere Kommentare nennen genau diese zwei Funktionen als Grund für Installation oder Wechsel von Wayfinder; Probleme hier treffen das Hauptversprechen der App.
2. **Bottom-Screen-App-Switcher — sehr wichtig.** Er wird mehrfach spontan gelobt und als Lösung für das schwierige Schließen bzw. Wechseln von Apps auf dem Thor beschrieben.
3. **Einfach, schnell und ohne Bloat bleiben — sehr wichtig.** „Just works“, leichter als Wayfinder und keine überladene Funktionssammlung sind wiederkehrende positive Aussagen; jede Erweiterung sollte optional und leicht verständlich bleiben.
4. **Kompatibilität mit Cocoon, NeoStation, iiSU, ES-DE und getrennten Launchern — wichtig und dringend.** Es gibt zwar weniger Berichte als allgemeines Lob, aber dies sind die einzigen klaren Funktionsprobleme und sie können Kernfunktionen vollständig unbrauchbar machen.
5. **Hotkeys für alltägliche Aktionen — wichtig.** Besonders Lautstärke, Screenshot und Controller-Fokus sparen zusätzliche Tools wie KeyMapper; die Funktion wird geschätzt, soll aber eindeutig optional bleiben.
6. **Komplettere und controllerbedienbare App-Auswahl — wünschenswert.** Zwei konkrete Wünsche betreffen alle Apps im Bottom-Switcher und Bedienung ohne Touch; beides verbessert den Komfort, blockiert die Grundnutzung aber nicht.
7. **Bessere Struktur der Einstellungen — sinnvoll, aber nicht dringend.** Header, Position von „Unbind optionals“ und ein empfohlenes Preset reduzieren Verwirrung, beheben jedoch keinen Laufzeitfehler.
8. **AI-Offenlegung und nachvollziehbare Qualität — wichtig für Vertrauen, nicht für die Funktion.** Einige reagieren skeptisch oder ablehnend, andere verteidigen die App gerade wegen Tests und Open Source. Klare Offenlegung verhindert, dass diese Debatte das eigentliche Produktfeedback überlagert.

## Gutes Feedback, leicht verständlich

- **„Die App löst ein echtes Problem.“** Nutzer möchten Apps unten wechseln oder schließen und die zwei Screens schnell tauschen; genau das liefert Back Button Hero.
- **„Sie ist leichter als Wayfinder.“** Die geringere Komplexität wird nicht als fehlender Umfang, sondern als Vorteil wahrgenommen.
- **„Der Kern funktioniert.“** Mehrere Nutzer berichten, dass Screen-Swap und App-Drawer einfach funktionieren; ES-DE + Companion lässt sich erfolgreich tauschen.
- **„Die Extras sind praktisch.“** Lautstärke und andere Aktionen per Back-Kombination vermeiden zusätzliche Mapping-Software.
- **„Das Projekt wirkt sorgfältig.“** Tests, Open Source, Datenschutz und die Bereitschaft, Fragen zu beantworten, schaffen Vertrauen.

## Schlechtes Feedback, leicht verständlich

- **„Mit manchen Launchern sieht es aus, als würde der Swap nicht funktionieren.“** Fest eingeblendete Bottom-Panels oder mitgetauschte Launcher machen Cocoon-/NeoStation-/iiSU- und Zwei-Launcher-Setups problematisch.
- **„Spiele wechseln nicht immer nahtlos.“** Ein Displaywechsel kann wegen Auflösungs-/Größenänderung ruckeln und der aktuelle Relaunch-Ansatz kann App-Zustand verlieren.
- **„Der eigene Switcher kennt nicht automatisch jede App.“** Er zeigt nur Apps, die der Service auf dem unteren Screen bereits gesehen hat; das ist technisch erklärbar, für Nutzer aber überraschend.
- **„Für den Switcher braucht man noch Touch.“** Das unterbricht den Controller-Workflow.
- **„Optionale Einstellungen wirken weniger optional als gedacht.“** Acht vorbelegte Hotkeys und der weit unten platzierte Sammel-Reset können neue Nutzer überfordern.
- **„AI ist ein Vertrauensrisiko für einen Teil der Community.“** Das ist kein nachgewiesener Qualitätsfehler der App, aber fehlende Offenlegung im ersten Post erzeugte unnötige Nebendiskussionen.

## Bestätigte Mehrwerte

- **Bottom-Screen-App-Switcher:** Wird als „game changing“ bezeichnet und löst insbesondere das umständliche vollständige Schließen von Apps auf dem Thor.
- **Screen-Swap:** Funktioniert laut Feedback mit ES-DE + Companion und generell besser als bei Wayfinder; genau diese Kernfunktion motiviert mehrere Personen zum Wechsel.
- **Einfachheit:** „Does what it says“ und „without feature bloat“ bestätigen die Positionierung als fokussierte Alternative zu Wayfinder.
- **Zuverlässiger Kernablauf:** App-Drawer öffnen und Apps zwischen den Screens tauschen werden ausdrücklich als funktionierend gelobt.
- **Hotkeys:** Die einfache Lautstärkesteuerung ersetzt für mindestens eine Person KeyMapper; Screenshot- und Controller-Fokus-Aktionen erweitern denselben Nutzen.
- **Vertrauen:** Open Source, Tests, Quality Gates, fehlendes Tracking und die sorgfältige Kommunikation werden positiv wahrgenommen und helfen gegen Vorbehalte gegenüber AI-assistierter Entwicklung.

## Bug — Drittanbieter-Frontends blockieren oder überdecken den unteren Screen (Priorität: hoch)

**Was tun und warum:** Cocoon, NeoStation und iiSU gezielt testen und die Swap-/Launch-Strategie so anpassen oder klar begrenzen, dass deren festes Bottom-Panel nicht fälschlich wie ein erfolgreicher Wechsel wirkt.

- **Mehrwert einer Lösung:** Öffnet die App für verbreitete Thor-Setups und beseitigt den stärksten konkreten „funktioniert bei mir nicht“-Eindruck im Thread.
- **Defizit laut Feedback:** Bei Cocoon sprang der untere Screen wieder auf dessen Home-Screen; für NeoStation wurde ein nicht überschreibbares Panel bestätigt und für iiSU derselbe Effekt vermutet.
- **Auswirkung auf den Ist-Stand:** v1.3.0 erkennt vollflächig überlagernde Fenster nur vor dem Start einer App aus dem eigenen Switcher und zeigt dann einen Hinweis. `swapScreens()` schützt diesen Ablauf nicht und verschiebt keine bestehenden Tasks, sondern startet die jeweiligen Paket-Launch-Intents auf dem anderen Display neu. Die genannte ADB-Alternative `cmd activity display move-stack` sollte auf Firmware-Kompatibilität und Nebenwirkungen untersucht, nicht ungeprüft übernommen werden.
- **Stand 2026-10-30 (unveröffentlicht seit v1.3.0, für Changelog/Reddit):** Es gibt zwei Bauarten von Bottom-Screens, beide werden ohne Paketliste behandelt.
  - **Overlay-Fenster — ES-DE Companion ✅, NeoStation ✅** (Commit „Handle bottom-screen panels and excluded companion tasks“): `BottomPanels`/`PanelWindowHost` erfassen das Fenster vor jedem Start unten, verstecken es per Root und zeigen es als eigene Karte in den Bottom-Recents; Anwählen stellt es unverändert wieder her. Hold-to-swap lehnt mit Hinweis ab, solange ein Panel-Besitzer beteiligt ist.
  - **Normale Activity — Cocoon ✅** (`ExternalDisplayActivity`): Verdecken würde sie stoppen, Cocoon schaltet dann oben auf seinen Ein-Bildschirm-Modus. Stattdessen parkt `FrontendParking` den Task vor dem Start unten auf einem privaten, unsichtbaren Display dieser App, wo er weiterläuft; seine Karte holt ihn ohne Neustart zurück. Startet Cocoon seinen unteren Screen nach Standby oder nach Rückkehr aus einem Spiel selbst neu (nur wenn er nicht auf dem echten unteren Display liegt), wird er erneut geparkt. Stirbt der Prozess, verwirft Android das Park-Display samt Inhalt; Cocoon baut seinen unteren Screen beim nächsten Start selbst neu auf. Vom Owner auf dem Gerät bestätigt: Drawer, App-Start, Vorschaubild, Zurückholen. Noch nicht gezielt beobachtet: erneutes Parken nach Standby bzw. nach Spielende.
  - **Normale Activity — iiSU ❌** (`SecondaryHomeActivity`): startet ~130 ms nach jedem Vordergrundwechsel auf dem unteren Display eine neue Instanz (`NEW_TASK|MULTIPLE_TASK|EXCLUDE_FROM_RECENTS`) und holt seine obere Activity nach vorne. Parken hilft nicht, weil iiSU nicht auf das Stoppen, sondern auf den Vordergrundwechsel reagiert (Machbarkeitstest). Die Schleifensperre (`ParkingPolicy.REPARK_MIN_INTERVAL_MS`) erkennt das und lässt iiSU für die laufende Dienst-Sitzung in Ruhe. Einziger bekannter Hebel bleibt ein systemweites Start-Veto (`IActivityController`), wegen Risiko vorerst zurückgestellt. Nächstes Thema nach bestätigter Cocoon-Lösung.
  - Nebenbei behoben: Eine App mit Tasks auf beiden Displays zeigte in den Bottom-Recents das Vorschaubild und Display-Symbol ihres neuesten (oft oberen) Tasks; jetzt gilt der Task auf dem Display des Switchers.
- **Quelle:** [Cocoon-Bericht](https://www.reddit.com/r/AynThor/comments/1xfj5ew/back_button_hero_130_one_button_many_possibilities/19rnqgbcivwtf/), [iiSU-Testwunsch und ADB-Ansatz](https://www.reddit.com/r/AynThor/comments/1xfj5ew/back_button_hero_130_one_button_many_possibilities/19rnrj9w0oq1s/)

## Feature Request — Apps vom Screen-Swap ausschließen (Priorität: hoch)

**Was tun und warum:** Eine konfigurierbare Ausschlussliste für Launcher/Frontends ergänzen, damit getrennte Launcher auf Top- und Bottom-Screen beim Swap auf ihrem jeweiligen Display bleiben.

- **Mehrwert einer Lösung:** Macht Screen-Swapping in Zwei-Launcher-Setups vorhersagbar und kann zugleich einen Teil der Cocoon-/NeoStation-/iiSU-Probleme entschärfen.
- **Defizit laut Feedback:** Zwei unterschiedliche Launcher werden aktuell mitgetauscht, obwohl nur die eigentlichen Nutz-Apps die Displays wechseln sollen.
- **Auswirkung auf den Ist-Stand:** `AccessibilityAppSource` besitzt nur eine fest codierte Ignore-Liste für SystemUI, AYN-Komponenten, Odin Launcher und Launcher3. Drittanbieter-Launcher sind weder automatisch erkannt noch vom Nutzer ausschließbar. Die Einstellung müsste sowohl die Ermittlung der Vordergrund-Apps als auch den Swap berücksichtigen.
- **Quelle:** [Launcher-Ausschluss](https://www.reddit.com/r/AynThor/comments/1xfj5ew/back_button_hero_130_one_button_many_possibilities/19ro35re7e6zk/)

## Bug — Spiel-Swap ist nicht immer flüssig und kann Zustand verlieren (Priorität: mittel bis hoch)

**Was tun und warum:** Prüfen, ob laufende Tasks statt ihrer Haupt-Activity verschoben werden können, um Resize-/Resolution-Ruckler und verlorenen Laufzeitzustand beim Displaywechsel zu reduzieren.

- **Mehrwert einer Lösung:** Ein nahtloserer Wechsel stärkt die wichtigste Kernfunktion und grenzt die App weiter positiv von Wayfinder ab.
- **Defizit laut Feedback:** Das Umschalten funktioniert bei Spielen, aber nicht immer flüssig; Größen- und Auflösungswechsel werden als wahrscheinliche Ursache genannt.
- **Auswirkung auf den Ist-Stand:** v1.3.0 ruft für beide Pakete jeweils `getLaunchIntentForPackage()` auf dem anderen Display auf. Das ist einfach und robust, verschiebt aber nicht zwingend den bestehenden Task und kann eine Activity neu aufbauen. Ein Task-Move wäre technisch tiefer, privilegiert und firmwareabhängig und braucht deshalb reale Tests mit Spielen und Emulatoren.
- **Quelle:** [Hinweis zur Swap-Flüssigkeit](https://www.reddit.com/r/AynThor/comments/1xfj5ew/back_button_hero_130_one_button_many_possibilities/19ro35re7e6zk/)

## Bug — ES-DE kehrt nach Schließen eines geswappten Spiels nicht auf demselben Screen zurück (Priorität: mittel)

**Was tun und warum:** Den ES-DE-/Companion-Ablauf reproduzieren und entweder den ursprünglichen Task-/Display-Bezug erhalten oder den bekannten Workaround in der FAQ dokumentieren.

- **Mehrwert einer Lösung:** Erhält den konsolenartigen ES-DE-Workflow auch nach Swap und Spielende ohne zusätzlichen manuellen Flip/Home-Schritt.
- **Defizit laut Feedback:** Nach dem Schließen eines zuvor geswappten Spiels erscheint Home statt ES-DE auf dem Zielscreen; vor dem Spielstart oder vor dem Schließen zurückzutauschen umgeht das Problem.
- **Auswirkung auf den Ist-Stand:** Der aktuelle paketbasierte Relaunch kennt die Beziehung zwischen ES-DE, Companion und dem von ES-DE gestarteten Emulator nicht. Eine generische automatische Wiederherstellung ist daher voraussichtlich schwierig; kurzfristig ist dies eher eine dokumentierte Integrationsgrenze, langfristig Teil einer taskbasierten Swap-Lösung.
- **Stand 2026-10-30 (unveröffentlicht, für Changelog/Reddit):** Gelöst, allgemein ohne Paketliste. Reproduziert mit ES-DE oben, Companion unten, Super Mario (SNES, RetroArch): Hold-to-swap verschiebt heute schon die Tasks (Spiel unten, Companion oben über ES-DE); nach Spielende zeigte unten Home, oben verdeckte Companion weiter ES-DE. Jetzt merkt sich `SwapReturn` das getauschte Paar. Wird eine der beiden Apps geschlossen und ihr Bildschirm fällt auf Home zurück, wandert die andere automatisch auf ihren ursprünglichen Bildschirm zurück — oben liegt wieder ES-DE, unten Companion. Wird eine geswappte App nur verlassen (Home), bleibt alles, wie es ist; auch ein späteres Schließen über die Bottom-Recents löst die Rückkehr aus. Auf dem Gerät per emulierter Eingabe geprüft; Owner-Bestätigung steht aus.
- **Stand 2026-10-31 (Owner-Entscheidung):** `SwapReturn` ist wieder ausgebaut (Hotfix 1.4.1). Die App verschiebt Fenster grundsätzlich nicht automatisch zwischen den Bildschirmen, siehe `docs/ARCHITECTURE.md` (Hold-to-swap). Nach Spielende bleibt Companion oben über ES-DE liegen; der Nutzer swappt selbst zurück.
- **Quelle:** [ES-DE-/Companion-Ablauf](https://www.reddit.com/r/AynThor/comments/1xfj5ew/back_button_hero_130_one_button_many_possibilities/19ro34lzbhxb4/)

## Bug — Bottom Recents liegt hinter dem AYN-Panel (intern gefunden, 2026-10-31)

- **Ursache:** AYNs Assistentenpanel (`com.odin.dualscreen.assistant`, Typ `BOOT_PROGRESS`) liegt über jedem Accessibility-Overlay; System-Apps waren von der Panel-Behandlung ausgenommen.
- **Stand 2026-10-31 (Owner-Entscheidung, auf dem Gerät bestätigt):** Das AYN-Panel ist die einzige System-Ausnahme und verhält sich wie ein normales Fenster unten: Recents versteckt es beim Öffnen und gibt es ohne Auswahl zurück; ein App-Start parkt es hinter seiner Karte. Der AYN-Button holt ein geparktes Panel immer mit einem Druck zurück (auch lang gedrückt). Karte schließen/„Alle schließen“ schließt es über einen Root-Druck auf AYNs eigene Taste, nie per force-stop. Teil des Hotfix 1.4.1.

## Bug — RetroArch verliert nach Swap + Fokuswechsel den Controller (intern gefunden, 2026-10-30)

- **Ursache:** RetroArch setzt beim Fortsetzen (z. B. Swap zurück nach oben) seine Port-Zuordnung zurück und vergibt Port 1 an das erste Gerät, das sich meldet. AYNs Fokuswechsel schickt dabei ein künstliches Shift-rechts vom Android-Gerät „Virtual“. Kommt das zuerst an, meldet RetroArch „Virtual (0/0) not configured“, und das Pad landet auf Port 2 (keine Steuerung). Port-Reservierung in RetroArch brach Steuerkreuz und Stick; sichtbares Pausieren half, war aber zu störend.
- **Stand 2026-10-31 (Owner-Entscheidung):** Das experimentelle `PadPortClaim` (Route wandert beim Swap mit, Stick-Nudge) ist vollständig ausgebaut. Die App wechselt den Controller-Fokus grundsätzlich nicht für den Nutzer, siehe `docs/ARCHITECTURE.md` (`ControllerFocus`). Erscheint als Hotfix in 1.4.1; die Release Notes nennen die Regression aus 1.4.0 und den Melder.
- **Regression in 1.4.0 (gemeldet von u/moosedude451, Reddit, 2026-10-31):** Das Swappen von RetroArch setzt AYNs Controller-Fokus fest (statt Auto-Lock); unter 1.3 hatte der Melder keine Controller-Probleme mit RetroArch beim Swap. Zusätzlich: Jeder Aufruf eines Switchers (oben oder unten) stellt den Fokus auf „unten fest“, der Melder muss jedes Mal in AYNs Einstellungen auf Auto-Lock zurückstellen. Er bleibt vorerst bei 1.3.
  > Whenever I use the app switcher now (either top or bottom), it toggles controller focus lock on the bottom screen and I have manually go in to the Ayn setting to switch it back auto-lock every single time. Also, swapping Retroarch causes a focus lock as well. I'm assuming that this is part of that experimental feature mentioned in the post, but I never had an issue with my controls swapping over in Retroarch on 1.3, so I'm not sure what the purpose of this change was.
- **Ursache Switcher-Teil (am Gerät bestätigt 2026-10-31):** Kein Teil des Experiments. AYNs „Auto-lock“ ist `screen_focus_lock=0`; `ControllerFocus.currentTarget()` kannte nur `1`/`2` und stellte nach Recents alles andere als „unten fest“ (`2`) wieder her. Fix: Auto-lock wird als eigener Wiederherstellungswert erhalten. Teil des Hotfix 1.4.1.

## Feature Request — Umschaltbarer Switcher-Inhalt: Bottom-only oder alle Apps (Priorität: mittel)

**Was tun und warum:** Optional die auf beiden Displays beobachteten Apps im Bottom-Switcher anbieten, damit eine App nicht erst einmal auf dem unteren Screen geöffnet worden sein muss.

- **Mehrwert einer Lösung:** Schnellere App-Auswahl und weniger überraschende Lücken, ohne den bewusst kompakten Bottom-only-Modus aufzugeben.
- **Defizit laut Feedback:** Der Bottom-Switcher zeigt nur Apps, die bereits auf dem unteren Display liefen; der System-Switcher oben wirkt vollständiger.
- **Auswirkung auf den Ist-Stand:** Das Verhalten ist beabsichtigte Folge der pro Display geführten In-Memory-Historie in `AccessibilityAppSource`. Ein Toggle könnte beide Historien zusammenführen; Apps, die seit Start des Accessibility Service noch nie beobachtet wurden, bleiben wegen Androids Task-Zugriffsbeschränkung weiterhin unsichtbar. Diese Grenze muss im UI erklärt bleiben.
- **Quelle:** [Vorschlag für umschaltbare App-Auswahl](https://www.reddit.com/r/AynThor/comments/1xfj5ew/back_button_hero_130_one_button_many_possibilities/19ro34lzbhxb4/)

## Feature Request — Controller-Steuerung für die Recent-/Switcher-Menüs (Priorität: mittel)

**Was tun und warum:** Zuerst den eigenen Bottom-Switcher mit D-Pad/Stick und Bestätigung/Zurück bedienbar machen, damit Nutzer für die Auswahl nicht vom Controller zum Touchscreen wechseln müssen.

- **Mehrwert einer Lösung:** Geschlossener Handheld-Workflow, bessere Barrierefreiheit und schnellerer App-Wechsel während des Spielens.
- **Defizit laut Feedback:** Für eine Auswahl in den Recent-Menüs müssen die Hände vom Controller genommen werden.
- **Auswirkung auf den Ist-Stand:** `SwitcherOverlay` ist derzeit touchzentriert (horizontal scrollen, Karte antippen, nach oben wischen). Controller-Navigation im eigenen Overlay ist implementierbar, kann aber dem laufenden Spiel Fokus entziehen und kollidiert potenziell mit dem Ziel „Spiel läuft weiter“. Der obere Android-System-Recents-Screen gehört dagegen dem System und lässt sich von der App nur begrenzt verändern; beide Fälle sollten getrennt bewertet werden.
- **Quelle:** [Controller-Support für Recent-Menüs](https://www.reddit.com/r/AynThor/comments/1xfj5ew/back_button_hero_130_one_button_many_possibilities/19rp4jofi8r8h/)

## Feature Request — Übergeordnete Gruppe für primäre Back-Gesten (Priorität: niedrig)

**Was tun und warum:** Die Bereiche Single, Double, Tap-and-hold und Hold visuell unter „Back button gestures“ oder „Primary actions“ zusammenfassen, damit ihre gemeinsame Rolle sofort erkennbar ist.

- **Mehrwert einer Lösung:** Verbessert Orientierung und trennt Kernbedienung klar von optionalen Kombinationen.
- **Defizit laut Feedback:** Die einzelnen Einstellungsbeschreibungen wirken ohne gemeinsame Überschrift weniger eindeutig gruppiert.
- **Auswirkung auf den Ist-Stand:** v1.3.0 hat bereits separate Überschriften für jede Geste, aber keine gemeinsame übergeordnete Ebene. Die Änderung ist rein visuell und sollte Snapshot-/Golden-Tests sowie deutsche und englische Texte aktualisieren.
- **Quelle:** [Vorschlag „Screen Swap Hotkey“-Header](https://www.reddit.com/r/AynThor/comments/1xfj5ew/back_button_hero_130_one_button_many_possibilities/19ro34lzbhxb4/)

## Feature Request — Optionale Hotkeys klar gruppieren und „Unbind“ nach oben holen (Priorität: mittel)

**Was tun und warum:** Einen Header „Optional hotkeys“ vor Shoulder/D-Pad einführen und „Unbind optionals“ direkt dort platzieren, damit Nutzer alle Zusatzbelegungen löschen, bevor sie acht Einträge einzeln bearbeiten.

- **Mehrwert einer Lösung:** Bessere Auffindbarkeit, weniger unnötige Einzelarbeit und klarere Kommunikation, dass die Hotkeys nicht Pflicht sind.
- **Defizit laut Feedback:** Der Sammel-Reset wird erst weit unten unter „Help and debug“ entdeckt; bis dahin könnten Nutzer bereits jede Belegung einzeln entfernt haben.
- **Auswirkung auf den Ist-Stand:** Die Sammelfunktion existiert vollständig, ist aber im Wartungsbereich statt am Ort ihrer Wirkung platziert. Benötigt hauptsächlich UI-Umsortierung und Anpassungen an Golden-/Snapshot-Tests.
- **Quelle:** [Gruppierung und Platzierung von „Unbind optionals“](https://www.reddit.com/r/AynThor/comments/1xfj5ew/back_button_hero_130_one_button_many_possibilities/19ro34lzbhxb4/)

## Feature Request — Optionale Hotkeys standardmäßig leer oder als Preset anbieten (Priorität: mittel, Produktentscheidung)

**Was tun und warum:** Entweder neue Installationen ohne optionale Belegungen starten lassen oder ein explizites „Recommended settings“-Preset neben „Unbind optionals“ anbieten, um Überraschungen zu vermeiden und die bewährte Konfiguration trotzdem leicht zugänglich zu halten.

- **Mehrwert einer Lösung:** Stärkt das Versprechen „alles optional“ und reduziert die Einstiegshürde für Nutzer, die ausschließlich Screen-Swap und App-Drawer möchten.
- **Defizit laut Feedback:** Die Default-Belegung wirkt trotz Optionalität zunächst aktiv und umfangreich.
- **Auswirkung auf den Ist-Stand:** v1.3.0 belegt ab Werk alle vier Shoulder-Buttons und alle vier D-Pad-Richtungen. Wegen der bestehenden Migrationsregel darf eine Änderung vorhandene Installationen nicht überschreiben; sie sollte nur Fresh Installs/Reset oder ein bewusst gewähltes Preset betreffen. Ein Preset ist wahrscheinlich der bessere Kompromiss, weil die aktuellen Defaults gleichzeitig als praktisch gelobt wurden.
- **Quelle:** [Vorschlag „off by default“/Preset](https://www.reddit.com/r/AynThor/comments/1xfj5ew/back_button_hero_130_one_button_many_possibilities/19ro34lzbhxb4/)

## Feature Request — Guide-/Second-Screen-Anwendungsfall klar dokumentieren (Priorität: niedrig)

**Was tun und warum:** In README/FAQ kurz zeigen, wie man unten zu Browser oder Guide wechselt, während das Spiel oben weiterläuft, einschließlich Hinweis auf Controller-Fokus und mögliche Frontend-Overlays.

- **Mehrwert einer Lösung:** Macht einen wichtigen Dual-Screen-Use-Case sichtbar und beantwortet eine konkrete Kauf-/Installationsfrage ohne neue Funktion.
- **Defizit laut Feedback:** Es war nicht eindeutig, ob bei WatermelonDS/Azahar ein Guide unten geöffnet werden kann, während das Spiel oben weiterläuft.
- **Auswirkung auf den Ist-Stand:** Die App arbeitet displaybezogen und der beworbene Ablauf sieht vor, dass das Spiel weiterläuft. Vor einer eindeutigen Zusage sollte dies mit den genannten Emulatoren manuell geprüft werden; danach genügt voraussichtlich Dokumentation statt Code.
- **Quelle:** [Frage zum Guide auf dem Bottom-Screen](https://www.reddit.com/r/AynThor/comments/1xfj5ew/back_button_hero_130_one_button_many_possibilities/19ropo1r3cvkz/)

## Feature Request — AI-Transparenz in Release-Posts standardisieren (Priorität: niedrig, Kommunikation)

**Was tun und warum:** Künftige Community-Posts von Anfang an deutlich als AI-assisted kennzeichnen und direkt auf Quellcode, reproduzierbare Builds, Tests und Datenschutzdetails verlinken, um Regelverstöße und Vertrauenszweifel zu vermeiden.

- **Mehrwert einer Lösung:** Erfüllt Community-Regeln, nimmt sachliche Sicherheitsbedenken ernst und lenkt die Diskussion auf überprüfbare Softwarequalität.
- **Defizit laut Feedback:** Ein Moderator musste auf Rule 7 hinweisen; einzelne Personen äußerten pauschale Angst oder Ablehnung gegenüber AI-assistierten Apps.
- **Auswirkung auf den Ist-Stand:** Im Reddit-Post wurde die Angabe nachgetragen, und das Repository dokumentiert Open Source, Tests, Quality Gates, Berechtigungen und fehlendes Tracking bereits ausführlich. Es besteht kein erkennbarer Code-Mangel, sondern ein wiederverwendbarer Release-/Kommunikationsschritt. Abwertende und beleidigende Nebendiskussionen liefern keine zusätzliche Produktanforderung.
- **Quelle:** [Rule-7-Hinweis](https://www.reddit.com/r/AynThor/comments/1xfj5ew/back_button_hero_130_one_button_many_possibilities/19rnplazt18f6/), [Vorbehalt trotz Interesse](https://www.reddit.com/r/AynThor/comments/1xfj5ew/back_button_hero_130_one_button_many_possibilities/19rnvd7u24kbl/), [Qualität/Open Source als Gegenargument](https://www.reddit.com/r/AynThor/comments/1xfj5ew/back_button_hero_130_one_button_many_possibilities/19rnqre3hv5cx/)

## Empfohlene Reihenfolge

1. **Launcher-/Frontend-Kompatibilität reproduzieren:** Cocoon, NeoStation, iiSU sowie getrennte Top-/Bottom-Launcher; dabei Task-Move gegen Paket-Relaunch vergleichen.
2. **Konfigurierbare Swap-Ausschlüsse umsetzen:** hoher Nutzen, klarer Scope und mögliche Teilantwort auf mehrere Berichte.
3. **Controller-Navigation im eigenen Bottom-Switcher prototypisieren:** Fokus- und „Spiel läuft weiter“-Risiko früh auf echter Hardware prüfen.
4. **Settings neu gruppieren:** Optional-Hotkey-Header und „Unbind optionals“ verschieben; Preset-Entscheidung separat treffen.
5. **Switcher-Scope-Toggle evaluieren:** beide Display-Historien zusammenführen, technische Grenzen transparent anzeigen.
6. **ES-DE und Emulator-Use-Cases dokumentieren:** zunächst reproduzieren, dann entweder gezielt beheben oder als bekannte Integrationsgrenze mit Workaround festhalten.

## Nicht als eigener Arbeitsauftrag gewertete Kommentare

Reine Zustimmung, Dank, Vergleiche ohne konkreten Fehlerbericht, Rückfragen zur generellen Funktionsweise sowie die persönliche Debatte über AI wurden in die Gesamtbewertung aufgenommen, aber nicht künstlich zu zusätzlichen Bugs gemacht. Dazu gehören insbesondere „looks great“, „all I ever wanted“, „better than Wayfinder because it just works“ sowie Autor-Antworten auf diese Aussagen. Der einzelne Punkt-Kommentar (`.`) enthält kein auswertbares Feedback.
