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

    @Test
    fun `stick reports become presses and releases, and malformed ones are ignored`() {
        val events = mutableListOf<Pair<Stick, DpadDirection?>>()
        val helper = PadHelper(RuntimeEnvironment.getApplication(), {}, {}, {}) { stick, direction -> events += stick to direction }
        ReflectionHelpers.setField(helper, "wanted", true)
        fun line(text: String) = ReflectionHelpers.callInstanceMethod<Unit>(helper, "handle", ReflectionHelpers.ClassParameter.from(String::class.java, text))
        listOf("STICK L U", "STICK L N", "STICK R L", "STICK X U", "STICK L Q", "STICK L").forEach(::line)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            listOf(Stick.LEFT to DpadDirection.UP, Stick.LEFT to null, Stick.RIGHT to DpadDirection.LEFT),
            events,
        )
    }

    @Test
    fun `queued stick report cannot act after service disposal`() {
        val events = mutableListOf<Pair<Stick, DpadDirection?>>()
        val helper = PadHelper(RuntimeEnvironment.getApplication(), {}, {}, {}) { stick, direction -> events += stick to direction }
        ReflectionHelpers.setField(helper, "wanted", true)
        ReflectionHelpers.callInstanceMethod<Unit>(helper, "handle", ReflectionHelpers.ClassParameter.from(String::class.java, "STICK L U"))
        helper.dispose()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<Pair<Stick, DpadDirection?>>(), events)
    }

    @Test
    fun `setSticks keeps the mask and only resends a change`() {
        val helper = PadHelper(RuntimeEnvironment.getApplication(), {}, {}, {})
        helper.setSticks(setOf(Stick.LEFT, Stick.RIGHT))
        assertEquals(3, ReflectionHelpers.getField<Int>(helper, "stickMask"))
        helper.setSticks(setOf(Stick.RIGHT))
        assertEquals(2, ReflectionHelpers.getField<Int>(helper, "stickMask"))
        helper.setSticks(setOf(Stick.RIGHT))
        assertEquals(2, ReflectionHelpers.getField<Int>(helper, "stickMask"))
    }
}
