package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

/*
 * PROTOTYPE (agent/lagoon/ios-native-nav-proto, not for merge): the page's top bar drawn by the
 * host's real UINavigationBar instead of a Compose header.
 *
 * A host opts a page in like the native tab bar: it registers a [RaftNativeNavBarBridge] in
 * [RaftNativeNavBarRegistry] and passes the id in pageData under [RaftNativeNavBarHostIdPageDataKey].
 * [RaftTopPageHeader] (and so every head built on it, the conversation head included) then
 * publishes its title, subtitle and actions to the bridge and draws only a spacer for the
 * host bar's height (the page's top safe area, which UIKit grows by the bar). Without a bridge
 * (Android, desktop, any page the host does not opt in) nothing changes.
 */

/** pageData key carrying the id of the host's registered [RaftNativeNavBarBridge]. */
const val RaftNativeNavBarHostIdPageDataKey = "slockNativeNavBarHostId"

/** One trailing bar button: [systemSymbol] is an SF Symbol name. */
data class RaftNativeNavBarAction(
    val id: String,
    val label: String,
    val systemSymbol: String,
    val onClick: () -> Unit,
)

/** Implemented by the host (Swift). Called on the main thread. */
interface RaftNativeNavBarListener {
    /**
     * Show [title] / [subtitle] and the trailing buttons of [actionsJson]
     * (`[{"id":..,"label":..,"systemSymbol":..}]`, in order).
     */
    fun onNativeNavBarChanged(title: String, subtitle: String?, actionsJson: String)
}

class RaftNativeNavBarBridge {
    private var listener: RaftNativeNavBarListener? = null
    private var title: String? = null
    private var subtitle: String? = null
    private var actionsJson: String = "[]"
    private var handlers: Map<String, () -> Unit> = emptyMap()

    /** Sets the single host listener and replays what the page published so far. */
    fun attachListener(listener: RaftNativeNavBarListener) {
        this.listener = listener
        title?.let { listener.onNativeNavBarChanged(it, subtitle, actionsJson) }
    }

    fun detachListener() {
        listener = null
    }

    /** Host -> page: the bar button [id] was tapped. False when the page has no such action. */
    fun actionTapped(id: String): Boolean {
        val handler = handlers[id] ?: return false
        handler()
        return true
    }

    internal fun publish(title: String, subtitle: String?, actions: List<RaftNativeNavBarAction>) {
        handlers = actions.associate { it.id to it.onClick }
        val json =
            buildJsonArray {
                actions.forEach { action ->
                    addJsonObject {
                        put("id", action.id)
                        put("label", action.label)
                        put("systemSymbol", action.systemSymbol)
                    }
                }
            }.toString()
        if (title == this.title && subtitle == this.subtitle && json == actionsJson) return
        this.title = title
        this.subtitle = subtitle
        actionsJson = json
        listener?.onNativeNavBarChanged(title, subtitle, json)
    }
}

/** Host-owned bridges by host id. Exported to Swift as `RaftNativeNavBarRegistry.shared`. */
object RaftNativeNavBarRegistry {
    private val bridges = mutableMapOf<String, RaftNativeNavBarBridge>()

    fun register(hostId: String, bridge: RaftNativeNavBarBridge) {
        if (hostId.isBlank()) return
        bridges[hostId] = bridge
    }

    fun unregister(hostId: String, bridge: RaftNativeNavBarBridge) {
        if (bridges[hostId] === bridge) bridges.remove(hostId)
    }

    internal fun bridgeFor(hostId: String?): RaftNativeNavBarBridge? =
        hostId?.takeIf { it.isNotBlank() }?.let { bridges[it] }
}

/** The host bar of the current page, if the host draws one (null: the page draws its head). */
val LocalRaftNativeNavBar = staticCompositionLocalOf<RaftNativeNavBarBridge?> { null }

/**
 * Publishes the head to the host bar when there is one. True when the host draws it (the caller
 * then draws no head of its own).
 */
@Composable
internal fun raftNativeNavBarPublish(
    title: String,
    subtitle: String?,
    actions: List<RaftNativeNavBarAction>,
): Boolean {
    val bridge = LocalRaftNativeNavBar.current ?: return false
    SideEffect { bridge.publish(title, subtitle?.takeIf { it.isNotBlank() }, actions) }
    return true
}
