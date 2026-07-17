package build.raft.app.data.realtime

import build.raft.app.data.RealtimeScopeAuthority
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Process-owned realtime transport intent. This is derived from durable auth
 * authority; route/page state never owns or carries it.
 */
internal data class RealtimeProcessDesiredSession(
    val environmentId: String,
    val socketUrl: String,
    val principalId: String,
    val authGeneration: Long,
    val connectionFamilyId: String,
    val accessToken: String,
    val serverIds: Set<String>,
) {
    val normalizedServerIds: Set<String>
        get() = serverIds.map(String::trim).filter(String::isNotEmpty).toSet()

    fun normalizedOrNull(): RealtimeProcessDesiredSession? {
        val cleanEnvironmentId = environmentId.trim().trimEnd('/')
        val cleanSocketUrl = socketUrl.trim().trimEnd('/')
        val cleanPrincipalId = principalId.trim()
        val cleanFamilyId = connectionFamilyId.trim()
        val cleanAccessToken = accessToken.trim()
        val cleanServerIds = normalizedServerIds
        if (
            cleanEnvironmentId.isEmpty() ||
            cleanSocketUrl.isEmpty() ||
            cleanPrincipalId.isEmpty() ||
            authGeneration <= 0L ||
            cleanFamilyId.isEmpty() ||
            cleanAccessToken.isEmpty() ||
            cleanServerIds.isEmpty()
        ) {
            return null
        }
        return copy(
            environmentId = cleanEnvironmentId,
            socketUrl = cleanSocketUrl,
            principalId = cleanPrincipalId,
            connectionFamilyId = cleanFamilyId,
            accessToken = cleanAccessToken,
            serverIds = cleanServerIds,
        )
    }

    override fun toString(): String =
        "RealtimeProcessDesiredSession(environmentId=$environmentId, socketUrl=$socketUrl, " +
            "principalId=$principalId, authGeneration=$authGeneration, " +
            "connectionFamilyId=$connectionFamilyId, accessToken=<redacted>, serverIds=$serverIds)"
}

internal data class RealtimeProcessDesiredSessionPlan(
    val disconnectAll: Boolean,
    val disconnectServerIds: Set<String>,
    val connectServerIds: Set<String>,
    val forceReconnect: Boolean,
)

internal fun realtimeProcessDesiredSession(
    authority: RealtimeScopeAuthority?,
    environmentId: String,
    socketUrl: String,
): RealtimeProcessDesiredSession? {
    val current = authority ?: return null
    return RealtimeProcessDesiredSession(
        environmentId = environmentId,
        socketUrl = socketUrl,
        principalId = current.witness.principalId,
        authGeneration = current.witness.authGeneration,
        connectionFamilyId = current.witness.connectionFamilyId,
        accessToken = current.accessToken,
        serverIds = current.grantsByServer.keys,
    ).normalizedOrNull()
}

internal fun reconcileRealtimeProcessDesiredSession(
    previous: RealtimeProcessDesiredSession?,
    next: RealtimeProcessDesiredSession?,
    forceReconnect: Boolean = false,
): RealtimeProcessDesiredSessionPlan {
    val old = previous?.normalizedOrNull()
    val desired = next?.normalizedOrNull()
    if (desired == null) {
        return RealtimeProcessDesiredSessionPlan(
            disconnectAll = old != null,
            disconnectServerIds = emptySet(),
            connectServerIds = emptySet(),
            forceReconnect = false,
        )
    }
    if (old == null) {
        return RealtimeProcessDesiredSessionPlan(
            disconnectAll = false,
            disconnectServerIds = emptySet(),
            connectServerIds = desired.serverIds,
            forceReconnect = forceReconnect,
        )
    }
    val identityChanged =
        old.environmentId != desired.environmentId ||
            old.socketUrl != desired.socketUrl ||
            old.principalId != desired.principalId ||
            old.authGeneration != desired.authGeneration ||
            old.connectionFamilyId != desired.connectionFamilyId ||
            old.accessToken != desired.accessToken
    if (identityChanged) {
        return RealtimeProcessDesiredSessionPlan(
            disconnectAll = true,
            disconnectServerIds = emptySet(),
            connectServerIds = desired.serverIds,
            forceReconnect = true,
        )
    }
    return RealtimeProcessDesiredSessionPlan(
        disconnectAll = false,
        disconnectServerIds = old.serverIds - desired.serverIds,
        connectServerIds =
            if (forceReconnect) {
                desired.serverIds
            } else {
                desired.serverIds - old.serverIds
            },
        forceReconnect = forceReconnect,
    )
}

data class RealtimePayloadServerGateDecision(
    val shouldAccept: Boolean,
    val payloadServerId: String,
    val reason: String,
)

/**
 * Generic raw-ingress integrity gate shared by every platform host.
 * Only an exact top-level camelCase `serverId` participates; nested aliases
 * are deliberately ignored. Missing/null is the explicit account-level pass.
 */
fun realtimePayloadServerGate(
    connectionServerId: String,
    rawPayload: String,
): RealtimePayloadServerGateDecision {
    val cleanConnectionServerId = connectionServerId.trim()
    if (cleanConnectionServerId.isEmpty()) {
        return RealtimePayloadServerGateDecision(false, "", "missing_connection_server")
    }
    val root =
        runCatching { Json.parseToJsonElement(rawPayload).jsonObject }
            .getOrElse {
                return RealtimePayloadServerGateDecision(false, "", "invalid_payload")
            }
    if (!root.containsKey(GeneratedRealtimeContract.PayloadKey.ServerId)) {
        return RealtimePayloadServerGateDecision(true, "", "absent")
    }
    val rawServerId = root[GeneratedRealtimeContract.PayloadKey.ServerId]
    if (rawServerId == null || rawServerId is JsonNull) {
        return RealtimePayloadServerGateDecision(true, "", "absent")
    }
    val payloadServerId =
        (rawServerId as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?.trim()
            .orEmpty()
    if (payloadServerId.isEmpty()) {
        return RealtimePayloadServerGateDecision(false, "", "invalid_server_id")
    }
    return if (payloadServerId == cleanConnectionServerId) {
        RealtimePayloadServerGateDecision(true, payloadServerId, "matched")
    } else {
        RealtimePayloadServerGateDecision(false, payloadServerId, "mismatch")
    }
}
