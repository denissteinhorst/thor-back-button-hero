# Schritt 34 — Fix: Bitmap-Churn in `captureFrontApp` (bedingt)

## 1. Ziel

Falls Schritt 31/32 belegt, dass die Zwischen-Bitmaps in
`SwitcherService.captureFrontApp` (der ARGB_8888-Copy vor dem Skalieren, sowie der
`wrapHardwareBuffer`-Bitmap) tatsächlich spürbar zu Speicherdruck beitragen: diese
freigeben, sobald sie nicht mehr gebraucht werden.

## 2. Voraussetzungen

Schritt 33 ist abgeschlossen oder bewusst übersprungen. **Zusätzlich:** ein konkreter Beleg
aus Schritt 31/32 (hohe `Graphics`/`Native Heap`-Werte, die mit Switcher-Öffnungen
korrelieren, oder ein LeakCanary-/Profiler-Fund mit `captureFrontApp` im Pfad).

**Ohne Beleg: in `PROGRESS.md` als „übersprungen — kein Messbeleg" markieren, weiter zu
Schritt 35.**

## 3. Exakte Aufgaben — nur falls der Beleg vorliegt

In `SwitcherService.kt`, Methode `captureFrontApp`, den Block
```kotlin
val buffer = result.hardwareBuffer
val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
val stillFront = !overlay.isShowing && source.topApp(display.displayId) == pkg
if (bitmap != null && stillFront) {
    val scale = THUMB_WIDTH.toFloat() / bitmap.width
    val small = Bitmap.createScaledBitmap(
        bitmap.copy(Bitmap.Config.ARGB_8888, false), THUMB_WIDTH, (bitmap.height * scale).toInt(), true)
    source.saveThumbnail(pkg, small)
}
buffer.close()
```
ersetzen durch eine Variante, die die beiden Zwischenstufen freigibt, sobald das kleine
Thumbnail erzeugt ist — **ohne** das Endergebnis (das in `source.saveThumbnail` gespeicherte
Bitmap) zu verändern:
```kotlin
val buffer = result.hardwareBuffer
val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
val stillFront = !overlay.isShowing && source.topApp(display.displayId) == pkg
if (bitmap != null && stillFront) {
    val scale = THUMB_WIDTH.toFloat() / bitmap.width
    val fullCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
    val small = Bitmap.createScaledBitmap(
        fullCopy, THUMB_WIDTH, (bitmap.height * scale).toInt(), true)
    if (small !== fullCopy) fullCopy.recycle()
    source.saveThumbnail(pkg, small)
}
bitmap?.recycle()
buffer.close()
```
**Begründung für `if (small !== fullCopy) fullCopy.recycle()`:** `createScaledBitmap` kann
dieselbe Instanz zurückgeben, wenn Zielgröße = Quellgröße ist (unwahrscheinlich hier, aber
die Android-Doku garantiert es nicht auszuschließen) — ein `recycle()` auf ein Bitmap, das
gerade als `small` an `saveThumbnail` weitergegeben wird, würde das sichtbare Thumbnail
zerstören. Die Identitätsprüfung verhindert genau das.

## 4. Nicht anfassen

- `source.saveThumbnail(pkg, small)` erhält exakt dasselbe `small`-Bitmap wie vorher — der
  sichtbare Thumbnail-Inhalt ändert sich nicht.
- Kein anderer Teil von `captureFrontApp` oder der umgebenden `takeScreenshot`-Callback-Logik.

## 5. Akzeptanzkriterien

- `./tools/verify.sh` grün.
- Manuelle Device-Prüfung: Switcher mehrfach öffnen, Thumbnails sehen weiterhin korrekt aus
  (kein schwarzes/korruptes Bild durch zu frühes `recycle()`).
- Re-Messung (Teil von Schritt 36) zeigt einen niedrigeren oder gleichen `Graphics`/
  `Native Heap`-Wert nach wiederholtem Öffnen des Switchers im Vergleich zur Baseline.

## 6. Gate

`./tools/verify.sh` **plus** die manuelle Thumbnail-Sichtprüfung auf dem Gerät (ein
korruptes Thumbnail ist eine UX-Regression, kein Test fängt das automatisch ab, da das
Overlay laut `TEST-GAPS.md` nicht snapshotbar ist). Bei Rot oder sichtbarem Fehler:
`git restore app/src/main/java/.../SwitcherService.kt`, stoppen, melden.

## 7. Commit-Message-Vorschlag

```
perf: release intermediate bitmaps in captureFrontApp

Only applied because <perf-baseline.md / LEAK-REPORT.md> documented
<concrete finding>. The final cached thumbnail is byte-identical;
only the throwaway full-resolution copies are recycled.
```

(Platzhalter in Klammern beim Commit durch den tatsächlichen Befund-Verweis ersetzen.)
