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
                .put("action", value.action)
                .put("display", value.display)
                .put("target", value.targetOverride ?: JSONObject.NULL)
                .put("focusTarget", value.focusTarget)
                .put("focusIndicator", value.indicator)
                .put("focusDurationMs", value.duration)
                .put("focusPosition", value.position)
                .put("appMode", value.appMode)
                .put("appScreen", value.appScreen)
                .put("appConfirm", value.appConfirm))
        }
        val sticks = JSONObject()
        state.stickTimings.toSortedMap().forEach { (key, value) ->
            sticks.put(key, JSONObject().put("doubleMs", value.doubleMs).put("tapHoldMs", value.tapHoldMs))
        }
        val exceptions = JSONArray()
        state.exceptions.forEach {
            exceptions.put(JSONObject().put("package", it.packageName).put("name", it.name)
                .put("allowed", it.allowed).put("active", it.active))
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("formatVersion", FORMAT_VERSION)
            .put("app", JSONObject().put("version", appVersion).put("versionCode", appVersionCode))
            .put("createdAt", now.toString())
            .put("settings", JSONObject()
                .put("backTimings", JSONObject().put("doubleMs", state.doubleMs).put("tapHoldMs", state.tapHoldMs).put("holdMs", state.holdMs))
                .put("switcher", JSONObject().put("showsAllApps", state.allApps).put("controllerNavigation", state.controller))
                .put("activationShortcut", JSONObject().put("buttons", state.toggleButtons).put("holdMs", state.toggleMs))
                .put("stickTimings", sticks)
                .put("bindings", bindings)
                .put("exceptions", exceptions))
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
        val state = factory.copy(
            doubleMs = back.time("doubleMs", factory.doubleMs, 150, 600),
            tapHoldMs = back.time("tapHoldMs", factory.tapHoldMs, 500, 2000),
            holdMs = back.time("holdMs", factory.holdMs, 500, 2000),
            allApps = switcher?.booleanOrNull("showsAllApps") ?: factory.allApps,
            controller = switcher?.booleanOrNull("controllerNavigation") ?: factory.controller,
            toggleButtons = activation?.stringOrNull("buttons")?.takeIf { it in TOGGLE_VALUES } ?: factory.toggleButtons,
            toggleMs = activation.time("holdMs", factory.toggleMs, 100, 3000),
            bindings = readBindings(settings?.objectOrNull("bindings"), factory.bindings),
            stickTimings = readStickTimings(settings?.objectOrNull("stickTimings"), factory.stickTimings),
            exceptions = readExceptions(settings?.optJSONArray("exceptions")),
        )
        return Result.Loaded(state, info)
    }

    private val TOGGLE_VALUES = setOf(Prefs.TOGGLE_NONE, Prefs.TOGGLE_L1_R1, Prefs.TOGGLE_L2_R2, Prefs.TOGGLE_BOTH, "l3_r3")
    private val SCREENS = setOf(Prefs.DISPLAY_TOP, Prefs.DISPLAY_BOTTOM)
    private val TARGETS = SCREENS + Prefs.TARGET_BOTH
    private val FOCUS_TARGETS = SCREENS + Prefs.FOCUS_TOGGLE
    private val APP_MODES = setOf(Prefs.APP_MINIMIZE, Prefs.APP_FORCE_CLOSE)
    private val APP_SCREENS = TARGETS + Prefs.APP_SCREEN_ASK
    private val POSITIONS = setOf(
        Prefs.FOCUS_POS_TOP_LEFT, Prefs.FOCUS_POS_TOP_CENTER, Prefs.FOCUS_POS_TOP_RIGHT,
        Prefs.FOCUS_POS_CENTER_LEFT, Prefs.FOCUS_POS_CENTER, Prefs.FOCUS_POS_CENTER_RIGHT,
        Prefs.FOCUS_POS_BOTTOM_LEFT, Prefs.FOCUS_POS_BOTTOM_CENTER, Prefs.FOCUS_POS_BOTTOM_RIGHT,
    )

    private fun readBindings(json: JSONObject?, factory: Map<String, BindingSettings>): Map<String, BindingSettings> =
        factory.mapValues { (key, default) ->
            val entry = json?.objectOrNull(key) ?: return@mapValues default
            val action = entry.stringOrNull("action")?.takeIf { it.isEmpty() || it in Prefs.KNOWN_ACTIONS || Shell.isPkg(it) }
            default.copy(
                action = action ?: default.action,
                display = entry.stringOrNull("display")?.takeIf { it in SCREENS } ?: default.display,
                targetOverride = entry.stringOrNull("target")?.takeIf { it in TARGETS },
                focusTarget = entry.stringOrNull("focusTarget")?.takeIf { it in FOCUS_TARGETS } ?: default.focusTarget,
                indicator = entry.booleanOrNull("focusIndicator") ?: default.indicator,
                duration = entry.time("focusDurationMs", default.duration, Prefs.MIN_FOCUS_DURATION_MS, Prefs.MAX_FOCUS_DURATION_MS),
                position = entry.stringOrNull("focusPosition")?.takeIf { it in POSITIONS } ?: default.position,
                appMode = entry.stringOrNull("appMode")?.takeIf { it in APP_MODES } ?: default.appMode,
                appScreen = entry.stringOrNull("appScreen")?.takeIf { it in APP_SCREENS } ?: default.appScreen,
                appConfirm = entry.booleanOrNull("appConfirm") ?: default.appConfirm,
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

    private fun readExceptions(json: JSONArray?): List<AppException> {
        if (json == null) return emptyList()
        val result = LinkedHashMap<String, AppException>()
        for (i in 0 until minOf(json.length(), MAX_EXCEPTIONS)) {
            val entry = json.optJSONObject(i) ?: continue
            val pkg = entry.stringOrNull("package")?.takeIf(Shell::isPkg) ?: continue
            if (pkg in result) continue
            val name = entry.stringOrNull("name")?.filterNot { it.isISOControl() }?.trim()?.take(MAX_NAME)
            val allowed = (entry.intOrNull("allowed") ?: ExceptionControl.ALL) and ExceptionControl.ALL
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
