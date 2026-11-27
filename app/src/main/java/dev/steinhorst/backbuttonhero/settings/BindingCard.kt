package dev.steinhorst.backbuttonhero.settings

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.actions.ActionGroups
import dev.steinhorst.backbuttonhero.actions.Ayn
import dev.steinhorst.backbuttonhero.input.AndroidButton
import dev.steinhorst.backbuttonhero.input.ComboButton
import dev.steinhorst.backbuttonhero.input.HomeLayerButton
import dev.steinhorst.backbuttonhero.prefs.Prefs

/**
 * The card of one binding (one button in one layer): its action row and every option row below it.
 * Option rows are built the first time their action is chosen and only hidden afterwards, so a card
 * that never had an option looks as it always did. [update] re-reads the binding from [prefs] and
 * changes the existing views; the dialogs that choose values stay with the activity behind [Host].
 */
internal class BindingCard(
    page: SettingsPage,
    val button: ComboButton,
    titleRes: Int,
    extra: (SettingsPage.Card.(ComboButton) -> View)?,
    private val activity: Activity,
    private val prefs: Prefs,
    private val describer: BindingDescriber,
    private val host: Host,
) {
    /** The choosers a card opens; each one writes its value and lets the card update itself. */
    interface Host {
        fun chooseAction(card: BindingCard)
        fun chooseDisplay(card: BindingCard)
        fun chooseTarget(card: BindingCard)
        fun chooseOption(card: BindingCard)
        fun choosePosition(card: BindingCard, record: Boolean)
        fun choosePushToTalkKey(card: BindingCard)
        fun chooseRecordDuck(card: BindingCard)
        fun chooseRecordQuality(card: BindingCard)
        fun chooseAppControl(card: BindingCard, mode: Boolean)
        fun openMacroLibrary(card: BindingCard)
        fun openGyroSettings(card: BindingCard)
        /** A switch on the card changed a value other rows depend on. */
        fun changed(card: BindingCard)
    }

    class RecordOptions(
        val group: View,
        val pushToTalk: SettingsPage.ToggleHandle,
        val pushToTalkKey: SettingsPage.ChooserHandle,
        val duck: SettingsPage.ChooserHandle,
        val quality: SettingsPage.ChooserHandle,
        val indicator: SettingsPage.ChooserHandle,
    )

    val card: SettingsPage.Card = page.card {}
    val actionRow: SettingsPage.ChooserHandle =
        card.chooserRow(titleRes, describer.describeShoulderAction(button)) { host.chooseAction(this@BindingCard) }
    /** A timing slider that only means something once the row has an action. */
    val timingOptions: View? = extra?.let { card.it(button) }
    val display: SettingsPage.ChooserHandle
    val displayOptions: View
    val target: SettingsPage.ChooserHandle
    val targetOptions: View
    init {
        var builtDisplay: SettingsPage.ChooserHandle? = null
        displayOptions = card.subOptions {
            builtDisplay = chooserRow(R.string.level_target_title, describer.describeShoulderDisplay(button)) { host.chooseDisplay(this@BindingCard) }
        }
        display = checkNotNull(builtDisplay)
        var builtTarget: SettingsPage.ChooserHandle? = null
        targetOptions = card.subOptions {
            builtTarget = chooserRow(R.string.level_target_title, describer.describeLevelTarget(button)) { host.chooseTarget(this@BindingCard) }
        }
        target = checkNotNull(builtTarget)
    }
    /** The Home column's label names its layer, so the dialogs and screen readers say which binding it is. */
    val label: String = if (button is HomeLayerButton) activity.getString(R.string.settings_home_binding_label, activity.getString(titleRes))
        else activity.getString(titleRes)

    var focusOptions: SettingsPage.FocusOptionsHandle? = null
        private set
    /** The choice below a grouped action, built when first needed so unassigned rows stay as they were. */
    var option: SettingsPage.ChooserHandle? = null
        private set
    private var optionOptions: View? = null
    /** The "apps of both screens" switch, shown wherever the app switcher is the action. */
    private var switcherOptions: View? = null
    private var switchAppOptions: View? = null
    private var closeAppsOptions: View? = null
    private var dndOptions: View? = null
    private var standbyOptions: View? = null
    private var recordOptions: RecordOptions? = null
    private var appOptions: SettingsPage.AppControlOptionsHandle? = null
    private var macroOptions: View? = null
    private var macroLibrary: SettingsPage.ChooserHandle? = null
    private var gyroOptions: View? = null
    private var gyroSetup: SettingsPage.ChooserHandle? = null
    val position: SettingsPage.ChooserHandle get() = checkNotNull(focusOptions).position

    fun update() {
        val action = prefs.shoulderAction(button)
        val described = describer.describeShoulderAction(button)
        // The chosen action stands out from the options under it; "Not set" stays plain.
        actionRow.value.text = if (action.isEmpty()) described else activity.getString(R.string.shoulder_action_selected, described)
        actionRow.value.typeface = if (action.isEmpty()) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
        display.row.visibility = if (Prefs.launchesApp(action) || action == Prefs.SHOULDER_HOME) View.VISIBLE else View.GONE
        display.value.text = describer.describeShoulderDisplay(button)
        display.row.contentDescription = activity.getString(R.string.level_target_accessibility, label, display.value.text)
        val hasOption = Ayn.groupOf(action) != null || ActionGroups.groupOf(action) != null
        val hasTarget = Prefs.hasScreenTarget(action)
        val focus = action == Prefs.SHOULDER_CONTROLLER_FOCUS
        target.row.visibility = if (hasTarget && !focus) View.VISIBLE else View.GONE
        displayOptions.visibility = display.row.visibility
        timingOptions?.visibility = if (action.isEmpty()) View.GONE else View.VISIBLE
        val switcher = action == Prefs.SHOULDER_SWITCHER
        if (switcher && switcherOptions == null) {
            switcherOptions = card.subOptions {
                toggleRow(R.string.switcher_all_apps, R.string.switcher_all_apps_hint, prefs.switcherShowsAllApps, readValue = { prefs.switcherShowsAllApps }) {
                    prefs.switcherShowsAllApps = it
                }
            }
        }
        switcherOptions?.visibility = if (switcher) View.VISIBLE else View.GONE
        targetOptions.visibility = target.row.visibility
        val switchApp = action == Prefs.SHOULDER_SWITCH_APP
        val appControl = action == Prefs.SHOULDER_APP_CONTROL
        actionRow.row.setPadding(actionRow.row.paddingLeft, actionRow.row.paddingTop, actionRow.row.paddingRight,
            activity.dp(if (hasTarget || hasOption || appControl || switchApp || display.row.visibility == View.VISIBLE) 0 else 10))
        if (hasOption && option == null) {
            lateinit var created: SettingsPage.ChooserHandle
            val options = card.subOptions {
                created = chooserRow(R.string.ayn_option_title, describer.describeOption(button)) { host.chooseOption(this@BindingCard) }
            }
            // Built last, but it belongs right above the screen 
            (options.parent as ViewGroup).let { parent ->
                parent.removeView(options)
                parent.addView(options, parent.indexOfChild(targetOptions))
            }
            option = created
            optionOptions = options
        }
        option?.let { option ->
            option.row.visibility = if (hasOption) View.VISIBLE else View.GONE
            optionOptions?.visibility = option.row.visibility
            option.value.text = describer.describeOption(button)
            val screen = describer.isScreenOption(action)
            option.title.setText(if (screen) R.string.level_target_title else R.string.ayn_option_title)
            option.row.contentDescription =
                activity.getString(if (screen) R.string.level_target_accessibility else R.string.ayn_option_accessibility, label, option.value.text)
        }
        target.value.text = describer.describeLevelTarget(button)
        target.row.contentDescription = activity.getString(R.string.level_target_accessibility, label, target.value.text)
        if (focus && focusOptions == null) {
            focusOptions = card.focusOptions(
                describer.describeFocusTarget(button), describer.describeFocusPosition(button),
                { host.chooseTarget(this@BindingCard) }, { host.choosePosition(this@BindingCard, record = false) },
            )
        }
        if (switchApp && switchAppOptions == null) {
            switchAppOptions = card.subOptions {
                toggleRow(R.string.switch_app_follow_focus, null, prefs.switchAppFollowFocus(button),
                    readValue = { prefs.switchAppFollowFocus(button) }) {
                    prefs.setSwitchAppFollowFocus(button, it)
                }
            }
        }
        switchAppOptions?.visibility = if (switchApp) View.VISIBLE else View.GONE
        val closeApps = Prefs.isCloseApps(action)
        if (closeApps && closeAppsOptions == null) {
            closeAppsOptions = card.subOptions {
                toggleRow(R.string.app_control_confirm_title, null, prefs.closeConfirm(button),
                    readValue = { prefs.closeConfirm(button) }) {
                    prefs.setCloseConfirm(button, it)
                }
            }
        }
        closeAppsOptions?.visibility = if (closeApps) View.VISIBLE else View.GONE
        val dnd = action == Ayn.Toggle.DO_NOT_DISTURB.action
        if (dnd && dndOptions == null) {
            dndOptions = card.subOptions {
                toggleRow(R.string.dnd_hide_popups_title, R.string.dnd_hide_popups_summary, prefs.dndHidePopups(button),
                    readValue = { prefs.dndHidePopups(button) }) {
                    prefs.setDndHidePopups(button, it)
                }
            }
        }
        dndOptions?.visibility = if (dnd) View.VISIBLE else View.GONE
        val standby = action == Prefs.SHOULDER_STANDBY
        if (standby && standbyOptions == null) {
            standbyOptions = card.subOptions {
                toggleRow(R.string.standby_confirm_title, null, prefs.standbyConfirm(button),
                    readValue = { prefs.standbyConfirm(button) }) {
                    prefs.setStandbyConfirm(button, it)
                }
            }
        }
        standbyOptions?.visibility = if (standby) View.VISIBLE else View.GONE
        val record = action == Prefs.SHOULDER_RECORD_VIDEO
        if (record && recordOptions == null) {
            lateinit var pushToTalk: SettingsPage.ToggleHandle
            lateinit var pushToTalkKey: SettingsPage.ChooserHandle
            lateinit var duck: SettingsPage.ChooserHandle
            lateinit var quality: SettingsPage.ChooserHandle
            lateinit var indicator: SettingsPage.ChooserHandle
            val group = card.subOptions {
                toggleRow(R.string.record_mic_title, null, prefs.recordMic(button),
                    readValue = { prefs.recordMic(button) }) {
                    prefs.setRecordMic(button, it)
                    host.changed(this@BindingCard)
                }
                pushToTalk = toggleRow(R.string.record_ptt_title, null, prefs.recordPtt(button),
                    readValue = { prefs.recordPtt(button) }) {
                    prefs.setRecordPtt(button, it)
                    host.changed(this@BindingCard)
                }
                pushToTalkKey = chooserRow(R.string.record_ptt_key_title, describer.describePushToTalkKey(prefs.recordPttKey(button))) {
                    host.choosePushToTalkKey(this@BindingCard)
                }
                duck = chooserRow(R.string.record_duck_title, describer.describeRecordDuck(button)) { host.chooseRecordDuck(this@BindingCard) }
                quality = chooserRow(R.string.record_quality_title, describer.describeRecordQuality(button)) { host.chooseRecordQuality(this@BindingCard) }
                indicator = chooserRow(R.string.record_indicator_row, describer.describeRecordIndicator(button)) {
                    host.choosePosition(this@BindingCard, record = true)
                }
            }
            recordOptions = RecordOptions(group, pushToTalk, pushToTalkKey, duck, quality, indicator)
        }
        recordOptions?.let { options ->
            options.group.visibility = if (record) View.VISIBLE else View.GONE
            // Push to talk only exists with the microphone, its button and fade only with push to talk.
            val mic = prefs.recordMic(button)
            val talk = mic && prefs.recordPtt(button)
            fun show(view: View, visible: Boolean) {
                val state = if (visible) View.VISIBLE else View.GONE
                view.visibility = state
                dividerBefore(view)?.visibility = state
            }
            show(options.pushToTalk.row, mic)
            show(options.pushToTalkKey.row, talk)
            show(options.duck.row, talk)
            options.pushToTalkKey.value.text = describer.describePushToTalkKey(prefs.recordPttKey(button))
            options.pushToTalkKey.row.contentDescription =
                activity.getString(R.string.record_ptt_key_accessibility, label, options.pushToTalkKey.value.text)
            options.duck.value.text = describer.describeRecordDuck(button)
            options.duck.row.contentDescription = activity.getString(R.string.record_duck_accessibility, label, options.duck.value.text)
            options.quality.value.text = describer.describeRecordQuality(button)
            options.quality.row.contentDescription = activity.getString(R.string.record_quality_accessibility, label, options.quality.value.text)
            options.indicator.value.text = describer.describeRecordIndicator(button)
            options.indicator.row.contentDescription =
                activity.getString(R.string.record_indicator_accessibility, label, options.indicator.value.text)
        }
        val macroAction = action == Prefs.SHOULDER_MACRO
        if (macroAction && macroOptions == null) {
            lateinit var library: SettingsPage.ChooserHandle
            // One row: the list both picks a saved macro and records a new one, and its value names the one this hotkey plays.
            macroOptions = card.subOptions {
                library = chooserRow(R.string.macro_library_title, describer.describeMacro(prefs.savedMacro(button))) {
                    host.openMacroLibrary(this@BindingCard)
                }
            }
            macroLibrary = library
        }
        macroOptions?.visibility = if (macroAction) View.VISIBLE else View.GONE
        macroLibrary?.let { library ->
            library.value.text = describer.describeMacro(prefs.savedMacro(button))
            library.row.contentDescription = activity.getString(R.string.macro_library_accessibility, label, library.value.text)
        }
        val gyroAction = action == Prefs.SHOULDER_GYRO
        if (gyroAction && gyroOptions == null) {
            lateinit var setup: SettingsPage.ChooserHandle
            gyroOptions = card.subOptions {
                setup = chooserRow(R.string.gyro_setup_title, describer.describeGyro(prefs.gyro(button))) { host.openGyroSettings(this@BindingCard) }
            }
            gyroSetup = setup
        }
        gyroOptions?.visibility = if (gyroAction) View.VISIBLE else View.GONE
        gyroSetup?.let { setup ->
            setup.value.text = describer.describeGyro(prefs.gyro(button))
            setup.row.contentDescription = activity.getString(R.string.gyro_setup_accessibility, label, setup.value.text)
        }
        if (appControl && appOptions == null) {
            appOptions = card.appControlOptions(
                describer.describeAppMode(button), describer.describeAppScreen(button), prefs.appConfirm(button),
                { host.chooseAppControl(this@BindingCard, mode = true) }, { host.chooseAppControl(this@BindingCard, mode = false) },
                { prefs.setAppConfirm(button, it) },
            )
        }
        appOptions?.let { options ->
            val close = prefs.appMode(button) != Prefs.APP_MINIMIZE
            options.row.visibility = if (appControl) View.VISIBLE else View.GONE
            options.mode.value.text = describer.describeAppMode(button)
            options.screen.value.text = describer.describeAppScreen(button)
            options.mode.row.contentDescription = activity.getString(R.string.app_control_mode_accessibility, label, options.mode.value.text)
            options.screen.row.contentDescription = activity.getString(R.string.level_target_accessibility, label, options.screen.value.text)
            // Ask already is a confirmation; minimizing destroys nothing.
            fun show(view: View, visible: Boolean) {
                val state = if (visible) View.VISIBLE else View.GONE
                view.visibility = state
                dividerBefore(view)?.visibility = state
            }
            show(options.confirm.row, appControl && close && prefs.appScreen(button) != Prefs.APP_SCREEN_ASK)
            show(options.warning.parent as View, appControl && close)
        }
        focusOptions?.let { options ->
            options.row.visibility = if (focus) View.VISIBLE else View.GONE
            options.target.value.text = describer.describeFocusTarget(button)
            options.target.row.contentDescription = activity.getString(R.string.level_target_accessibility, label, options.target.value.text)
            options.position.value.text = describer.describeFocusPosition(button)
            options.position.row.contentDescription = activity.getString(R.string.focus_position_accessibility, label, options.position.value.text)
        }
        // With Home standing in for Back, or Back + Home as the activation shortcut, Back + Home cannot
        // run its own shortcut; the binding stays stored.
        val toggleBlocks = button == AndroidButton.HOME && prefs.toggleButtons == Prefs.TOGGLE_BACK_HOME
        val dualBlocks = button == AndroidButton.HOME && prefs.dualMode
        val blocked = toggleBlocks || dualBlocks || button == AndroidButton.HOME && prefs.homeAsBack
        if (blocked) actionRow.value.text = activity.getString(when {
            toggleBlocks -> R.string.toggle_combo_blocked
            dualBlocks -> R.string.dual_mode_combo_blocked
            else -> R.string.home_as_back_combo_blocked
        })
        (actionRow.row.parent as View).let { card ->
            card.alpha = if (blocked) 0.5f else 1f
            setTreeEnabled(card, !blocked)
        }
        actionRow.row.contentDescription =
            activity.getString(R.string.shoulder_action_accessibility, label, if (blocked) actionRow.value.text else described)
    }

    private fun setTreeEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) for (i in 0 until view.childCount) setTreeEnabled(view.getChildAt(i), enabled)
    }

    private fun dividerBefore(row: View): View? =
        (row.parent as? ViewGroup)?.let { card -> card.getChildAt(card.indexOfChild(row) - 1) }
}
