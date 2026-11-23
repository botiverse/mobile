package build.raft.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Canonical HTTP JSON error envelope decoded from non-2xx API responses (`#proj-mobile:#319`).
 *
 * Extracts the server's human-readable refusal sentence (`error` -> fallback `message`) and
 * machine-readable refusal code (`code` -> fallback `errorCode`). Preserves `"edge_blocked"` in
 * [serverMessage] so Tier 1 error presentation can classify CDN/WAF intercepts reliably.
 */
internal data class HttpErrorEnvelope(
    val serverMessage: String? = null,
    val serverCode: String? = null,
)

private val httpErrorEnvelopeJson = Json { ignoreUnknownKeys = true }

internal fun parseHttpErrorEnvelope(
    bodyText: String?,
    trimMessage: Boolean = true,
): HttpErrorEnvelope? {
    val payload = bodyText?.takeIf { it.isNotBlank() } ?: return null
    val root = runCatching { httpErrorEnvelopeJson.parseToJsonElement(payload) }.getOrNull() as? JsonObject ?: return null
    fun stringField(key: String): String? =
        (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val rawMessage =
        stringField("error")?.takeIf { it.isNotBlank() }
            ?: stringField("message")?.takeIf { it.isNotBlank() }
    val message = if (trimMessage) rawMessage?.trim() else rawMessage
    val code =
        stringField("code")?.trim()?.takeIf { it.isNotEmpty() }
            ?: stringField("errorCode")?.trim()?.takeIf { it.isNotEmpty() }
    if (message == null && code == null) return null
    return HttpErrorEnvelope(serverMessage = message, serverCode = code)
}

internal fun HomeNetworkResponse.parseHttpErrorEnvelope(
    trimMessage: Boolean = true,
): HttpErrorEnvelope? {
    // code <= 0 represents synthetic client-side transport/timeout responses (no server answer),
    // which must never be decoded as a server refusal message.
    if (code <= 0 || isSuccessful) return null
    val payload = errorBodyText?.takeIf { it.isNotBlank() } ?: bodyText?.takeIf { it.isNotBlank() }
    return parseHttpErrorEnvelope(payload, trimMessage = trimMessage)
}

internal fun HomeNetworkResponse.toAuthSessionRequiredResult(
    envelope: HttpErrorEnvelope? = parseHttpErrorEnvelope(),
): DataResult.NoPermission =
    DataResult.NoPermission(
        PermissionBlocker(
            code = "auth_required",
            requiredCapability = "auth_session",
            serverMessage = envelope?.serverMessage,
            serverCode = envelope?.serverCode,
            traceId = traceId,
        )
    )
