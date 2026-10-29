package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import build.raft.designsystem.components.RaftSideActionHost
import build.raft.designsystem.components.RaftSideActionHostColumn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/*
 * Host-drawn iPhone Duo side column (iOS 27.1: bar button items with `axisBehavior =
 * .verticalPreferred` on a hidden navigation bar, drawn by the system in the vertical strip).
 *
 * The page's RaftSideActionColumn stays the single source of truth for what the column holds
 * (Back, the page actions, which of them overflow into the ⋯). When every button has an SF Symbol
 * it hands them to the [RaftSideActionHost] this bridge provides, keeps its width so the page
 * still ends at the strip, and draws nothing; the host renders the published column and reports
 * taps back through [RaftSideActionHostBridge.sideActionTapped], which runs the button's latest
 * `onClick`.
 *
 * A host opts in per page, like the native tab bar ([RaftNativeTabBarBridge]): it registers a
 * bridge in [RaftSideActionHostRegistry], passes the id in the page's pageData under
 * [RaftSideActionHostIdPageDataKey], and switches it on with [RaftSideActionHostBridge.setAvailable]
 * once it can draw vertical bar items (the capability flag; off by default). Without all three
 * (Android, older iOS, any page the host does not register) the column draws itself as before.
 *
 * Main thread only: the page composes on the main thread and the iOS host calls in from UIKit.
 */

/** pageData key carrying the id of the host's registered [RaftSideActionHostBridge]. */
const val RaftSideActionHostIdPageDataKey = "slockSideActionHostId"

/** The `version` of the column JSON ([RaftSideActionHostListener.onSideActionColumnChanged]). */
const val RaftSideActionHostWireVersion = 1

/** Implemented by the host (Swift). Called on the main thread. */
interface RaftSideActionHostListener {
    /**
     * Draw the column described by [json] (see [raftSideActionHostJson]), replacing the previous
     * one. Not called again for an identical column.
     */
    fun onSideActionColumnChanged(json: String)

    /** Remove the system buttons: the page draws its own column again, or has none. */
    fun onSideActionColumnCleared()
}

/**
 * Per-page channel between the page's side column and the host's system bar buttons. Created and
 * registered by the host before the page is created.
 */
class RaftSideActionHostBridge {
    private val mutableAvailable = MutableStateFlow(false)
    private var listener: RaftSideActionHostListener? = null
    private var column: RaftSideActionHostColumn? = null
    private var json: String? = null
    private var onAction: ((String) -> Unit)? = null

    /** Whether the host draws columns ([setAvailable]); the page provides [host] only while true. */
    internal val available: StateFlow<Boolean> = mutableAvailable.asStateFlow()

    /** Swift convenience: the current value of [available]. */
    val isAvailable: Boolean
        get() = mutableAvailable.value

    /** The column JSON the host should be showing, or null for none. */
    val currentColumnJson: String?
        get() = json

    /**
     * Host -> page: the host can (true) or cannot (false) draw the column with system bar buttons
     * (iOS 27.1+ vertical bar items in this window). Off by default. Switching off removes the
     * system buttons at once; the page draws its own column from its next frame.
     */
    fun setAvailable(available: Boolean) {
        if (mutableAvailable.value == available) return
        mutableAvailable.value = available
        if (!available) withdraw()
    }

    /** Sets the single host listener and replays the current column (or its absence) to it. */
    fun attachListener(listener: RaftSideActionHostListener) {
        this.listener = listener
        val current = json
        if (current != null) listener.onSideActionColumnChanged(current) else listener.onSideActionColumnCleared()
    }

    fun detachListener() {
        listener = null
    }

    /**
     * Host -> page: the user tapped the button or ⋯ menu row [id]. Runs that action's latest
     * `onClick`. False (nothing runs) when no column is shown or [id] is not an enabled item of it.
     */
    fun sideActionTapped(id: String): Boolean {
        val shown = column ?: return false
        val handler = onAction ?: return false
        if ((shown.primary + shown.actions).none { it.id == id && it.enabled }) return false
        handler(id)
        return true
    }

    /** What the page's side column talks to; provided by [rememberRaftSideActionHost]. */
    internal val host: RaftSideActionHost =
        object : RaftSideActionHost {
            override fun present(column: RaftSideActionHostColumn, onAction: (id: String) -> Unit): Boolean {
                if (!mutableAvailable.value) {
                    withdraw()
                    return false
                }
                this@RaftSideActionHostBridge.column = column
                this@RaftSideActionHostBridge.onAction = onAction
                val next = raftSideActionHostJson(column)
                if (next != json) {
                    json = next
                    listener?.onSideActionColumnChanged(next)
                }
                return true
            }

            override fun clear() = withdraw()
        }

    private fun withdraw() {
        column = null
        onAction = null
        if (json == null) return
        json = null
        listener?.onSideActionColumnCleared()
    }
}

/** Host-owned bridges by host id. Exported to Swift as `RaftSideActionHostRegistry.shared`. */
object RaftSideActionHostRegistry {
    private val bridges = mutableMapOf<String, RaftSideActionHostBridge>()

    fun register(hostId: String, bridge: RaftSideActionHostBridge) {
        if (hostId.isBlank()) return
        bridges[hostId] = bridge
    }

    /** Removes [hostId] only while it still maps to [bridge]. */
    fun unregister(hostId: String, bridge: RaftSideActionHostBridge) {
        if (bridges[hostId] === bridge) bridges.remove(hostId)
    }

    internal fun bridgeFor(hostId: String?): RaftSideActionHostBridge? =
        hostId?.takeIf { it.isNotBlank() }?.let { bridges[it] }
}

/**
 * The [RaftSideActionHost] for `LocalRaftSideActionHost`: [bridge]'s while the host has switched
 * it on, else null (the column draws itself). Switching it off drops the host, which clears it.
 */
@Composable
internal fun rememberRaftSideActionHost(bridge: RaftSideActionHostBridge?): RaftSideActionHost? {
    if (bridge == null) return null
    val available by bridge.available.collectAsState()
    return if (available) bridge.host else null
}

/**
 * The column as the host receives it (wire version [RaftSideActionHostWireVersion]):
 *
 * ```
 * {"version":1,
 *  "primary":[{"id":"back","label":"Back","systemSymbol":"chevron.backward","enabled":true,"attention":false}],
 *  "actions":[{"id":"thread-search",...},{"id":"thread-settings",...}],
 *  "visibleActionIds":["thread-search","thread-settings"],
 *  "overflowActionIds":[],
 *  "overflowLabel":"More Actions",
 *  "overflowAttention":false}
 * ```
 *
 * `actions` are all of them in listed order; `visibleActionIds` the ones drawn as buttons and
 * `overflowActionIds` the ⋯ menu's rows, both in listed order. An item's `attentionDescription`
 * is present only when it has one. `overflowAttention`: an overflowed item wants attention (the ⋯
 * wears the pixel).
 */
internal fun raftSideActionHostJson(column: RaftSideActionHostColumn): String {
    val overflowed = column.overflowActionIds.toSet()
    return buildJsonObject {
        put("version", RaftSideActionHostWireVersion)
        putJsonArray("primary") { column.primary.forEach { addJsonObject { putItem(it) } } }
        putJsonArray("actions") { column.actions.forEach { addJsonObject { putItem(it) } } }
        putJsonArray("visibleActionIds") { column.visibleActionIds.forEach { add(it) } }
        putJsonArray("overflowActionIds") { column.overflowActionIds.forEach { add(it) } }
        put("overflowLabel", column.overflowLabel)
        put("overflowAttention", column.actions.any { it.id in overflowed && it.attention })
    }.toString()
}

private fun JsonObjectBuilder.putItem(item: RaftSideActionHostColumn.Item) {
    put("id", item.id)
    put("label", item.label)
    put("systemSymbol", item.systemSymbol)
    put("enabled", item.enabled)
    put("attention", item.attention)
    item.attentionDescription?.let { put("attentionDescription", it) }
}
