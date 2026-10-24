package dev.steinhorst.backbuttonhero.support

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import java.io.File

/**
 * Compares [actual] against the golden file at
 * app/src/test/resources/golden/[name].txt. Set UPDATE_GOLDENS=1 to (re)write it instead
 * of comparing — only ever run with that flag after an explicit, user-approved UI change.
 */
object Golden {
    fun verify(name: String, actual: String) {
        val file = File("src/test/resources/golden/$name.txt")
        if (System.getenv("UPDATE_GOLDENS") == "1") {
            file.parentFile?.mkdirs()
            file.writeText(actual)
            return
        }
        if (!file.exists()) {
            fail(
                "Golden file $file does not exist. If this is an intentional new " +
                    "snapshot, re-run with UPDATE_GOLDENS=1 - only after explicit user " +
                    "approval, never to make a failing test pass."
            )
        }
        assertEquals(file.readText(), actual)
    }
}
