package dev.steinhorst.backbuttonhero

import android.view.View
import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.support.Golden
import dev.steinhorst.backbuttonhero.support.ViewTree
import dev.steinhorst.backbuttonhero.support.selectSettingsPage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/** Freezes the app row with its four group buttons and the group modals. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-xxhdpi")
class ExceptionGroupSnapshotTest {
    private val pkg = "org.example.alpha"

    private fun activity(exception: AppException): SettingsActivity {
        Prefs(ApplicationProvider.getApplicationContext<android.content.Context>()).appExceptions = listOf(exception)
        return Robolectric.buildActivity(SettingsActivity::class.java).setup().visible().get().also {
            selectSettingsPage(it, SettingsSection.EXCEPTIONS)
        }
    }

    private fun modal(activity: SettingsActivity, group: ExceptionGroup): View {
        activity.findViewById<View>(android.R.id.content).findViewWithTag<View>("exception_group_${pkg}_${group.name}").performClick()
        return ShadowAlertDialog.getLatestAlertDialog().window!!.decorView
    }

    @Test
    fun `an app row shows the four groups with how much each still handles`() {
        val partial = AppException(pkg, "Alpha Emulator",
            ExceptionControl.ALL and (ExceptionControl.L2.bit or ExceptionControl.LEFT_STICK_UP.bit).inv())
        val row = activity(partial).findViewById<View>(android.R.id.content).findViewWithTag<View>("exception_active_$pkg").parent.parent as View
        Golden.verify("exceptions_app_row", ViewTree.describe(row))
    }

    @Test
    fun `the back modal lists its gestures`() {
        Golden.verify("exceptions_modal_back", ViewTree.describe(modal(activity(AppException(pkg, "Alpha Emulator", ExceptionControl.ALL and ExceptionControl.HOLD.bit.inv())), ExceptionGroup.BACK)))
    }

    @Test
    fun `the sticks modal lists both sticks with every direction and click`() {
        Golden.verify("exceptions_modal_sticks", ViewTree.describe(modal(activity(AppException(pkg, "Alpha Emulator")), ExceptionGroup.STICKS)))
    }

    @Test
    fun `a combo modal explains why it is unreachable when Back is native`() {
        Golden.verify("exceptions_modal_native", ViewTree.describe(modal(activity(AppException(pkg, "Alpha Emulator", 0)), ExceptionGroup.DPAD)))
    }
}
