# Schritt 35 — Fix: PackageManager-Label/Icon-Cache (bedingt)

## 1. Ziel

Falls Schritt 31 belegt, dass `appsOnDisplay()`'s synchroner `getApplicationIcon`/
`getApplicationLabel`-Aufruf pro App bei jedem Switcher-Öffnen spürbar zur
Doppeltipp-Latenz beiträgt: Label und Icon je Paket einmalig cachen, solange die App nicht
deinstalliert/aktualisiert wird.

## 2. Voraussetzungen

Schritt 34 ist abgeschlossen oder bewusst übersprungen. **Zusätzlich:** ein konkreter Beleg
aus Schritt 31 (gemessene `am start -W`- oder Frame-Zeit-Verschlechterung korrelierend mit
der Anzahl offener Apps, oder ein Nutzer-beobachtetes Ruckeln beim Öffnen des Switchers).

**Ohne Beleg: in `PROGRESS.md` als „übersprungen — kein Messbeleg" markieren, weiter zu
Schritt 36.**

## 3. Exakte Aufgaben — nur falls der Beleg vorliegt

In `AccessibilityAppSource.kt` zwei private Caches ergänzen und `iconOf`/`labelOf` darüber
umleiten, **ohne** das Rückgabeverhalten (inklusive des `NameNotFoundException`-Fallbacks)
zu ändern:

```kotlin
private val iconCache = mutableMapOf<String, Drawable?>()
private val labelCache = mutableMapOf<String, String>()

private fun iconOf(pm: PackageManager, pkg: String) =
    iconCache.getOrPut(pkg) {
        try { pm.getApplicationIcon(pkg) } catch (e: PackageManager.NameNotFoundException) { null }
    }

private fun labelOf(pm: PackageManager, pkg: String): String =
    labelCache.getOrPut(pkg) {
        try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
        catch (e: PackageManager.NameNotFoundException) { pkg }
    }
```

Die Caches in `forget(packageName)` ebenfalls bereinigen, damit eine deinstallierte und neu
installierte App (anderes Icon/Label, gleicher Package-Name — z. B. nach einem Update)
keinen veralteten Eintrag zurückgibt:
```kotlin
override fun forget(packageName: String) {
    history.values.forEach { it.remove(packageName) }
    thumbnails.remove(packageName)?.recycle()
    iconCache.remove(packageName)
    labelCache.remove(packageName)
}
```

**Wichtiger Kompromiss, der dokumentiert werden muss:** ein Label-/Icon-Update einer App,
die weiterhin installiert bleibt (z. B. ein App-Update ändert nur das Launcher-Icon, ohne
dass die App zwischenzeitlich aus der MRU-Historie verschwindet, also ohne `forget()`-Aufruf)
würde mit diesem Cache nicht sofort sichtbar — das alte Icon/Label bliebe bis zum nächsten
`forget()` (App schließen über den Switcher) im Cache. Das ist eine neue, wenn auch sehr
unwahrscheinliche Randbedingung. **Falls das als unzulässige Verhaltensänderung bewertet
wird: diesen Schritt nicht umsetzen** und stattdessen in `docs/hardening/PERF-RESULT.md`
(Schritt 36) als geprüft-aber-verworfen dokumentieren.

## 4. Nicht anfassen

- Der `NameNotFoundException`-Fallback (`null` für Icon, `pkg` als Label) bleibt exakt
  erhalten.
- Keine andere Methode in `AccessibilityAppSource.kt`.

## 5. Akzeptanzkriterien

- `./tools/verify.sh` grün.
- Bestehender `AccessibilityAppSourceTest` weiterhin grün.
- Re-Messung (Schritt 36) zeigt eine niedrigere oder gleiche `am start -W`-/Frame-Zeit beim
  wiederholten Öffnen des Switchers mit denselben Apps im Vergleich zur Baseline.
- Manuelle Device-Prüfung: Switcher-Karten zeigen weiterhin korrekte Icons/Labels.

## 6. Gate

`./tools/verify.sh` plus manuelle Sichtprüfung auf dem Gerät. Bei Rot oder sichtbarem
Fehler: `git restore app/src/main/java/.../AccessibilityAppSource.kt`, stoppen, melden.

## 7. Commit-Message-Vorschlag

```
perf: cache per-package icon and label lookups

Only applied because perf-baseline.md documented <concrete finding>.
Caches are invalidated on forget() (app closed via the switcher) to
avoid serving a stale icon/label after a reinstall under the same
package name.
```

(Platzhalter im Commit durch den tatsächlichen Befund-Verweis ersetzen.)
