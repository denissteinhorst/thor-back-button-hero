# Schritt 27 — `CardTouch`-Extraktion + Tests

## 1. Ziel

Die private innere Klasse `SwitcherOverlay.CardTouch` (reine Gestenmathematik, keine
View-Abhängigkeit außer `ViewConfiguration` für `slop`) in eine eigene Datei extrahieren und
mit `MotionEvent`-Fixtures testen.

## 2. Voraussetzungen

Schritt 26 ist committet und `./tools/verify.sh` läuft grün.

## 3. Exakte Aufgaben

1. Datei anlegen `app/src/main/java/dev/steinhorst/backbuttonhero/CardTouch.kt`. Den
   kompletten Inhalt der `private class CardTouch` aus `SwitcherOverlay.kt` **wortwörtlich**
   hierher verschieben, Sichtbarkeit von `private` auf `internal` ändern:
   ```kotlin
   package dev.steinhorst.backbuttonhero

   import android.content.Context
   import android.view.MotionEvent
   import android.view.View
   import android.view.ViewConfiguration
   import kotlin.math.abs

   /** Tap -> callback(false); vertical swipe up past a threshold -> callback(true). Horizontal drags go to the scroller. */
   internal class CardTouch(ctx: Context, private val threshold: Int, private val done: (closing: Boolean) -> Unit) : View.OnTouchListener {
       private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
       private var downX = 0f
       private var downY = 0f
       private var dragging = false

       override fun onTouch(v: View, e: MotionEvent): Boolean {
           when (e.actionMasked) {
               MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = false }
               MotionEvent.ACTION_MOVE -> {
                   val dy = e.rawY - downY
                   if (!dragging && dy < -slop && abs(dy) > abs(e.rawX - downX)) {
                       dragging = true
                       v.parent.requestDisallowInterceptTouchEvent(true)
                   }
                   if (dragging) {
                       v.translationY = dy.coerceAtMost(0f)
                       v.alpha = (1f + dy / (threshold * 1.5f)).coerceIn(0.2f, 1f)
                   }
               }
               MotionEvent.ACTION_UP -> {
                   val dy = e.rawY - downY
                   if (dragging) {
                       if (dy < -threshold * 0.3f) done(true)
                       else v.animate().translationY(0f).alpha(1f).setDuration(150).start()
                   } else if (abs(dy) < slop && abs(e.rawX - downX) < slop) { v.performClick(); done(false) }
                   dragging = false
               }
               MotionEvent.ACTION_CANCEL -> {
                   v.animate().translationY(0f).alpha(1f).setDuration(150).start()
                   dragging = false
               }
           }
           return true
       }
   }
   ```
2. In `SwitcherOverlay.kt` die ursprüngliche `private class CardTouch { ... }`-Definition
   (bisher am Ende der Datei, innerhalb der Klasse) vollständig löschen. Die eine
   Verwendungsstelle (`setOnTouchListener(CardTouch(ctx, thumbH) { closing -> ... })`)
   bleibt unverändert — `CardTouch` ist jetzt eine Top-Level-Klasse im selben Package, kein
   Import nötig.
3. Nicht mehr gebrauchte Imports (`kotlin.math.abs`, falls `SwitcherOverlay.kt` sie sonst
   nirgends mehr verwendet) aus `SwitcherOverlay.kt` entfernen.
4. Datei anlegen `app/src/test/java/dev/steinhorst/backbuttonhero/CardTouchTest.kt`:
   ```kotlin
   package dev.steinhorst.backbuttonhero

   import android.view.MotionEvent
   import android.view.View
   import androidx.test.core.app.ApplicationProvider
   import org.junit.Assert.assertEquals
   import org.junit.Test
   import org.junit.runner.RunWith
   import org.robolectric.RobolectricTestRunner

   @RunWith(RobolectricTestRunner::class)
   class CardTouchTest {

       private fun motionEvent(action: Int, x: Float, y: Float) =
           MotionEvent.obtain(0, 0, action, x, y, 0)

       private fun view() = View(ApplicationProvider.getApplicationContext())

       @Test
       fun `a plain tap with no movement calls done(false)`() {
           var result: Boolean? = null
           val touch = CardTouch(ApplicationProvider.getApplicationContext(), 200) { result = it }
           val v = view()
           touch.onTouch(v, motionEvent(MotionEvent.ACTION_DOWN, 100f, 100f))
           touch.onTouch(v, motionEvent(MotionEvent.ACTION_UP, 100f, 100f))
           assertEquals(false, result)
       }

       @Test
       fun `a vertical swipe up past the threshold calls done(true)`() {
           var result: Boolean? = null
           val threshold = 200
           val touch = CardTouch(ApplicationProvider.getApplicationContext(), threshold) { result = it }
           val v = view()
           touch.onTouch(v, motionEvent(MotionEvent.ACTION_DOWN, 100f, 500f))
           touch.onTouch(v, motionEvent(MotionEvent.ACTION_MOVE, 100f, 500f - threshold - 50f))
           touch.onTouch(v, motionEvent(MotionEvent.ACTION_UP, 100f, 500f - threshold - 50f))
           assertEquals(true, result)
       }

       @Test
       fun `a vertical swipe that does not pass 30 percent of the threshold snaps back, calls nothing`() {
           var result: Boolean? = null
           val threshold = 200
           val touch = CardTouch(ApplicationProvider.getApplicationContext(), threshold) { result = it }
           val v = view()
           touch.onTouch(v, motionEvent(MotionEvent.ACTION_DOWN, 100f, 500f))
           touch.onTouch(v, motionEvent(MotionEvent.ACTION_MOVE, 100f, 500f - threshold - 10f))
           touch.onTouch(v, motionEvent(MotionEvent.ACTION_UP, 100f, 500f - 10f))
           assertEquals(null, result)
       }

       @Test
       fun `a predominantly horizontal drag never starts a vertical close-drag`() {
           var result: Boolean? = null
           val touch = CardTouch(ApplicationProvider.getApplicationContext(), 200) { result = it }
           val v = view()
           touch.onTouch(v, motionEvent(MotionEvent.ACTION_DOWN, 100f, 500f))
           touch.onTouch(v, motionEvent(MotionEvent.ACTION_MOVE, 300f, 480f))
           touch.onTouch(v, motionEvent(MotionEvent.ACTION_UP, 300f, 480f))
           assertEquals(null, result)
       }

       @Test
       fun `ACTION_CANCEL resets drag state without calling done`() {
           var result: Boolean? = null
           val touch = CardTouch(ApplicationProvider.getApplicationContext(), 200) { result = it }
           val v = view()
           touch.onTouch(v, motionEvent(MotionEvent.ACTION_DOWN, 100f, 500f))
           touch.onTouch(v, motionEvent(MotionEvent.ACTION_MOVE, 100f, 300f))
           touch.onTouch(v, motionEvent(MotionEvent.ACTION_CANCEL, 100f, 300f))
           assertEquals(null, result)
       }
   }
   ```
   **Hinweis:** `v.parent.requestDisallowInterceptTouchEvent(true)` wird aufgerufen, sobald
   `dragging` auf `true` wechselt — ein `View` ohne gesetzten `parent` wirft dabei eine
   `NullPointerException`. Falls Test 2 deshalb fehlschlägt: den View vor dem Test in eine
   `FrameLayout` (aus `ApplicationProvider.getApplicationContext()`) einhängen
   (`FrameLayout(context).apply { addView(v) }`), damit `v.parent` nicht `null` ist — das ist
   eine Testfixture-Korrektur, keine Änderung an `CardTouch` selbst.
5. In `app/build.gradle.kts` die `jacocoCoverageVerification`-`violationRules` um eine
   weitere Regel ergänzen:
   ```kotlin
   rule {
       element = "CLASS"
       includes = listOf("dev.steinhorst.backbuttonhero.CardTouch")
       limit { minimum = "0.90".toBigDecimal() }
   }
   ```

## 4. Nicht anfassen

- Kein Verhalten der Gestenlogik selbst ändern — jede Konstante (`slop`, `threshold * 1.5f`,
  `threshold * 0.3f`, `duration = 150`) bleibt exakt wie im Original.
- `SwitcherOverlay.kt`'s `card(...)`-Methode bleibt bis auf die Entfallene innere Klasse und
  ggf. den Import unverändert.

## 5. Akzeptanzkriterien

- `./tools/verify.sh` grün, inklusive der neuen `CardTouch`-Coverage-Regel.
- Alle 5 neuen Tests grün.
- Bestehende Snapshot-Tests aus Schritt 11–13 weiterhin grün (beweist, dass `SwitcherOverlay`
  durch die Extraktion nicht beeinflusst wurde — auch wenn diese Tests das Overlay selbst
  nicht rendern, siehe `TEST-GAPS.md`, Punkt 3).

## 6. Gate

`./tools/verify.sh`. Bei Rot: `git restore` auf beide Produktivdateien + die neue
Klassendatei + Testdatei, stoppen, melden.

## 7. Commit-Message-Vorschlag

```
refactor: extract CardTouch into its own testable file

Verbatim move of the tap-vs-swipe gesture discrimination logic out of
SwitcherOverlay's private inner class. Pure MotionEvent math, now unit
tested without needing a rendered overlay.
```
