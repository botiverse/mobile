package build.raft.app

import build.raft.app.data.StoredAuthSession
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val SERVER_SETUP_HOST_VERSION = "raft-host-v1"
internal const val SERVER_SETUP_COMPLETION_KIND = "onboarding:completed"
internal const val SERVER_SETUP_SERVER_SWITCH_KIND = "onboarding:server-switch-request"
internal const val SERVER_SETUP_CONTRACT_VERSION = "raft-onboarding-v1"
internal const val SERVER_SETUP_BRIDGE_SCHEME = "raft-onboarding-wake"

internal data class ServerSetupCompletionWake(
    val serverId: String,
    val serverSlug: String,
    val generation: String,
)

internal data class ServerSetupSwitchIntent(
    val sourceServerId: String,
    val targetServerId: String,
    val generation: String,
)

internal sealed interface ServerSetupHostEvent {
    data class StateMayHaveChanged(val wake: ServerSetupCompletionWake) : ServerSetupHostEvent
    data class SwitchServer(val intent: ServerSetupSwitchIntent) : ServerSetupHostEvent
}

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

@Serializable
private data class ServerSetupSwitchIntentWire(
    val contractVersion: String,
    val generation: String,
    val sourceServerId: String,
    val targetServerId: String,
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
): String? {
    if (webBaseUrl.isBlank() || serverSlug.isBlank() || !generation.isValidServerSetupGeneration()) return null
    val origin = serverSetupCanonicalWebOrigin(webBaseUrl) ?: return null
    val encodedSlug = serverSlug.serverSetupPercentEncode().takeIf { it.isNotBlank() } ?: return null
    val encodedGeneration = generation.serverSetupPercentEncode().takeIf { it.isNotBlank() } ?: return null
    return "$origin/s/$encodedSlug/onboarding?generation=$encodedGeneration"
}

private fun String.serverSetupPercentEncode(): String = buildString {
    encodeToByteArray().forEach { byte ->
        val value = byte.toInt() and 0xFF
        val char = value.toChar()
        if (
            char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
            char == '-' || char == '.' || char == '_' || char == '~'
        ) {
            append(char)
        } else {
            append('%')
            append(value.toString(16).uppercase().padStart(2, '0'))
        }
    }
}

/**
 * Environment `webUrl` may point at a tenant route (for example `/s/botiverse/`). The onboarding
 * route is rooted at the same trusted HTTP(S) origin, never appended to that candidate path.
 */
internal fun serverSetupCanonicalWebOrigin(webBaseUrl: String): String? {
    val raw = webBaseUrl.trim()
    if (raw.isEmpty() || raw.any(Char::isWhitespace) || '\\' in raw) return null
    val schemeSeparator = raw.indexOf("://")
    if (schemeSeparator <= 0) return null
    val scheme = raw.substring(0, schemeSeparator).lowercase()
    if (scheme != "http" && scheme != "https") return null
    val remainder = raw.substring(schemeSeparator + 3)
    val authority = remainder.substringBefore('/').substringBefore('?').substringBefore('#')
    if (!serverSetupValidWebAuthority(authority)) return null
    return "$scheme://$authority"
}

private fun serverSetupValidWebAuthority(authority: String): Boolean {
    if (authority.isBlank() || '@' in authority) return false
    if (authority.startsWith('[')) {
        val closing = authority.indexOf(']')
        if (closing <= 1) return false
        val suffix = authority.substring(closing + 1)
        return suffix.isEmpty() || serverSetupValidPortSuffix(suffix)
    }
    if (authority.count { it == ':' } > 1) return false
    val host = authority.substringBefore(':')
    val suffix = authority.removePrefix(host)
    if (!serverSetupValidWebHost(host)) return false
    return suffix.isEmpty() || serverSetupValidPortSuffix(suffix)
}

private fun serverSetupValidWebHost(host: String): Boolean {
    if (host.isBlank() || host.length > 253 || host.any { !it.isLetterOrDigit() && it !in ".-" }) return false
    return host.split('.').all { label ->
        label.isNotEmpty() &&
            label.length <= 63 &&
            label.first().isLetterOrDigit() &&
            label.last().isLetterOrDigit()
    }
}

private fun serverSetupValidPortSuffix(value: String): Boolean {
    if (!value.startsWith(':')) return false
    val port = value.drop(1)
    return port.isNotEmpty() && port.all(Char::isDigit) && port.toIntOrNull() in 1..65535
}

/**
 * Installs the frozen two-positional-argument Web ABI without exposing a broad native JS object.
 * The synthetic navigation is rejected by the WebView allowlist and returned through the existing
 * navigationBlocked event on Android, iOS, and OHOS.
 */
internal fun serverSetupHostBridgeJavaScript(identity: ServerSetupAdmissionIdentity): String =
    """
        (function() {
          if (window.top !== window) return;
          Object.defineProperty(window, "RaftHost", {
            configurable: false,
            enumerable: false,
            writable: false,
            value: Object.freeze({
              version: "$SERVER_SETUP_HOST_VERSION",
              onboarding: Object.freeze({
                contractVersion: "$SERVER_SETUP_CONTRACT_VERSION",
                generation: ${serverSetupWakeJson.encodeToString(identity.generation)},
                sourceServerId: ${serverSetupWakeJson.encodeToString(identity.serverId)}
              }),
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
): ServerSetupCompletionWake? =
    (decodeServerSetupHostBridgeNavigation(url, decode) as? ServerSetupHostEvent.StateMayHaveChanged)?.wake

internal fun decodeServerSetupHostBridgeNavigation(
    url: String,
    decode: (String) -> String,
): ServerSetupHostEvent? {
    val prefix = "$SERVER_SETUP_BRIDGE_SCHEME://emit?"
    if (!url.startsWith(prefix)) return null
    val parameters = url.removePrefix(prefix).split('&').mapNotNull { part ->
        val separator = part.indexOf('=')
        if (separator <= 0) {
            null
        } else {
            runCatching { part.substring(0, separator) to decode(part.substring(separator + 1)) }.getOrNull()
        }
    }.toMap()
    if (parameters.keys != setOf("kind", "payload")) return null
    val payload = runCatching { JSONObject(parameters.getValue("payload")) }.getOrNull() ?: return null
    return decodeServerSetupHostEvent(parameters.getValue("kind"), payload)
}

internal fun decodeServerSetupHostEvent(kind: String, payload: Any?): ServerSetupHostEvent? {
    if (kind == SERVER_SETUP_COMPLETION_KIND) {
        return decodeServerSetupCompletionWake(kind, payload)?.let(ServerSetupHostEvent::StateMayHaveChanged)
    }
    if (kind != SERVER_SETUP_SERVER_SWITCH_KIND) return null
    val json = payload as? JSONObject ?: return null
    val wire = try {
        serverSetupWakeJson.decodeFromString(ServerSetupSwitchIntentWire.serializer(), json.toString())
    } catch (_: SerializationException) {
        return null
    }
    val sourceServerId = wire.sourceServerId.trim()
    val targetServerId = wire.targetServerId.trim()
    if (
        wire.contractVersion != SERVER_SETUP_CONTRACT_VERSION ||
        !wire.generation.isValidServerSetupGeneration() ||
        sourceServerId.isBlank() || targetServerId.isBlank() ||
        sourceServerId == targetServerId
    ) return null
    return ServerSetupHostEvent.SwitchServer(
        ServerSetupSwitchIntent(sourceServerId, targetServerId, wire.generation)
    )
}

internal fun ServerSetupSwitchIntent.matchesSource(identity: ServerSetupAdmissionIdentity): Boolean =
    sourceServerId == identity.serverId && generation == identity.generation

internal fun serverSetupSwitchTargetId(
    session: StoredAuthSession,
    identity: ServerSetupAdmissionIdentity,
    intent: ServerSetupSwitchIntent,
    activeOwner: Boolean,
    controlledDocument: Boolean,
): String? =
    intent.targetServerId.takeIf {
        activeOwner &&
            controlledDocument &&
            identity.accountId == session.user.id.trim() &&
            intent.matchesSource(identity) &&
            session.servers.any { server -> server.id == intent.targetServerId }
    }

internal fun serverSetupControlledDocumentMatchesOrigin(documentUrl: String, webBaseUrl: String): Boolean {
    val expected = serverSetupCanonicalWebOrigin(webBaseUrl) ?: return false
    val actual = serverSetupCanonicalWebOrigin(documentUrl) ?: return false
    return actual == expected
}
