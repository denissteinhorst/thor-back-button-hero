# Schritt 33 — Fix: Thumbnail-Cache begrenzen (bedingt)

## 1. Ziel

Falls Schritt 31 oder 32 den unbegrenzten `AccessibilityAppSource.thumbnails`-Cache als
echtes Problem belegt: eine Obergrenze einziehen, ohne sichtbares Verhalten zu ändern.

## 2. Voraussetzungen

Schritt 32 ist committet. **Zusätzlich:** `docs/hardening/LEAK-REPORT.md` oder
`docs/hardening/baseline/perf-baseline.md` muss den Thumbnail-Cache konkret als Problem
benennen (z. B. steigender `Native Heap`/`Graphics`-Wert über eine lange Sitzung mit vielen
verschiedenen Apps, oder ein LeakCanary-Fund mit `thumbnails`-Feld im Retention-Pfad).

**Falls kein Beleg vorliegt: diesen Schritt in `PROGRESS.md` als „übersprungen — kein
Messbeleg" markieren und direkt zu Schritt 34 übergehen.** Nicht spekulativ optimieren
(globale Regel 7).

## 3. Exakte Aufgaben — nur falls der Beleg vorliegt

1. In `AccessibilityAppSource.kt` den `thumbnails`-Cache durch eine Variante mit fester
   Obergrenze ersetzen, die funktional identisch bleibt (`get`/`put`/`remove` dasselbe
   Verhalten, nur mit Eviction des am längsten nicht genutzten Eintrags, wenn eine Grenze
   überschritten wird) — z. B. ein `LinkedHashMap` mit `accessOrder = true` und
   `removeEldestEntry` überschrieben, Grenze = 12 (deutlich über der in der Praxis üblichen
   Zahl gleichzeitig offener Apps, damit sich für den Normalfall **nichts** sichtbar
   ändert):
   ```kotlin
   private val thumbnails = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
       override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean {
           if (size > 12) {
               eldest.value.recycle()
               return true
           }
           return false
       }
   }
   ```
2. `saveThumbnail` und `forget` bleiben unverändert — sie arbeiten weiterhin über
   `thumbnails[pkg]`/`thumbnails.remove(pkg)`, was mit `LinkedHashMap` identisch
   funktioniert.

## 4. Nicht anfassen

- Kein sichtbares Verhalten: solange nicht mehr als 12 verschiedene Apps gleichzeitig in der
  MRU-Historie sind (in der Praxis nie der Fall auf einem Dual-Screen-Handheld), ändert sich
  nichts.
- Kein anderer Teil von `AccessibilityAppSource.kt`.

## 5. Akzeptanzkriterien

- `./tools/verify.sh` grün.
- Bestehender `AccessibilityAppSourceTest` (Schritt 07) weiterhin grün ohne Anpassung.
- Falls Zeit erlaubt: ein neuer Test, der >12 Thumbnails speichert und bestätigt, dass die
  ältesten evicted werden, ohne dass `forget()`/`saveThumbnail()` für die verbleibenden
  Einträge ihr Verhalten ändert.

## 6. Gate

`./tools/verify.sh`. Bei Rot: `git restore app/src/main/java/.../AccessibilityAppSource.kt`,
stoppen, melden.

## 7. Commit-Message-Vorschlag

```
perf: cap the thumbnail cache at 12 entries with LRU eviction

Only applied because <LEAK-REPORT.md / perf-baseline.md> documented
<concrete finding>. No visible behavior change below the cap, which
exceeds realistic concurrent-app counts on this device.
```

(Platzhalter in Klammern beim Commit durch den tatsächlichen Befund-Verweis ersetzen.)
