package dev.steinhorst.backbuttonhero

import android.net.LocalSocket
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
class PadHelperLifecycleTest {
    @Test
    fun `stop shuts down both socket directions before closing to release blocked reader`() {
        val calls = mutableListOf<String>()
        val socket = object : LocalSocket() {
            override fun shutdownOutput() { calls += "output" }
            override fun shutdownInput() { calls += "input" }
            override fun close() { calls += "close" }
        }
        val helper = PadHelper(RuntimeEnvironment.getApplication(), {}, {}, {})
        ReflectionHelpers.setField(helper, "client", socket)
        helper.stop()
        assertEquals(listOf("output", "input", "close"), calls)
    }

    @Test
    fun `queued old helper direction cannot navigate after service disposal`() {
        val presses = mutableListOf<DpadDirection>()
        val active = mutableListOf<Boolean>()
        val helper = PadHelper(RuntimeEnvironment.getApplication(), presses::add, {}, active::add)
        ReflectionHelpers.setField(helper, "wanted", true)
        ReflectionHelpers.callInstanceMethod<Unit>(helper, "setActive", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, true))
        ReflectionHelpers.callInstanceMethod<Unit>(helper, "handle", ReflectionHelpers.ClassParameter.from(String::class.java, "HAT X -1"))
        helper.dispose()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<DpadDirection>(), presses)
        assertEquals(emptyList<Boolean>(), active)
        helper.start()
        assertEquals(false, ReflectionHelpers.getField<Boolean>(helper, "wanted"))
    }
}
