package build.raft.app.desktop

import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.parseRaftJsonObjectOrNull
import build.raft.app.toRaftJsonString
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The desktop side of the `HRBridgeModule` wire (what RaftCmpActivity serves on Android). M1
 * covers the methods login, the app shell and a channel need; everything else answers "not
 * implemented" (the Android host's answer for unknown methods) and is logged.
 */
class RaftDesktopBridgeTransport(private val onClosePage: () -> Unit) : HostBridgeTransport {
    override fun call(method: String, paramsJson: String, callback: ((String?) -> Unit)?) {
        val params = parseRaftJsonObjectOrNull(paramsJson) ?: JsonObject(emptyMap())
        when (method) {
            "toast" -> RaftDesktopToasts.show(params.string("content"))
            "copyToPasteboard" ->
                runCatching {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(params.string("content")), null)
                }
            "openUrl" -> openUrl(params.string("url"))
            "closePage" -> onClosePage()
            "log" -> log("page: ${params.string("message")}")
            "reportRealTime" -> RaftDesktopRealtimeHost.report(params)
            "settingsCapability" -> RaftDesktopSettingsCapability.handle(params, callback)
            // TODO(desktop): app badge, shortcuts and performance markers have no desktop surface.
            "appBadge", "syncDesktopShortcuts", "performanceScene", "performanceMarker" -> Unit
            else -> {
                log("bridge method not implemented: $method")
                callback?.invoke(NOT_IMPLEMENTED)
            }
        }
    }

    override fun callSync(method: String, paramsJson: String): String? {
        val params = parseRaftJsonObjectOrNull(paramsJson) ?: JsonObject(emptyMap())
        return when (method) {
            "urlEncode" -> URLEncoder.encode(params.string("string"), Charsets.UTF_8)
            "urlDecode" -> URLDecoder.decode(params.string("string"), Charsets.UTF_8)
            "currentTimestamp" -> System.currentTimeMillis().toString()
            else -> null
        }
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) return
        runCatching { Desktop.getDesktop().browse(URI(url)) }
            .onFailure { log("openUrl failed: ${it.message}") }
    }

    companion object {
        val NOT_IMPLEMENTED: String =
            JsonObject(mapOf("code" to JsonPrimitive(-1), "message" to JsonPrimitive("Method not implemented")))
                .toRaftJsonString()
    }
}

internal fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
