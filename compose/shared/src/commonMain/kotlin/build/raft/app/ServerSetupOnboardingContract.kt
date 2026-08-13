package build.raft.app

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal const val SERVER_SETUP_HOST_VERSION = "raft-host-v1"
internal const val SERVER_SETUP_COMPLETION_KIND = "onboarding:completed"
internal const val SERVER_SETUP_CONTRACT_VERSION = "raft-onboarding-v1"
internal const val SERVER_SETUP_BRIDGE_SCHEME = "raft-onboarding-wake"

internal data class ServerSetupCompletionWake(
    val serverId: String,
    val serverSlug: String,
    val generation: String,
)

internal fun decodeServerSetupCompletionWake(kind: String, payload: Any?): ServerSetupCompletionWake? {
    if (kind != SERVER_SETUP_COMPLETION_KIND) return null
    val json = payload as? JSONObject ?: return null
    val wire = try {
        serverSetupWakeJson.decodeFromString(ServerSetupCompletionWakeWire.serializer(), json.toString())
    } catch (_: SerializationException) {
        return null
    }
    if (wire.contractVersion != SERVER_SETUP_CONTRACT_VERSION) return null
    val serverId = wire.serverId.trim()
    val serverSlug = wire.serverSlug.trim()
    val generation = wire.generation
    if (serverId.isBlank() || serverSlug.isBlank() || !generation.isValidServerSetupGeneration()) return null
    return ServerSetupCompletionWake(serverId, serverSlug, generation)
}

@Serializable
private data class ServerSetupCompletionWakeWire(
    val contractVersion: String,
    val serverId: String,
    val serverSlug: String,
    val generation: String,
)

private val serverSetupWakeJson = Json { ignoreUnknownKeys = false }

internal fun ServerSetupCompletionWake.matches(
    identity: ServerSetupAdmissionIdentity,
    expectedServerSlug: String,
): Boolean =
    serverId == identity.serverId &&
        serverSlug == expectedServerSlug &&
        generation == identity.generation

internal fun serverSetupOnboardingUrl(
    webBaseUrl: String,
    serverSlug: String,
    generation: String,
    encode: (String) -> String,
): String? {
    if (webBaseUrl.isBlank() || serverSlug.isBlank() || !generation.isValidServerSetupGeneration()) return null
    return "${webBaseUrl.trimEnd('/')}/s/${encode(serverSlug)}/onboarding?generation=${encode(generation)}"
}

/**
 * Installs the frozen two-positional-argument Web ABI without exposing a broad native JS object.
 * The synthetic navigation is rejected by the WebView allowlist and returned through the existing
 * navigationBlocked event on Android, iOS, and OHOS.
 */
internal fun serverSetupHostBridgeJavaScript(): String =
    """
        (function() {
          if (window.top !== window) return;
          Object.defineProperty(window, "RaftHost", {
            configurable: false,
            enumerable: false,
            writable: false,
            value: Object.freeze({
              version: "$SERVER_SETUP_HOST_VERSION",
              emit: function(kind, payload) {
                if (arguments.length !== 2 || typeof kind !== "string" || !payload || typeof payload !== "object" || Array.isArray(payload)) return;
                try {
                  window.location.href = "$SERVER_SETUP_BRIDGE_SCHEME://emit?kind=" + encodeURIComponent(kind) + "&payload=" + encodeURIComponent(JSON.stringify(payload));
                } catch (_) {}
              }
            })
          });
        })();
    """.trimIndent()

internal fun decodeServerSetupBridgeNavigation(
    url: String,
    decode: (String) -> String,
): ServerSetupCompletionWake? {
    val prefix = "$SERVER_SETUP_BRIDGE_SCHEME://emit?"
    if (!url.startsWith(prefix)) return null
    val parameters = url.removePrefix(prefix).split('&').mapNotNull { part ->
        val separator = part.indexOf('=')
        if (separator <= 0) null else part.substring(0, separator) to decode(part.substring(separator + 1))
    }.toMap()
    if (parameters.keys != setOf("kind", "payload")) return null
    val payload = runCatching { JSONObject(parameters.getValue("payload")) }.getOrNull() ?: return null
    return decodeServerSetupCompletionWake(parameters.getValue("kind"), payload)
}
