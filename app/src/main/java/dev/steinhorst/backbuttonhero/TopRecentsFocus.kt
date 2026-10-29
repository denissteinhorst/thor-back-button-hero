package dev.steinhorst.backbuttonhero

import android.view.Display

/** Finish AYN's unfocused Overview handoff without issuing a Home intent that closes Recents. */
object TopRecentsFocus {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            require(args.isEmpty() || args.contentEquals(arrayOf("controller")))
            val power = ProcessBuilder("dumpsys", "power").start().inputStream.bufferedReader().use { it.readText() }
            require(power.contains("mWakefulness=Awake")) { "display is not interactive" }
            val dump = ProcessBuilder("dumpsys", "activity", "com.android.launcher3")
                .start().inputStream.bufferedReader().use { it.readText() }
            // Recheck inside the root worker: a queued focus request must not reopen a menu that
            // the user has already closed while another pservice transaction was running.
            val before = TopRecentsState.parse(dump)
            require(before.overview) { "Overview is no longer active" }
            val display = Display.DEFAULT_DISPLAY.toString()
            if (!before.windowFocused) {
                run("am", "start", "--display", display, "-a", "dev.steinhorst.backbuttonhero.FOCUS_RECENTS",
                    "-n", "com.android.launcher3/.uioverrides.QuickstepLauncher", "-f", "0x10000000")
            }
            if (args.isNotEmpty()) {
                // Tab exits native touch mode. The service restores the same visible card's
                // input focus afterward, instead of injecting a spatial direction that can scroll.
                run("input", "keyboard", "-d", display, "keyevent", "61")
            }
            finish("OK focused", 0)
        } catch (t: Throwable) {
            finish("ERR ${t.javaClass.simpleName}: ${t.message}", 1)
        }
    }

    private fun run(vararg command: String) {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        require(process.waitFor() == 0 && !output.contains("Error:")) { "Overview focus command failed" }
    }

    private fun finish(line: String, code: Int) {
        println(line)
        System.out.flush()
        Runtime.getRuntime().halt(code)
    }
}
