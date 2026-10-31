package dev.steinhorst.backbuttonhero

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.view.Display
import org.json.JSONObject
import java.util.ArrayDeque

/** Serializes capture/hide/show so a card always belongs to the panel, never the app underneath. */
class BottomPanels(
    private val context: Context,
    private val isCovered: (Int) -> Boolean,
    private val capture: (PanelWindow, (Bitmap?) -> Unit) -> Unit,
    createConnection: (() -> Unit) -> PanelTransport = { lost -> PanelConnection(context, lost) },
    private val onChanged: () -> Unit,
) {
    private var windows = emptyList<PanelWindow>()
    private val previews = mutableMapOf<Int, Bitmap>()
    private var suppressed = false
    // Covering panels Recents hid only to be seen; they return unless the user chose something there.
    private val switcherHidden = mutableSetOf<Int>()
    private var closed = false
    private var busy = false
    private val pending = ArrayDeque<() -> Unit>()
    private val connection = createConnection {
        windows = emptyList()
        previews.clear()
        suppressed = false
        switcherHidden.clear()
        onChanged()
    }

    val hasPanels: Boolean get() = windows.isNotEmpty()

    /** A covering panel is hidden, so its owner's own key (AYN's button) must bring it back instead. */
    val hasHiddenCovering: Boolean get() = windows.any { it.covering && it.hidden }

    fun preventsSwap(topPackage: String?, bottomPackage: String?): Boolean =
        windows.any { it.packageName == topPackage || it.packageName == bottomPackage }

    fun cards(displayId: Int): List<RunningApp> = windows.filter { it.displayId == displayId }.map { panel ->
        val info = runCatching { context.packageManager.getApplicationInfo(panel.packageName, 0) }.getOrNull()
        val name = info?.loadLabel(context.packageManager)?.toString() ?: panel.packageName
        RunningApp(panel.packageName, context.getString(R.string.bottom_panel_label, name),
            info?.loadIcon(context.packageManager), previews[panel.id], panel)
    }

    fun refresh(display: Display?, then: () -> Unit = {}) = enqueue {
        discover(display) {
            // A covering panel only appears when the user opens it with its own button; never undo that.
            if (suppressed) hideVisible(includeCovering = false) { finish(then) } else finish(then)
        }
    }

    fun prepareSwitcher(display: Display, then: () -> Unit) = enqueue {
        switcherHidden.clear()
        discover(display) {
            if (suppressed) hideVisible { finish(then) }
            else hideCovering {
                val front = windows.firstOrNull { !it.hidden }
                if (front == null) finish(then) else capture(front) { bitmap ->
                    if (closed) return@capture
                    if (bitmap != null) previews[front.id] = bitmap
                    finish(then)
                }
            }
        }
    }

    fun prepareLaunch(display: Display, then: () -> Unit) = enqueue {
        suppressed = true
        discover(display) { hideVisible { finish(then) } }
    }

    /** Recents closed. Without a choice, the covering panels it hid come back as if never covered. */
    fun switcherClosed(chose: Boolean) = enqueue {
        val ids = switcherHidden.toList()
        switcherHidden.clear()
        if (chose) finish {} else showAll(ids) { finish {} }
    }

    /** AYN's button always returns its panel, wherever the user is. */
    fun showCovering() = enqueue {
        switcherHidden.clear()
        // Like a card selection: a later refresh must not hide the panel the user just asked for.
        showAll(windows.filter { it.covering && it.hidden }.map { it.id }) { suppressed = false; finish {} }
    }

    /** AYN closes its own panel through [press]; Recents closing afterwards must not reopen it. */
    fun closeCovering(press: (() -> Unit) -> Unit) = enqueue {
        val covering = windows.filter { it.covering }.map { it.id }
        if (covering.isEmpty()) return@enqueue finish {}
        switcherHidden.removeAll(covering.toSet())
        press { finish {} }
    }

    fun show(id: Int, then: (Boolean) -> Unit) = enqueue {
        hideVisible {
            if (windows.none { it.id == id }) {
                finish { then(false) }
            } else command("show", id) { result ->
                if (result != null) suppressed = false
                finish { then(result != null) }
            }
        }
    }

    fun close() {
        closed = true
        pending.clear()
        connection.close()
        windows = emptyList()
        previews.clear()
    }

    private fun discover(display: Display?, then: () -> Unit) {
        if (closed) return
        if (display == null) {
            if (windows.isEmpty()) then() else command("restore") { then() }
            return
        }
        if (windows.isEmpty() && !isCovered(display.displayId)) { then(); return }
        val size = Point().also { display.getRealSize(it) }
        connection.request(JSONObject().put("op", "list").put("display", display.displayId)
            .put("width", size.x).put("height", size.y)) { result ->
            if (result != null) update(result)
            then()
        }
    }

    private fun hideVisible(includeCovering: Boolean = true, then: () -> Unit) {
        if (closed) return
        val front = windows.firstOrNull { !it.hidden && (includeCovering || !it.covering) } ?: return then()
        capture(front) { bitmap ->
            if (closed) return@capture
            if (bitmap != null) previews[front.id] = bitmap
            command("hide", front.id) { result ->
                if (result == null) then() else hideVisible(includeCovering, then)
            }
        }
    }

    private fun hideCovering(then: () -> Unit) {
        if (closed) return
        val panel = windows.firstOrNull { it.covering && !it.hidden } ?: return then()
        capture(panel) { bitmap ->
            if (closed) return@capture
            if (bitmap != null) previews[panel.id] = bitmap
            command("hide", panel.id) { result ->
                if (result == null) then() else { switcherHidden += panel.id; hideCovering(then) }
            }
        }
    }

    private fun showAll(ids: List<Int>, then: () -> Unit) {
        if (closed) return
        val id = ids.firstOrNull { id -> windows.any { it.id == id && it.hidden } } ?: return then()
        command("show", id) { result -> if (result == null) then() else showAll(ids - id, then) }
    }

    private fun command(op: String, id: Int? = null, then: (List<PanelWindow>?) -> Unit) {
        val command = JSONObject().put("op", op)
        id?.let { command.put("id", it) }
        connection.request(command) { result ->
            if (result != null) update(result)
            then(result)
        }
    }

    private fun update(result: List<PanelWindow>) {
        windows = result
        previews.keys.retainAll(result.map { it.id }.toSet())
        onChanged()
    }

    private fun enqueue(operation: () -> Unit) {
        if (closed) return
        if (busy) pending.addLast(operation) else { busy = true; operation() }
    }

    private fun finish(then: () -> Unit) {
        if (closed) return
        then()
        onChanged()
        busy = false
        pending.pollFirst()?.let { busy = true; it() }
    }
}
