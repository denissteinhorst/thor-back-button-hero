package dev.steinhorst.backbuttonhero

import android.annotation.SuppressLint
import android.os.IBinder

/**
 * Entry point run as root in a bare VM via `app_process` (see [PService.runEntryPoint]), not inside
 * the app. Steps the brightness of one or more displays at once. The bottom screen's brightness is
 * per-display state that only IDisplayManager.setBrightness can change, and that needs a permission
 * no unprivileged app gets - AYN's own "both screens" slider uses the same call.
 *
 * Usage: `BrightnessControl up|down <displayId>...`. The first display's brightness is the base, so
 * several displays end up on the same step. Prints "OK <value>" or "ERR <reason>".
 */
@SuppressLint("PrivateApi") // hidden IDisplayManager, reached by reflection as no SDK exposes it
object BrightnessControl {

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val up = when (args.getOrNull(0)) {
                "up" -> true
                "down" -> false
                else -> return finish("ERR direction", 1)
            }
            val displays = args.drop(1).mapNotNull { it.toIntOrNull() }
            if (displays.isEmpty()) return finish("ERR displays", 1)
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "display") as? IBinder ?: return finish("ERR display unavailable", 1)
            val dm = Class.forName("android.hardware.display.IDisplayManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)!!
            val intType = Int::class.javaPrimitiveType
            val floatType = Float::class.javaPrimitiveType
            val get = dm.javaClass.methods.first { it.name == "getBrightness" && it.parameterTypes.contentEquals(arrayOf(intType)) }
            val set = dm.javaClass.methods.first {
                it.name == "setBrightness" && it.parameterTypes.contentEquals(arrayOf(intType, floatType))
            }
            val current = get.invoke(dm, displays.first()) as Float
            val next = LevelSteps.nextBrightness(current, up)
            displays.forEach { set.invoke(dm, it, next) }
            finish("OK $next", 0)
        } catch (t: Throwable) {
            finish("ERR ${t.javaClass.simpleName}: ${t.cause?.message ?: t.message}", 1)
        }
    }

    // halt, not exit: app_process would otherwise wait on the binder threads that reflection started.
    private fun finish(line: String, code: Int) {
        println(line)
        System.out.flush()
        Runtime.getRuntime().halt(code)
    }
}
