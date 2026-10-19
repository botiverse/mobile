package build.raft.app.cmp.host

import androidx.compose.runtime.staticCompositionLocalOf
import build.raft.app.host.HostCapabilities
import build.raft.app.parseSlockJsonObjectOrNull
import kotlinx.serialization.json.JsonObject

/**
 * Page-level navigation owned by the native host (Activity stack on Android, the shared
 * `UINavigationController` on iOS). CMP pages never navigate in-composition while Kuikly pages
 * coexist; [openPage] goes back through the host router, which picks Kuikly or CMP by page name.
 */
interface SlockCmpNavigator {
    /** Opens [pageName] with [pageData] (the same pageData JSON contract Kuikly `openPage` uses). */
    fun openPage(pageName: String, pageData: JsonObject = JsonObject(emptyMap()))

    /** Closes the page hosting this composition (Android: finish the Activity; iOS: pop/dismiss). */
    fun closePage()
}

/**
 * Everything a CMP page gets from its native host. Provided by [LocalSlockHost].
 *
 * [bridgeTransport] is the raw `HRBridgeModule` wire [capabilities] is built on. It exists for the
 * CMP switch only: :shared's compat `BridgeModule` still speaks the bridge method protocol directly.
 * TODO(cmp-switch): remove once every bridge call goes through [HostCapabilities].
 */
class SlockCmpHost(
    val capabilities: HostCapabilities,
    val navigator: SlockCmpNavigator,
    val bridgeTransport: HostBridgeTransport? = null,
)

/** The route a CMP page was opened with: page name plus the unchanged pageData JSON. */
data class SlockCmpPageArgs(
    val pageName: String,
    val pageData: JsonObject,
) {
    companion object {
        /** Parses the host's pageData text; blank or malformed text yields an empty object. */
        fun fromJsonText(pageName: String, pageDataJson: String?): SlockCmpPageArgs =
            SlockCmpPageArgs(
                pageName = pageName,
                pageData = parseSlockJsonObjectOrNull(pageDataJson?.takeIf { it.isNotBlank() })
                    ?: JsonObject(emptyMap()),
            )
    }
}

val LocalSlockHost = staticCompositionLocalOf<SlockCmpHost> {
    error("LocalSlockHost is not provided; render CMP pages through SlockCmpRoot")
}

val LocalSlockPageArgs = staticCompositionLocalOf<SlockCmpPageArgs> {
    error("LocalSlockPageArgs is not provided; render CMP pages through SlockCmpRoot")
}
