package dev.steinhorst.backbuttonhero

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

/**
 * The backup file: every binding, the timings, the switches, the activation shortcut and the app
 * exceptions as one JSON document.
 *
 * Reading is built so that no file can break the app. Anything unknown is ignored, anything missing
 * stays at the factory value, and every value is checked on its own: a wrong type, an action this
 * build does not know, a malformed package name or an out-of-range time is replaced by the factory
 * value (or clamped) instead of failing the import. Only a file that is not a backup at all is
 * refused, and refusing changes nothing. The result is an ordinary [SettingsState] that goes
 * through the same preview and atomic write as every other settings change.
 */
object SettingsBackup {
    const val FORMAT = "thor-back-button-hero-backup"
    /** Bumped only when a field changes meaning; added fields need no bump, readers ignore what they do not know. */
    const val FORMAT_VERSION = 1
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_EXCEPTIONS = 1_000
    private const val MAX_NAME = 128

    enum class Rejection { TOO_LARGE, NOT_JSON, NOT_A_BACKUP }

    data class Info(val appVersion: String?, val formatVersion: Int, val createdAt: String?)

    sealed interface Result {
        data class Loaded(val state: SettingsState, val info: Info) : Result
        data class Rejected(val reason: Rejection) : Result
    }

    /** How the backup's app version relates to the installed one; any but [SAME] warrants a hint. */
    enum class Compatibility { SAME, OLDER, NEWER, UNKNOWN }

    fun fileName(appVersion: String, date: LocalDate = LocalDate.now()): String {
        val version = appVersion.filter { it.isLetterOrDigit() || it == '.' || it == '-' }.trim('.', '-').ifEmpty { "unknown" }
        return "back-button-hero-backup_v${version}_$date.json"
    }

    fun compatibility(info: Info, installedVersion: String): Compatibility {
        val saved = info.appVersion?.let(UpdateChecker::parseVersion)
        val installed = UpdateChecker.parseVersion(installedVersion)
        // A newer file format is as much a reason to hint as a newer app: parts of it may be ignored.
        return when {
            info.formatVersion > FORMAT_VERSION -> Compatibility.NEWER
            saved == null || installed == null -> Compatibility.UNKNOWN
            saved < installed -> Compatibility.OLDER
            saved > installed -> Compatibility.NEWER
            else -> Compatibility.SAME
        }
    }

    fun write(state: SettingsState, appVersion: String, appVersionCode: Long, now: Instant = Instant.now()): String {
        val bindings = JSONObject()
        state.bindings.toSortedMap().forEach { (key, value) ->
            bindings.put(key, JSONObject()
                .apply { value.aynOption?.let { put("aynOption", it) } }
                .apply { value.macro?.let { put("macro", it) } }
                .apply { value.gyro?.let { put("gyro", it) } }
                .put("action", value.action)
                .put("display", value.display)
                .put("target", value.targetOverride ?: JSONObject.NULL)
                .put("focusTarget", value.focusTarget)
                .put("focusIndicator", value.indicator)
                .put("focusDurationMs", value.duration)
                .put("focusPosition", value.position)
                .put("appMode", value.appMode)
                .put("appScreen", value.appScreen)
                .put("appConfirm", value.appConfirm)
                .put("switchAppFollow", value.switchAppFollow)
                .put("closeConfirm", value.closeConfirm)
                .put("standbyConfirm", value.standbyConfirm)
                .put("recordMic", value.recordMic)
                .put("recordQuality", value.recordQuality)
                .put("recordIndicator", value.recordIndicator)
                .put("recordPosition", value.recordPosition)
                .put("recordPtt", value.recordPtt)
                .put("recordPttKey", value.recordPttKey)
                .put("recordDuck", value.recordDuck))
        }
        fun sticks(timings: Map<String, StickTimings>) = JSONObject().apply {
            timings.toSortedMap().forEach { (key, value) ->
                put(key, JSONObject().put("doubleMs", value.doubleMs).put("tapHoldMs", value.tapHoldMs))
            }
        }
        val exceptions = JSONArray()
        state.exceptions.forEach {
            exceptions.put(JSONObject().put("package", it.packageName).put("name", it.name)
                .put("allowed", it.allowed and ExceptionControl.BEFORE_STICKS)
                .put("sticksAllowed", (it.allowed shr 12) and ExceptionControl.STICK_BITS)
                .put("buttonsAllowed", (it.allowed shr ExceptionControl.BUTTON_SHIFT) and ExceptionControl.BUTTON_BITS)
                .put("active", it.active))
        }
        val macros = JSONArray()
        state.macros.forEach { macros.put(JSONObject().put("id", it.id).put("name", it.name).put("steps", it.macro.encode())) }
        return JSONObject()
            .put("format", FORMAT)
            .put("formatVersion", FORMAT_VERSION)
            .put("app", JSONObject().put("version", appVersion).put("versionCode", appVersionCode))
            .put("createdAt", now.toString())
            .put("settings", JSONObject()
                .put("backTimings", JSONObject().put("doubleMs", state.doubleMs).put("tapHoldMs", state.tapHoldMs).put("holdMs", state.holdMs))
                .put("switcher", JSONObject().put("showsAllApps", state.allApps).put("controllerNavigation", state.controller))
                .put("homeAsBack", state.homeAsBack)
                .put("dualMode", JSONObject().put("enabled", state.dualMode).put("seeded", state.dualSeeded)
                    .put("timings", JSONObject().put("doubleMs", state.homeDoubleMs).put("tapHoldMs", state.homeTapHoldMs)
                        .put("holdMs", state.homeHoldMs))
                    .put("stickTimings", sticks(state.homeStickTimings)))
                .put("activationShortcut", JSONObject().put("buttons", state.toggleButtons).put("holdMs", state.toggleMs))
                .put("stickTimings", sticks(state.stickTimings))
                .put("bindings", bindings)
                .put("exceptions", exceptions)
                .put("macros", macros))
            .toString(2)
    }

    fun read(text: String): Result {
        if (text.length > MAX_BYTES) return Result.Rejected(Rejection.TOO_LARGE)
        val root = try { JSONObject(text) } catch (_: JSONException) { return Result.Rejected(Rejection.NOT_JSON) }
        if (root.opt("format") != FORMAT) return Result.Rejected(Rejection.NOT_A_BACKUP)
        val app = root.objectOrNull("app")
        val info = Info(
            appVersion = app?.stringOrNull("version")?.take(40),
            formatVersion = root.intOrNull("formatVersion") ?: FORMAT_VERSION,
            createdAt = root.stringOrNull("createdAt")?.take(40),
        )
        val factory = SettingsState()
        val settings = root.objectOrNull("settings")
        val back = settings?.objectOrNull("backTimings")
        val switcher = settings?.objectOrNull("switcher")
        val activation = settings?.objectOrNull("activationShortcut")
        val macros = readMacros(settings?.optJSONArray("macros"))
        val dual = settings?.objectOrNull("dualMode")
        val homeTimings = dual?.objectOrNull("timings")
        val dualMode = dual?.booleanOrNull("enabled") ?: factory.dualMode
        val state = factory.copy(
            doubleMs = back.time("doubleMs", factory.doubleMs, 150, 600),
            tapHoldMs = back.time("tapHoldMs", factory.tapHoldMs, 500, 2000),
            holdMs = back.time("holdMs", factory.holdMs, 500, 2000),
            allApps = switcher?.booleanOrNull("showsAllApps") ?: factory.allApps,
            controller = switcher?.booleanOrNull("controllerNavigation") ?: factory.controller,
            // The two Home modes exclude each other; a file claiming both keeps dual mode, the newer one.
            homeAsBack = (settings?.booleanOrNull("homeAsBack") ?: factory.homeAsBack) && !dualMode,
            dualMode = dualMode,
            // A dual-mode Home layer is already a copy; switching on again must not overwrite it.
            dualSeeded = (dual?.booleanOrNull("seeded") ?: factory.dualSeeded) || dualMode,
            homeDoubleMs = homeTimings.time("doubleMs", factory.homeDoubleMs, 150, 600),
            homeTapHoldMs = homeTimings.time("tapHoldMs", factory.homeTapHoldMs, 500, 2000),
            homeHoldMs = homeTimings.time("holdMs", factory.homeHoldMs, 500, 2000),
            homeStickTimings = readStickTimings(dual?.objectOrNull("stickTimings"), factory.homeStickTimings),
            toggleButtons = activation?.stringOrNull("buttons")?.takeIf { it in TOGGLE_VALUES } ?: factory.toggleButtons,
            toggleMs = activation.time("holdMs", factory.toggleMs, 100, 3000),
            bindings = readBindings(settings?.objectOrNull("bindings"), factory.bindings, macros),
            stickTimings = readStickTimings(settings?.objectOrNull("stickTimings"), factory.stickTimings),
            exceptions = readExceptions(settings?.optJSONArray("exceptions")),
            macros = macros,
        )
        return Result.Loaded(state, info)
    }

    private val TOGGLE_VALUES = setOf(Prefs.TOGGLE_NONE, Prefs.TOGGLE_L1_R1, Prefs.TOGGLE_L2_R2, Prefs.TOGGLE_BOTH, "l3_r3",
        Prefs.TOGGLE_START_SELECT, Prefs.TOGGLE_BACK_HOME)
    private val SCREENS = setOf(Prefs.DISPLAY_TOP, Prefs.DISPLAY_BOTTOM)
    private val TARGETS = SCREENS + Prefs.TARGET_BOTH
    private val FOCUS_TARGETS = SCREENS + Prefs.FOCUS_TOGGLE + Prefs.FOCUS_MODAL
    private val APP_MODES = setOf(Prefs.APP_MINIMIZE, Prefs.APP_FORCE_CLOSE, Prefs.APP_RESTART)
    private val APP_SCREENS = TARGETS + Prefs.APP_SCREEN_ASK
    private val RECORD_QUALITIES = setOf(Prefs.RECORD_SMALL, Prefs.RECORD_MEDIUM, Prefs.RECORD_LARGE)
    private val DUCKS = setOf(Prefs.DUCK_OFF, Prefs.DUCK_VIDEO, Prefs.DUCK_DEVICE)
    private val POSITIONS = setOf(
        Prefs.FOCUS_POS_TOP_LEFT, Prefs.FOCUS_POS_TOP_CENTER, Prefs.FOCUS_POS_TOP_RIGHT,
        Prefs.FOCUS_POS_CENTER_LEFT, Prefs.FOCUS_POS_CENTER, Prefs.FOCUS_POS_CENTER_RIGHT,
        Prefs.FOCUS_POS_BOTTOM_LEFT, Prefs.FOCUS_POS_BOTTOM_CENTER, Prefs.FOCUS_POS_BOTTOM_RIGHT,
    )

    private fun readBindings(json: JSONObject?, factory: Map<String, BindingSettings>, macros: List<SavedMacro>): Map<String, BindingSettings> =
        factory.mapValues { (key, default) ->
            val entry = json?.objectOrNull(key) ?: return@mapValues default
            val action = entry.stringOrNull("action")?.takeIf { it.isEmpty() || it in Prefs.KNOWN_ACTIONS || Shell.isPkg(it) }
                // Back's own gestures cannot play macros: the macro would wait for the very Back it started with.
                ?.takeIf { it != Prefs.SHOULDER_MACRO || Prefs.ALL_MACRO_BUTTONS.any { button -> button.prefKey == key } }
            default.copy(
                action = action ?: default.action,
                display = entry.stringOrNull("display")?.takeIf { it in SCREENS || it == Prefs.APP_SCREEN_ASK } ?: default.display,
                targetOverride = entry.stringOrNull("target")?.takeIf { it in TARGETS },
                focusTarget = entry.stringOrNull("focusTarget")?.takeIf { it in FOCUS_TARGETS } ?: default.focusTarget,
                indicator = entry.booleanOrNull("focusIndicator") ?: default.indicator,
                duration = entry.time("focusDurationMs", default.duration, Prefs.MIN_FOCUS_DURATION_MS, Prefs.MAX_FOCUS_DURATION_MS),
                position = entry.stringOrNull("focusPosition")?.takeIf { it in POSITIONS } ?: default.position,
                appMode = entry.stringOrNull("appMode")?.takeIf { it in APP_MODES } ?: default.appMode,
                appScreen = entry.stringOrNull("appScreen")?.takeIf { it in APP_SCREENS } ?: default.appScreen,
                appConfirm = entry.booleanOrNull("appConfirm") ?: default.appConfirm,
                switchAppFollow = entry.booleanOrNull("switchAppFollow") ?: default.switchAppFollow,
                closeConfirm = entry.booleanOrNull("closeConfirm") ?: default.closeConfirm,
                standbyConfirm = entry.booleanOrNull("standbyConfirm") ?: default.standbyConfirm,
                recordMic = entry.booleanOrNull("recordMic") ?: default.recordMic,
                recordQuality = entry.stringOrNull("recordQuality")?.takeIf { it in RECORD_QUALITIES } ?: default.recordQuality,
                recordIndicator = entry.booleanOrNull("recordIndicator") ?: default.recordIndicator,
                recordPosition = entry.stringOrNull("recordPosition")?.takeIf { it in POSITIONS } ?: default.recordPosition,
                recordPtt = entry.booleanOrNull("recordPtt") ?: default.recordPtt,
                recordPttKey = entry.intOrNull("recordPttKey")?.takeIf { it == 0 || PushToTalk.isAllowed(it) } ?: default.recordPttKey,
                recordDuck = entry.stringOrNull("recordDuck")?.takeIf { it in DUCKS } ?: default.recordDuck,
                aynOption = entry.stringOrNull("aynOption")?.takeIf { it in Ayn.OPTION_KEYS },
                // Only a macro the backup's own library holds; anything else would point at nothing.
                macro = entry.stringOrNull("macro")?.takeIf { id -> macros.any { it.id == id } },
                // Read field by field like every other value; a setup that reads as the defaults is none.
                gyro = GyroSettings.canonical(entry.stringOrNull("gyro")),
            )
        }

    private fun readStickTimings(json: JSONObject?, factory: Map<String, StickTimings>): Map<String, StickTimings> =
        factory.mapValues { (key, default) ->
            val entry = json?.objectOrNull(key) ?: return@mapValues default
            StickTimings(
                doubleMs = entry.time("doubleMs", default.doubleMs, 150, 600),
                tapHoldMs = entry.time("tapHoldMs", default.tapHoldMs, 500, 2000),
            )
        }

    private fun readMacros(json: JSONArray?): List<SavedMacro> {
        if (json == null) return emptyList()
        val result = LinkedHashMap<String, SavedMacro>()
        for (i in 0 until json.length()) {
            val entry = json.optJSONObject(i) ?: continue
            val id = entry.stringOrNull("id")?.takeIf(SavedMacro::isId) ?: continue
            val macro = entry.stringOrNull("steps")?.let(Macro::decode) ?: continue
            result.getOrPut(id) { SavedMacro(id, SavedMacro.cleanName(entry.stringOrNull("name"), id), macro) }
        }
        return result.values.toList()
    }

    private fun readExceptions(json: JSONArray?): List<AppException> {
        if (json == null) return emptyList()
        val result = LinkedHashMap<String, AppException>()
        for (i in 0 until minOf(json.length(), MAX_EXCEPTIONS)) {
            val entry = json.optJSONObject(i) ?: continue
            val pkg = entry.stringOrNull("package")?.takeIf(Shell::isPkg) ?: continue
            if (pkg in result) continue
            val name = entry.stringOrNull("name")?.filterNot { it.isISOControl() }?.trim()?.take(MAX_NAME)
            // Backups from before the sticks or the buttons lack those fields; their shortcuts were active then.
            fun bits(key: String, all: Long) = (entry.intOrNull(key)?.toLong() ?: all) and all
            val allowed = bits("allowed", ExceptionControl.BEFORE_STICKS) or
                (bits("sticksAllowed", ExceptionControl.STICK_BITS) shl 12) or
                (bits("buttonsAllowed", ExceptionControl.BUTTON_BITS) shl ExceptionControl.BUTTON_SHIFT)
            result[pkg] = AppException(pkg, name?.ifEmpty { null } ?: pkg, allowed, entry.booleanOrNull("active") ?: true)
        }
        return result.values.toList()
    }

    // JSONObject's own getters coerce ("12" becomes 12, 1 becomes true); only the real types count here.
    private fun JSONObject.objectOrNull(key: String): JSONObject? = opt(key) as? JSONObject
    private fun JSONObject.stringOrNull(key: String): String? = opt(key) as? String
    private fun JSONObject.booleanOrNull(key: String): Boolean? = opt(key) as? Boolean
    private fun JSONObject.intOrNull(key: String): Int? = (opt(key) as? Number)?.takeIf { it.toDouble() == it.toLong().toDouble() }?.toInt()
    private fun JSONObject?.time(key: String, default: Long, min: Long, max: Long): Long =
        ((this?.opt(key) as? Number)?.toLong() ?: default).coerceIn(min, max)
}
