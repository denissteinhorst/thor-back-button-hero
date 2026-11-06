package dev.steinhorst.backbuttonhero

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent

/** Root entry point: touch only the app-owned shield, never an underlying app. */
@SuppressLint("PrivateApi") // Public gesture injection cannot restrict the receiving UID.
object ControllerFocusTouch {
    fun shieldPresent(dump: String, title: String, display: Int, uid: Int): Boolean =
        dump.lineSequence().any { line ->
            line.contains(" $title'") && line.contains("displayId=$display,") &&
                line.contains("ownerUid=$uid,") && !line.contains("NOT_TOUCHABLE")
        }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            require(args.size == 5)
            val (display, x, y, uid) = args.take(4).map(String::toInt)
            val title = args[4]
            require(title.matches(Regex("BBHAutoFocus-[0-9a-f-]+")))
            require(display >= 0 && x >= 0 && y >= 0 && uid >= 10000)
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "input") as IBinder
            val input = Class.forName("android.hardware.input.IInputManager\$Stub").getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)!!
            val inject = input.javaClass.getMethod("injectInputEventToTarget", InputEvent::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            // A UID also owns SettingsActivity. Require this operation's unique input window
            // before injecting, so a retired/missing shield cannot turn into a settings tap.
            val dump = ProcessBuilder("dumpsys", "input").start().inputStream.bufferedReader().use { it.readText() }
            require(shieldPresent(dump, title, display, uid)) { "focus shield is unavailable" }
            val time = SystemClock.uptimeMillis()
            fun send(action: Int): Boolean {
                val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
                try {
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, display)
                    return inject.invoke(input, event, 2, uid) == true
                } finally { event.recycle() }
            }
            var pressed = false
            try {
                require(send(MotionEvent.ACTION_DOWN)) { "shield did not receive touch" }
                pressed = true
                require(send(MotionEvent.ACTION_UP)) { "shield did not receive release" }
                pressed = false
            } finally {
                if (pressed) runCatching { send(MotionEvent.ACTION_CANCEL) }
            }
            println("OK")
        } catch (t: Throwable) {
            println("ERR ${t.javaClass.simpleName}: ${t.cause?.message ?: t.message}")
        }
        System.out.flush()
        Runtime.getRuntime().halt(0)
    }
}
