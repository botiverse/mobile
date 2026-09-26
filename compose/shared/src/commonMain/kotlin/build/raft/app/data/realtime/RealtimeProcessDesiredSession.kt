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

/**
 * Task #366 (artin ruling: "维护8个，要是用户有20个呢？这个就不合理，先下掉，…改成更合理的").
 * Scope the realtime transport set to the CURRENT server only, instead of
 * holding a persistent socket for every authenticated server (PR #1180). We
 * stay authenticated to every granted server (grants are unchanged), but keep
 * a live realtime connection for the selected server alone; non-current server
 * drawer unread rides the home snapshot's serverUnreadSummary, not a socket.
 *
 * Rules:
 *  - current server present AND among the grants -> exactly {current};
 *  - current unknown/blank, or not among the grants -> empty, so we keep zero
 *    persistent sockets until a granted current server is known (a later
 *    reconcile re-run opens it). Never fan out to every authenticated server.
 */
internal fun realtimeCurrentServerScopedServerIds(
    currentServerId: String?,
    grantedServerIds: Set<String>,
): Set<String> {
    val normalizedGrants =
        grantedServerIds.asSequence().map(String::trim).filter(String::isNotEmpty).toSet()
    val normalizedCurrent = currentServerId?.trim().orEmpty()
    return if (normalizedCurrent.isNotEmpty() && normalizedCurrent in normalizedGrants) {
        setOf(normalizedCurrent)
    } else {
        emptySet()
    }
}

internal fun realtimeProcessDesiredSession(
    authority: RealtimeScopeAuthority?,
    environmentId: String,
    socketUrl: String,
    currentServerId: String?,
): RealtimeProcessDesiredSession? {
    val current = authority ?: return null
    return RealtimeProcessDesiredSession(
        environmentId = environmentId,
        socketUrl = socketUrl,
        principalId = current.witness.principalId,
        authGeneration = current.witness.authGeneration,
        connectionFamilyId = current.witness.connectionFamilyId,
        accessToken = current.accessToken,
        serverIds = realtimeCurrentServerScopedServerIds(currentServerId, current.grantsByServer.keys),
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
    // task #333: identity = the env/account binding (environmentId, socketUrl,
    // principalId, connectionFamilyId). A silent token rotation bumps authGeneration
    // and accessToken on the SAME binding — that is a credential refresh, not an
    // identity replacement. Replacing the binding must tear the session down
    // (disconnectAll + server_removed); a credential rotation must only re-auth the
    // socket with the new token, keeping the session and the UI where it was.
    val bindingChanged =
        old.environmentId != desired.environmentId ||
            old.socketUrl != desired.socketUrl ||
            old.principalId != desired.principalId ||
            old.connectionFamilyId != desired.connectionFamilyId
    val credentialsRotated =
        old.authGeneration != desired.authGeneration ||
            old.accessToken != desired.accessToken
    if (bindingChanged) {
        return RealtimeProcessDesiredSessionPlan(
            disconnectAll = true,
            disconnectServerIds = emptySet(),
            connectServerIds = desired.serverIds,
            forceReconnect = true,
        )
    }
    if (credentialsRotated) {
        // Same binding, new token: re-auth the live socket(s) but do NOT disconnect-
        // all or report process_identity_replaced / server_removed. Re-issue the
        // desired server set so the socket reconnects with the rotated token, and
        // still drop any server that left the desired set.
        return RealtimeProcessDesiredSessionPlan(
            disconnectAll = false,
            disconnectServerIds = old.serverIds - desired.serverIds,
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

data class RealtimeRuntimeCursorIdentity(
    val serverId: String,
    val scopeLeaseId: String,
    val principalId: String,
    val authGeneration: Long,
    val connectionFamilyId: String,
)

data class RealtimeRuntimeCursorAdvanceDecision(
    val shouldAdvance: Boolean,
    val resumeSeq: Long,
    val reason: String,
)

fun realtimeRuntimeCursorAdvanceDecision(
    currentIdentity: RealtimeRuntimeCursorIdentity,
    requestIdentity: RealtimeRuntimeCursorIdentity,
    currentSeq: Long,
    appliedSeq: Long,
): RealtimeRuntimeCursorAdvanceDecision {
    val normalizedCurrent = currentIdentity.normalizedOrNull()
        ?: return RealtimeRuntimeCursorAdvanceDecision(false, currentSeq.coerceAtLeast(0L), "invalid_current_identity")
    val normalizedRequest = requestIdentity.normalizedOrNull()
        ?: return RealtimeRuntimeCursorAdvanceDecision(false, currentSeq.coerceAtLeast(0L), "invalid_request_identity")
    if (normalizedCurrent != normalizedRequest) {
        return RealtimeRuntimeCursorAdvanceDecision(false, currentSeq.coerceAtLeast(0L), "stale_witness")
    }
    if (appliedSeq <= 0L) {
        return RealtimeRuntimeCursorAdvanceDecision(false, currentSeq.coerceAtLeast(0L), "invalid_seq")
    }
    return RealtimeRuntimeCursorAdvanceDecision(
        shouldAdvance = true,
        resumeSeq = maxOf(currentSeq.coerceAtLeast(0L), appliedSeq),
        reason = "applied",
    )
}

private fun RealtimeRuntimeCursorIdentity.normalizedOrNull(): RealtimeRuntimeCursorIdentity? {
    val cleanServerId = serverId.trim()
    val cleanScopeLeaseId = scopeLeaseId.trim()
    val cleanPrincipalId = principalId.trim()
    val cleanFamilyId = connectionFamilyId.trim()
    if (
        cleanServerId.isEmpty() ||
        cleanScopeLeaseId.isEmpty() ||
        cleanPrincipalId.isEmpty() ||
        authGeneration <= 0L ||
        cleanFamilyId.isEmpty()
    ) {
        return null
    }
    return copy(
        serverId = cleanServerId,
        scopeLeaseId = cleanScopeLeaseId,
        principalId = cleanPrincipalId,
        connectionFamilyId = cleanFamilyId,
    )
}

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
