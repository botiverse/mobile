package build.raft.app.cmp.host

import androidx.compose.runtime.staticCompositionLocalOf
import build.raft.app.host.HostCapabilities
import build.raft.app.parseRaftJsonObjectOrNull
import kotlinx.serialization.json.JsonObject

/**
 * Page-level navigation owned by the native host (Activity stack on Android, the shared
 * `UINavigationController` on iOS). Pages never navigate in-composition; [openPage] goes back
 * through the host router, which opens a new host for the page.
 */
interface RaftCmpNavigator {
    /** Opens [pageName] with [pageData] (the same pageData JSON contract Kuikly `openPage` uses). */
    fun openPage(pageName: String, pageData: JsonObject = JsonObject(emptyMap()))

    /** Closes the page hosting this composition (Android: finish the Activity; iOS: pop/dismiss). */
    fun closePage()
}

/**
 * Everything a CMP page gets from its native host. Provided by [LocalRaftHost].
 *
 * [bridgeTransport] is the raw `HRBridgeModule` wire [capabilities] is built on. It exists for the
 * CMP switch only: :shared's compat `BridgeModule` still speaks the bridge method protocol directly.
 * TODO(cmp-switch): remove once every bridge call goes through [HostCapabilities].
 */
class RaftCmpHost(
    val capabilities: HostCapabilities,
    val navigator: RaftCmpNavigator,
    val bridgeTransport: HostBridgeTransport? = null,
    val events: RaftHostEvents = RaftHostEvents(),
    val childPageLoads: RaftChildPageLoadListener? = null,
)

/**
 * Page -> native host report that an embedded child page (`RaftChildPage`) finished loading:
 * the CMP counterpart of the Kuikly child render view's load-complete callback. Hosts use it for
 * render readiness (connected tests wait on it before sampling pixels). Called on the main thread.
 */
fun interface RaftChildPageLoadListener {
    fun onChildPageLoadComplete(pageName: String, pageDataText: String, succeeded: Boolean)
}

/**
 * Native host -> page events (the Kuikly `sendEvent(event, data)` channel: window insets, adaptive
 * pane changes, ...). The host calls [send]; page runtimes (:shared `RaftPagerHost`) [subscribe] and
 * forward each event to their page as a pager event. Must be used on the main thread.
 */
class RaftHostEvents {
    private val listeners = LinkedHashSet<(String, JsonObject) -> Unit>()

    /** Registers [listener]; the returned function unregisters it. */
    fun subscribe(listener: (event: String, data: JsonObject) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    /** Delivers [event] to every current subscriber (snapshot: listeners may unsubscribe re-entrantly). */
    fun send(event: String, data: JsonObject = JsonObject(emptyMap())) {
        listeners.toList().forEach { it(event, data) }
    }
}

/** The route a CMP page was opened with: page name plus the unchanged pageData JSON. */
data class RaftCmpPageArgs(
    val pageName: String,
    val pageData: JsonObject,
) {
    companion object {
        /** Parses the host's pageData text; blank or malformed text yields an empty object. */
        fun fromJsonText(pageName: String, pageDataJson: String?): RaftCmpPageArgs =
            RaftCmpPageArgs(
                pageName = pageName,
                pageData = parseRaftJsonObjectOrNull(pageDataJson?.takeIf { it.isNotBlank() })
                    ?: JsonObject(emptyMap()),
            )
    }
}

val LocalRaftHost = staticCompositionLocalOf<RaftCmpHost> {
    error("LocalRaftHost is not provided; render CMP pages through RaftCmpRoot")
}

val LocalRaftPageArgs = staticCompositionLocalOf<RaftCmpPageArgs> {
    error("LocalRaftPageArgs is not provided; render CMP pages through RaftCmpRoot")
}
