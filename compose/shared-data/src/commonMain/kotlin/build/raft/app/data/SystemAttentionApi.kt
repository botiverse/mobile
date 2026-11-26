package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.ServerId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.time.TimeSource

/**
 * Durable server-scoped runtime-account usage-limit attention notifications
 * (`botiverse/slock` `packages/shared/src/systemAttention.ts`,
 * `packages/server/src/routes/servers.ts`, `packages/web/src/components/layout/useUsageLimitAttention.ts`,
 * `#13079` commit `5907beadd` and current `staging`):
 *
 *     GET  /api/servers/:id/system-notifications-v2
 *     POST /api/servers/:id/system-notifications-v2/:incidentId/seen
 *     POST /api/servers/:id/system-notifications-v2/:incidentId/dismiss
 *
 * `usage_limit_attention_v0` was retired in `slock #13079`: this feed is active on every server
 * for owners and admins without any feature flag. Regular members and guests never fetch or render
 * these cards.
 */
@RaftCoreInternalApi const val SERVER_SYSTEM_ATTENTION_CONTRACT_VERSION: String = "server-system-attention-v1"

@RaftCoreInternalApi const val SYSTEM_ATTENTION_KIND_USAGE_LIMIT_REACHED: String = "usage_limit_reached"

/** Home notification-center id prefix for usage-limit cards (web `usage-limit:<id>`). */
@RaftCoreInternalApi const val USAGE_LIMIT_HOME_NOTIFICATION_PREFIX: String = "usage-limit:"

/** Web `POLL_MS` (60s): poll interval while visible. */
@RaftCoreInternalApi const val USAGE_LIMIT_ATTENTION_POLL_MS: Long = 60_000L

/** Minimum elapsed interval between foreground/visibility rereads (`RefreshIfStale`). */
@RaftCoreInternalApi const val USAGE_LIMIT_ATTENTION_MIN_REREAD_MS: Long = 30_000L

@RaftCoreInternalApi fun usageLimitHomeNotificationId(incidentId: String): String =
    USAGE_LIMIT_HOME_NOTIFICATION_PREFIX + incidentId

@RaftCoreInternalApi fun String.usageLimitIdFromHomeNotificationIdOrNull(): String? =
    takeIf { it.startsWith(USAGE_LIMIT_HOME_NOTIFICATION_PREFIX) }
        ?.removePrefix(USAGE_LIMIT_HOME_NOTIFICATION_PREFIX)
        ?.takeIf(String::isNotBlank)

/**
 * Server rule (`UsageLimitAttentionService.listForReceiver` / `writeReceipt`): only server
 * `owner` and `admin` roles receive or acknowledge system attention incidents; `member` and
 * `guest` never fetch or render them.
 */
@RaftCoreInternalApi fun serverRoleCanReceiveSystemAttention(role: String?): Boolean =
    when (role?.trim()?.lowercase()) {
        "owner", "admin" -> true
        else -> false
    }

/** Web `USAGE_LIMIT_PROVIDER_NAMES`: display name of a usage-limit notice's provider. */
@RaftCoreInternalApi fun usageLimitProviderDisplayName(provider: String): String =
    when (provider.trim().lowercase()) {
        "claude" -> "Claude"
        "codex" -> "Codex"
        "kimi" -> "Kimi"
        "grok" -> "Grok"
        "antigravity" -> "Antigravity"
        else -> provider.trim()
    }

@RaftCoreInternalApi data class UsageLimitAttentionMachine(
    val id: String,
    val name: String,
)

@RaftCoreInternalApi data class UsageLimitAttentionNotification(
    val id: String,
    val kind: String = SYSTEM_ATTENTION_KIND_USAGE_LIMIT_REACHED,
    val severity: String = "warning",
    val state: String = "active",
    val activatedAt: String,
    val lastObservedAt: String,
    val seenAt: String?,
    val machine: UsageLimitAttentionMachine,
    val provider: String,
    val windowKey: String,
    val resetsAt: String?,
    val affectedAgentIds: List<String>,
) {
    val unseen: Boolean get() = seenAt == null
}

@RaftCoreInternalApi data class ServerSystemAttentionSnapshot(
    val contractVersion: String = SERVER_SYSTEM_ATTENTION_CONTRACT_VERSION,
    val generatedAt: String,
    val snapshotMode: String = "replace",
    val unseenCount: Int,
    val notifications: List<UsageLimitAttentionNotification>,
)

private val systemAttentionJson = Json { ignoreUnknownKeys = true }

/**
 * Parses `GET /api/servers/:id/system-notifications-v2`.
 * Fails closed (`DataResult.Error`) on non-2xx, blank/malformed JSON, contract version mismatch,
 * or non-array `notifications`, while gracefully skipping individual malformed or unknown-kind
 * items inside a valid contract envelope.
 */
@RaftCoreInternalApi fun parseServerSystemAttentionResponse(
    statusCode: Int,
    bodyText: String?,
    traceId: String? = null,
): DataResult<ServerSystemAttentionSnapshot> {
    if (statusCode !in 200..299) {
        val envelope = if (statusCode > 0) parseHttpErrorEnvelope(bodyText) else null
        return DataResult.Error(
            ScreenError(
                code = "system_attention_http_$statusCode",
                retryable = statusCode >= 500,
                serverMessage = envelope?.serverMessage,
                serverCode = envelope?.serverCode,
                traceId = traceId,
            )
        )
    }
    val raw = bodyText?.trim()?.takeIf { it.isNotEmpty() }
        ?: return DataResult.Error(ScreenError(code = "system_attention_contract_mismatch", retryable = false, traceId = traceId))
    val root =
        runCatching { systemAttentionJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return DataResult.Error(ScreenError(code = "system_attention_contract_mismatch", retryable = false, traceId = traceId))
    val contractVersion = root.nonBlankStringOrNull("contractVersion")
    if (contractVersion != SERVER_SYSTEM_ATTENTION_CONTRACT_VERSION) {
        return DataResult.Error(ScreenError(code = "system_attention_contract_mismatch", retryable = false, traceId = traceId))
    }
    val notificationsArray = root["notifications"] as? JsonArray
        ?: return DataResult.Error(ScreenError(code = "system_attention_contract_mismatch", retryable = false, traceId = traceId))
    val notifications = notificationsArray.mapNotNull { element ->
        (element as? JsonObject)?.toUsageLimitAttentionNotificationOrNull()
    }
    val parsedUnseenCount =
        (root["unseenCount"] as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.intOrNull
            ?.takeIf { it >= 0 }
    // Keep unseenCount aligned with parsed notifications so a skipped unknown-kind row never leaves a ghost badge.
    val computedUnseen = notifications.count { it.seenAt == null }
    val unseenCount =
        if (notifications.size == notificationsArray.size && parsedUnseenCount != null) {
            parsedUnseenCount
        } else {
            computedUnseen
        }
    return DataResult.Content(
        ServerSystemAttentionSnapshot(
            contractVersion = contractVersion,
            generatedAt = root.nonBlankStringOrNull("generatedAt").orEmpty(),
            snapshotMode = root.nonBlankStringOrNull("snapshotMode") ?: "replace",
            unseenCount = unseenCount,
            notifications = notifications,
        )
    )
}

/** Parses `POST /api/servers/:id/system-notifications-v2/:incidentId/(seen|dismiss)`. */
@RaftCoreInternalApi fun parseSystemAttentionReceiptResponse(
    statusCode: Int,
    bodyText: String?,
    action: String,
    traceId: String? = null,
): DataResult<Unit> {
    if (statusCode in 200..299) return DataResult.Content(Unit)
    val envelope = if (statusCode > 0) parseHttpErrorEnvelope(bodyText) else null
    return DataResult.Error(
        ScreenError(
            code = "system_attention_${action}_http_$statusCode",
            retryable = statusCode >= 500,
            serverMessage = envelope?.serverMessage,
            serverCode = envelope?.serverCode,
            traceId = traceId,
        )
    )
}

private fun JsonObject.toUsageLimitAttentionNotificationOrNull(): UsageLimitAttentionNotification? {
    val id = nonBlankStringOrNull("id") ?: return null
    val kind = nonBlankStringOrNull("kind") ?: return null
    if (kind != SYSTEM_ATTENTION_KIND_USAGE_LIMIT_REACHED) return null
    val machineObj = this["machine"] as? JsonObject ?: return null
    val machineId = machineObj.nonBlankStringOrNull("id") ?: return null
    val machineName = machineObj.nonBlankStringOrNull("name") ?: return null
    val provider = nonBlankStringOrNull("provider") ?: return null
    val affectedAgentIds =
        (this["affectedAgentIds"] as? JsonArray)
            ?.mapNotNull { element ->
                (element as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.contentOrNull
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
            .orEmpty()
    return UsageLimitAttentionNotification(
        id = id,
        kind = kind,
        severity = nonBlankStringOrNull("severity") ?: "warning",
        state = nonBlankStringOrNull("state") ?: "active",
        activatedAt = nonBlankStringOrNull("activatedAt").orEmpty(),
        lastObservedAt = nonBlankStringOrNull("lastObservedAt").orEmpty(),
        seenAt = nonBlankStringOrNull("seenAt"),
        machine = UsageLimitAttentionMachine(id = machineId, name = machineName),
        provider = provider,
        windowKey = nonBlankStringOrNull("windowKey").orEmpty(),
        resetsAt = nonBlankStringOrNull("resetsAt"),
        affectedAgentIds = affectedAgentIds,
    )
}

private fun JsonObject.nonBlankStringOrNull(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun encodeSystemAttentionPathSegment(value: String): String {
    val bytes = value.encodeToByteArray()
    val out = StringBuilder(bytes.size)
    for (byte in bytes) {
        val code = byte.toInt() and 0xFF
        val unreserved =
            (code in 'A'.code..'Z'.code) ||
                (code in 'a'.code..'z'.code) ||
                (code in '0'.code..'9'.code) ||
                code == '-'.code ||
                code == '_'.code ||
                code == '.'.code ||
                code == '~'.code
        if (unreserved) {
            out.append(code.toChar())
        } else {
            out.append('%')
            out.append("0123456789ABCDEF"[code ushr 4])
            out.append("0123456789ABCDEF"[code and 0x0F])
        }
    }
    return out.toString()
}

@RaftCoreInternalApi interface UsageLimitAttentionDataSource {
    suspend fun list(serverId: ServerId): DataResult<ServerSystemAttentionSnapshot>
    suspend fun markSeen(serverId: ServerId, incidentId: String): DataResult<Unit>
    suspend fun dismiss(serverId: ServerId, incidentId: String): DataResult<Unit>
}

@RaftCoreInternalApi class NetworkUsageLimitAttentionDataSource(
    private val networkClient: RaftNetworkClient,
) : UsageLimitAttentionDataSource {
    override suspend fun list(serverId: ServerId): DataResult<ServerSystemAttentionSnapshot> =
        networkCall {
            val response =
                networkClient.request(
                    priority = DataRequestPriority.Background,
                    path = "servers/${encodeSystemAttentionPathSegment(serverId.value)}/system-notifications-v2",
                    serverIdOverride = serverId,
                )
            parseServerSystemAttentionResponse(
                statusCode = response.code,
                bodyText = if (response.isSuccessful) response.bodyText else response.errorBodyText?.takeIf { it.isNotBlank() } ?: response.bodyText,
                traceId = response.traceId,
            )
        }

    override suspend fun markSeen(serverId: ServerId, incidentId: String): DataResult<Unit> =
        networkCall {
            val response =
                networkClient.request(
                    priority = DataRequestPriority.Background,
                    path =
                        "servers/${encodeSystemAttentionPathSegment(serverId.value)}/system-notifications-v2/" +
                            "${encodeSystemAttentionPathSegment(incidentId)}/seen",
                    method = "POST",
                    serverIdOverride = serverId,
                )
            parseSystemAttentionReceiptResponse(
                statusCode = response.code,
                bodyText = if (response.isSuccessful) response.bodyText else response.errorBodyText?.takeIf { it.isNotBlank() } ?: response.bodyText,
                action = "seen",
                traceId = response.traceId,
            )
        }

    override suspend fun dismiss(serverId: ServerId, incidentId: String): DataResult<Unit> =
        networkCall {
            val response =
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path =
                        "servers/${encodeSystemAttentionPathSegment(serverId.value)}/system-notifications-v2/" +
                            "${encodeSystemAttentionPathSegment(incidentId)}/dismiss",
                    method = "POST",
                    serverIdOverride = serverId,
                )
            parseSystemAttentionReceiptResponse(
                statusCode = response.code,
                bodyText = if (response.isSuccessful) response.bodyText else response.errorBodyText?.takeIf { it.isNotBlank() } ?: response.bodyText,
                action = "dismiss",
                traceId = response.traceId,
            )
        }

    private suspend fun <T> networkCall(call: suspend () -> DataResult<T>): DataResult<T> =
        try {
            call()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "network_unavailable", retryable = true))
        }
}

@RaftCoreInternalApi sealed interface SharedUsageLimitAttentionEffect {
    data class DismissFailed(val incidentId: String, val traceId: String?) : SharedUsageLimitAttentionEffect
}

@RaftCoreInternalApi data class SharedUsageLimitAttentionState(
    /** The server whose notifications this holds; rows for another server are never shown. */
    val serverId: String? = null,
    val notifications: List<UsageLimitAttentionNotification> = emptyList(),
    val unseenCount: Int = 0,
    val effect: SharedUsageLimitAttentionEffect? = null,
)

@RaftCoreInternalApi sealed interface SharedUsageLimitAttentionIntent {
    /**
     * Load usage-limit attention notifications for [serverId]. Only owners and admins
     * ([canReceive] = true) issue network requests; members, guests, and missing servers clear
     * state immediately with zero requests.
     */
    data class Load(
        val serverId: String?,
        val canReceive: Boolean,
        val force: Boolean = false,
    ) : SharedUsageLimitAttentionIntent

    /** Pull-to-refresh or explicit reload: always reads when authorized. */
    data object Refresh : SharedUsageLimitAttentionIntent

    /** Foreground / visibility return / periodic poll: reads when last read is stale. */
    data object RefreshIfStale : SharedUsageLimitAttentionIntent

    /** Mark one unseen incident seen (optimistic update with rollback on error). */
    data class MarkSeen(val incidentId: String) : SharedUsageLimitAttentionIntent

    /** Mark every currently unseen incident seen (called when Notification Center opens). */
    data object MarkAllUnseenSeen : SharedUsageLimitAttentionIntent

    /** Dismiss one incident (optimistic removal with rollback on error). */
    data class Dismiss(val incidentId: String) : SharedUsageLimitAttentionIntent

    data object ConsumeEffect : SharedUsageLimitAttentionIntent
}

private val UsageLimitAttentionClockOrigin = TimeSource.Monotonic.markNow()

private data class PendingUsageLimitDismiss(
    val incident: UsageLimitAttentionNotification,
    val originalIndex: Int,
    /** Set to true if an authoritative replace snapshot arrives without this incident while dismiss is in flight. */
    var resolvedByServerSnapshot: Boolean = false,
)

/**
 * Server-scoped store for `GET /api/servers/:id/system-notifications-v2` and receiver-scoped
 * `seen` / `dismiss` receipts (web `useUsageLimitAttention.ts`, `slock #13079`).
 *
 * - Owner/admin only: non-admin roles (`member`, `guest`) and null servers make zero requests and
 *   clear state immediately.
 * - Replace-snapshot fail-closed: HTTP/network/contract failures clear notifications so a stale
 *   card never outlives a failed authoritative read.
 * - Optimistic `markSeen` and `dismiss` update state immediately and roll back only the affected
 *   incident on failure without clobbering concurrent mutations or resurrecting server-resolved rows.
 */
@RaftCoreInternalApi class SharedUsageLimitAttentionStore(
    private val dataSource: UsageLimitAttentionDataSource,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = { UsageLimitAttentionClockOrigin.elapsedNow().inWholeMilliseconds },
    private val optimisticSeenAtProvider: () -> String = { "optimistic" },
) {
    private val mutableState = MutableStateFlow(SharedUsageLimitAttentionState())
    val state: StateFlow<SharedUsageLimitAttentionState> = mutableState.asStateFlow()

    private var activeServerId: String? = null
    private var activeCanReceive: Boolean = false
    private var serverGeneration: Long = 0L
    private var loadGeneration: Long = 0L
    private var loadJob: Job? = null
    private var lastReadStartedAtMillis: Long? = null
    private val inFlightSeenJobs = mutableMapOf<String, Job>()
    private val inFlightSeenTokens = mutableMapOf<String, String>()
    private val inFlightDismisses = mutableMapOf<String, PendingUsageLimitDismiss>()
    private val inFlightDismissJobs = mutableMapOf<String, Job>()

    fun dispatch(intent: SharedUsageLimitAttentionIntent) {
        when (intent) {
            is SharedUsageLimitAttentionIntent.Load -> load(intent)
            SharedUsageLimitAttentionIntent.Refresh -> {
                val serverId = activeServerId
                if (serverId != null && activeCanReceive) startRead(serverId, cancelInFlight = true)
            }
            SharedUsageLimitAttentionIntent.RefreshIfStale -> {
                val serverId = activeServerId
                if (serverId == null || !activeCanReceive) return
                val last = lastReadStartedAtMillis
                if (last == null || nowMillis() - last >= USAGE_LIMIT_ATTENTION_MIN_REREAD_MS) {
                    startRead(serverId, cancelInFlight = false)
                }
            }
            is SharedUsageLimitAttentionIntent.MarkSeen -> markSeen(intent.incidentId)
            SharedUsageLimitAttentionIntent.MarkAllUnseenSeen -> {
                val unseenIds = mutableState.value.notifications.filter { it.seenAt == null }.map { it.id }
                unseenIds.forEach { markSeen(it) }
            }
            is SharedUsageLimitAttentionIntent.Dismiss -> dismiss(intent.incidentId)
            SharedUsageLimitAttentionIntent.ConsumeEffect ->
                if (mutableState.value.effect != null) mutableState.value = mutableState.value.copy(effect = null)
        }
    }

    fun clear() {
        activeServerId = null
        activeCanReceive = false
        serverGeneration += 1L
        loadGeneration += 1L
        cancelAllJobs()
        lastReadStartedAtMillis = null
        clearState(null)
    }

    private fun load(intent: SharedUsageLimitAttentionIntent.Load) {
        val cleanServerId = intent.serverId?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanServerId == null || !intent.canReceive) {
            val changed = activeServerId != cleanServerId || activeCanReceive
            activeServerId = cleanServerId
            activeCanReceive = false
            if (changed || loadJob != null || inFlightSeenJobs.isNotEmpty() || inFlightDismissJobs.isNotEmpty()) {
                serverGeneration += 1L
                loadGeneration += 1L
                cancelAllJobs()
                lastReadStartedAtMillis = null
            }
            clearState(cleanServerId)
            return
        }
        val serverChanged = activeServerId != cleanServerId
        val newlyAuthorized = !activeCanReceive
        activeServerId = cleanServerId
        activeCanReceive = true
        if (serverChanged || newlyAuthorized) {
            serverGeneration += 1L
            loadGeneration += 1L
            cancelAllJobs()
            lastReadStartedAtMillis = null
            clearState(cleanServerId)
            startRead(cleanServerId, cancelInFlight = true)
            return
        }
        if (intent.force) {
            startRead(cleanServerId, cancelInFlight = true)
        } else if (loadJob?.isActive != true && lastReadStartedAtMillis == null) {
            startRead(cleanServerId, cancelInFlight = false)
        }
    }

    private fun startRead(serverId: String, cancelInFlight: Boolean) {
        if (!cancelInFlight && loadJob?.isActive == true) return
        loadJob?.cancel()
        loadGeneration += 1L
        val mineLoad = loadGeneration
        val mineServer = serverGeneration
        lastReadStartedAtMillis = nowMillis()
        loadJob =
            scope.launch {
                val result = runAttentionCall { dataSource.list(ServerId(serverId)) }
                if (mineLoad != loadGeneration || mineServer != serverGeneration || !activeCanReceive || activeServerId != serverId) {
                    return@launch
                }
                loadJob = null
                when (result) {
                    is DataResult.Content -> {
                        val incomingIds = result.data.notifications.map { it.id }.toSet()
                        inFlightDismisses.forEach { (id, pending) ->
                            if (id !in incomingIds) pending.resolvedByServerSnapshot = true
                        }
                        val merged =
                            result.data.notifications
                                .filterNot { it.id in inFlightDismisses }
                                .map { item ->
                                    val optimisticSeen = inFlightSeenTokens[item.id]
                                    if (item.seenAt == null && optimisticSeen != null) {
                                        item.copy(seenAt = optimisticSeen)
                                    } else {
                                        item
                                    }
                                }
                        publishNotifications(serverId, merged)
                    }
                    else -> {
                        // Fail closed (web useUsageLimitAttention.ts): a stale card must never
                        // outlive an authoritative replace snapshot failure.
                        inFlightDismisses.values.forEach { it.resolvedByServerSnapshot = true }
                        publishNotifications(serverId, emptyList())
                    }
                }
            }
    }

    private fun markSeen(incidentId: String) {
        val cleanId = incidentId.trim().takeIf { it.isNotEmpty() } ?: return
        val serverId = activeServerId ?: return
        if (!activeCanReceive) return
        if (cleanId in inFlightSeenJobs || cleanId in inFlightDismisses) return
        val current = mutableState.value
        if (current.serverId != serverId) return
        val target = current.notifications.firstOrNull { it.id == cleanId } ?: return
        if (target.seenAt != null) return

        val mineServer = serverGeneration
        val optimisticSeenAt = optimisticSeenAtProvider()
        inFlightSeenTokens[cleanId] = optimisticSeenAt
        val updated =
            current.notifications.map { item ->
                if (item.id == cleanId && item.seenAt == null) item.copy(seenAt = optimisticSeenAt) else item
            }
        publishNotifications(serverId, updated)

        val job =
            scope.launch {
                val result = runAttentionCall { dataSource.markSeen(ServerId(serverId), cleanId) }
                if (mineServer != serverGeneration || !activeCanReceive || activeServerId != serverId) {
                    return@launch
                }
                inFlightSeenJobs.remove(cleanId)
                val token = inFlightSeenTokens.remove(cleanId)
                if (result !is DataResult.Content) {
                    val latest = mutableState.value
                    if (latest.serverId == serverId) {
                        val rolledBack =
                            latest.notifications.map { item ->
                                // Revert only if still holding our optimistic token (never clobber a server-confirmed seenAt).
                                if (item.id == cleanId && item.seenAt == token) item.copy(seenAt = null) else item
                            }
                        publishNotifications(serverId, rolledBack)
                    }
                }
            }
        if (job.isActive) inFlightSeenJobs[cleanId] = job
    }

    private fun dismiss(incidentId: String) {
        val cleanId = incidentId.trim().takeIf { it.isNotEmpty() } ?: return
        val serverId = activeServerId ?: return
        if (!activeCanReceive) return
        if (cleanId in inFlightDismisses) return
        val current = mutableState.value
        if (current.serverId != serverId) return
        val index = current.notifications.indexOfFirst { it.id == cleanId }
        if (index < 0) return

        val removed = current.notifications[index]
        val optimisticSeenToken = inFlightSeenTokens.remove(cleanId)
        inFlightSeenJobs.remove(cleanId)?.cancel()
        val rollbackIncident =
            if (optimisticSeenToken != null && removed.seenAt == optimisticSeenToken) {
                removed.copy(seenAt = null)
            } else {
                removed
            }
        val pending = PendingUsageLimitDismiss(incident = rollbackIncident, originalIndex = index)
        val mineServer = serverGeneration
        inFlightDismisses[cleanId] = pending
        val remaining = current.notifications.filterNot { it.id == cleanId }
        publishNotifications(serverId, remaining, clearEffect = true)

        val job =
            scope.launch {
                val result = runAttentionCall { dataSource.dismiss(ServerId(serverId), cleanId) }
                if (mineServer != serverGeneration || !activeCanReceive || activeServerId != serverId) {
                    return@launch
                }
                inFlightDismissJobs.remove(cleanId)
                val entry = inFlightDismisses.remove(cleanId) ?: return@launch
                if (result !is DataResult.Content) {
                    val latest = mutableState.value
                    if (latest.serverId == serverId && !entry.resolvedByServerSnapshot && latest.notifications.none { it.id == cleanId }) {
                        val restored = latest.notifications.toMutableList()
                        restored.add(entry.originalIndex.coerceIn(0, restored.size), entry.incident)
                        mutableState.value =
                            latest.copy(
                                notifications = restored,
                                unseenCount = restored.count { it.seenAt == null },
                                effect = SharedUsageLimitAttentionEffect.DismissFailed(cleanId, result.failureTraceIdOrNull()),
                            )
                    } else if (latest.serverId == serverId) {
                        mutableState.value =
                            latest.copy(
                                effect = SharedUsageLimitAttentionEffect.DismissFailed(cleanId, result.failureTraceIdOrNull()),
                            )
                    }
                }
            }
        if (job.isActive) inFlightDismissJobs[cleanId] = job
    }

    private fun publishNotifications(
        serverId: String,
        notifications: List<UsageLimitAttentionNotification>,
        clearEffect: Boolean = false,
    ) {
        val current = mutableState.value
        val next =
            current.copy(
                serverId = serverId,
                notifications = notifications,
                unseenCount = notifications.count { it.seenAt == null },
                effect = if (clearEffect) null else current.effect,
            )
        if (current != next) mutableState.value = next
    }

    private fun clearState(serverId: String?) {
        val current = mutableState.value
        val next = SharedUsageLimitAttentionState(serverId = serverId)
        if (current != next) mutableState.value = next
    }

    private fun cancelAllJobs() {
        loadJob?.cancel()
        loadJob = null
        inFlightSeenJobs.values.forEach { it.cancel() }
        inFlightSeenJobs.clear()
        inFlightSeenTokens.clear()
        inFlightDismissJobs.values.forEach { it.cancel() }
        inFlightDismissJobs.clear()
        inFlightDismisses.clear()
    }
}

private suspend fun <T> runAttentionCall(call: suspend () -> DataResult<T>): DataResult<T> =
    try {
        call()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        DataResult.Error(ScreenError(code = "network_unavailable", retryable = true))
    }
