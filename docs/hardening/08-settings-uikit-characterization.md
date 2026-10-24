# Schritt 08 — Characterization: `SettingsUiKit`-Helfer

## 1. Ziel

Die freien Funktionen in `SettingsUiKit.kt` (`dp`, `isAccessibilityServiceEnabled`,
`currentServiceStatus`, `serviceStatusText`) absichern — insbesondere die drei
`ServiceStatus`-Textzuordnungen, da `ServiceStatus.ON` aus einem Unit-Test auf keinem anderen
Weg erreichbar ist (siehe `TEST-GAPS.md`, Punkt 1).

## 2. Voraussetzungen

Schritt 07 ist committet und `./tools/verify.sh` läuft grün.

**Vorher lesen: `docs/hardening/PITFALLS.md`, insbesondere P2.** Jeder Test in diesem Schritt
baut eine `SettingsActivity`, deren `onCreate` einen echten Netzwerk-Thread startet. Das ist
bekannt, erwartet, lässt die Tests nicht rot werden und wird **nicht** umgebaut.

## 3. Exakte Aufgaben

Datei anlegen `app/src/test/java/dev/steinhorst/backbuttonhero/SettingsUiKitTest.kt`:

```kotlin
package dev.steinhorst.backbuttonhero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// xxhdpi pins density to 3.0 so the dp() assertion below has a concrete expected value.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xxhdpi")
class SettingsUiKitTest {

    private fun activity() =
        Robolectric.buildActivity(SettingsActivity::class.java).create().get()

    @Test
    fun `dp converts dip to pixels at the pinned xxhdpi density of 3`() {
        val activity = activity()
        // JUnit 4 requires a delta for float comparisons - assertEquals(float, float) is deprecated.
        assertEquals(3.0f, activity.resources.displayMetrics.density, 0.001f)
        assertEquals(48, activity.dp(16))
        assertEquals(3, activity.dp(1))
        assertEquals(0, activity.dp(0))
    }

    @Test
    fun `isAccessibilityServiceEnabled is false when no service is enabled`() {
        val activity = activity()
        assertFalse(activity.isAccessibilityServiceEnabled())
    }

    @Test
    fun `currentServiceStatus is OFF when the accessibility service is disabled`() {
        val activity = activity()
        assertEquals(ServiceStatus.OFF, activity.currentServiceStatus())
    }

    @Test
    fun `serviceStatusText for ON reports the service is on`() {
        val activity = activity()
        assertEquals(
            activity.getString(R.string.status_on),
            activity.serviceStatusText(ServiceStatus.ON),
        )
    }

    @Test
    fun `serviceStatusText for UNRESPONSIVE reports it is enabled but not responding`() {
        val activity = activity()
        assertEquals(
            activity.getString(R.string.status_unresponsive),
            activity.serviceStatusText(ServiceStatus.UNRESPONSIVE),
        )
    }

    @Test
    fun `serviceStatusText for OFF includes the app name`() {
        val activity = activity()
        val expected = activity.getString(R.string.status_off, activity.getString(R.string.app_name))
        assertEquals(expected, activity.serviceStatusText(ServiceStatus.OFF))
    }
}
```

Hinweis: `currentServiceStatus()` kann hier **nicht** den `ON`-Zweig erreichen, weil
`SwitcherService.isConnected` in diesem Testkontext nie `true` wird (siehe `TEST-GAPS.md`,
Punkt 1) — deshalb testet dieser Schritt `ON` nur über `serviceStatusText(ServiceStatus.ON)`
direkt mit dem Enum-Wert, nicht über `currentServiceStatus()`.

## 4. Nicht anfassen

- `SettingsUiKit.kt` bleibt unverändert.
- Keine Mock-/Shadow-Konstruktion, die versucht, `SwitcherService.isConnected` zu setzen —
  das Feld hat `private set`, ein Zugriff von außen wäre ein Testbarkeits-Hack, der laut
  `TEST-GAPS.md` bewusst nicht gemacht wird.

## 5. Akzeptanzkriterien

- `./tools/verify.sh` grün.
- Alle 6 Tests grün.

## 6. Gate

`./tools/verify.sh`. Bei Rot: nur die neue Testdatei zurücknehmen, stoppen, melden.

## 7. Commit-Message-Vorschlag

```
test: characterize SettingsUiKit helpers and all three ServiceStatus texts

ServiceStatus.ON is tested directly via serviceStatusText(), since
SwitcherService.isConnected's private setter makes it unreachable through
currentServiceStatus() in a unit test (see TEST-GAPS.md).
```
