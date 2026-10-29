package dev.steinhorst.backbuttonhero

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecentsMemoryDeviceTest {
    @Test
    fun installedLauncherMemoryHelperIsCallableFromApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val launcher = context.createPackageContext("com.android.launcher3", Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
            val type = launcher.classLoader.loadClass("com.android.ext.meminfo.MeminfoHelper")
            val helper = type.getMethod("getInstance", Context::class.java).invoke(null, context)
            for (name in listOf("getAvailMemString", "getTotalMemString")) {
                val method = type.getDeclaredMethod(name, Context::class.java).apply { isAccessible = true }
                val value = method.invoke(helper, context) as String
                android.util.Log.i("RecentsMemoryProbe", "$name=$value")
                assertTrue(value.isNotBlank())
            }
        }
    }
}
