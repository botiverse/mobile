package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * REST transport, 30s per-agent status cache, audited View Context fetcher, and
 * Settings › Administration Context Access grants repository
 * (`botiverse/slock` #12980, #13548, #13550, #14072, #14560).
 */

interface RaftAgentContextViewApi {
    /** `GET /api/agents/:id/context-view/status` */
    suspend fun status(agentId: String, serverId: ServerId? = null): HomeNetworkResponse

    /** `GET /api/agents/:id/messages/:messageId/context` */
    suspend fun viewContext(agentId: String, messageId: String, serverId: ServerId? = null): HomeNetworkResponse

    /** `GET /api/agents/:id/context-views?messageId=:messageId` */
    suspend fun viewedBy(agentId: String, messageId: String, serverId: ServerId? = null): HomeNetworkResponse

    /** `GET /api/servers/:id/agent-context-view-grants` */
    suspend fun listGrants(serverId: ServerId): HomeNetworkResponse

    /** `POST /api/servers/:id/agent-context-view-grants` `{ agentId }` */
    suspend fun grantAccess(serverId: ServerId, body: String): HomeNetworkResponse

    /** `DELETE /api/servers/:id/agent-context-view-grants/:grantId` */
    suspend fun revokeGrant(serverId: ServerId, grantId: String): HomeNetworkResponse
}

class RaftAgentContextViewNetworkApi(
    private val networkClient: RaftNetworkClient,
) : RaftAgentContextViewApi {
    override suspend fun status(agentId: String, serverId: ServerId?): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agents/${encodeAgentContextPathSegment(agentId)}/context-view/status",
            serverIdOverride = serverId,
        )

    override suspend fun viewContext(
        agentId: String,
        messageId: String,
        serverId: ServerId?,
    ): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path =
                "agents/${encodeAgentContextPathSegment(agentId)}/messages/" +
                    "${encodeAgentContextPathSegment(messageId)}/context",
            serverIdOverride = serverId,
        )

    override suspend fun viewedBy(
        agentId: String,
        messageId: String,
        serverId: ServerId?,
    ): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "agents/${encodeAgentContextPathSegment(agentId)}/context-views",
            query = listOf("messageId" to messageId),
            serverIdOverride = serverId,
        )

    override suspend fun listGrants(serverId: ServerId): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "servers/${encodeAgentContextPathSegment(serverId.value)}/agent-context-view-grants",
            serverIdOverride = serverId,
        )

    override suspend fun grantAccess(serverId: ServerId, body: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "servers/${encodeAgentContextPathSegment(serverId.value)}/agent-context-view-grants",
            method = "POST",
            serverIdOverride = serverId,
            bodyText = body,
        )

    override suspend fun revokeGrant(serverId: ServerId, grantId: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path =
                "servers/${encodeAgentContextPathSegment(serverId.value)}/agent-context-view-grants/" +
                    encodeAgentContextPathSegment(grantId),
            method = "DELETE",
            serverIdOverride = serverId,
        )
}

@Serializable
private data class AgentContextViewGrantCreateBody(
    val agentId: String,
)

private val agentContextViewRequestJson = Json {
    encodeDefaults = true
}

internal fun encodeAgentContextViewGrantCreateBody(agentId: String): String =
    agentContextViewRequestJson.encodeToString(AgentContextViewGrantCreateBody(agentId = agentId))

internal fun encodeAgentContextPathSegment(value: String): String {
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
            out.append(HEX_DIGITS[code ushr 4])
            out.append(HEX_DIGITS[code and 0x0F])
        }
    }
    return out.toString()
}

private const val HEX_DIGITS = "0123456789ABCDEF"

/**
 * Per-agent in-memory status cache (30s TTL for 200 and 404, one request at a time per agent,
 * never caches network/5xx failures; web `agentContextViewStatus.ts`).
 */
class AgentContextViewStatusStore(
    private val api: RaftAgentContextViewApi,
    private val nowMillis: () -> Long = ::defaultNowMillis,
    private val ttlMillis: Long = AGENT_CONTEXT_VIEW_STATUS_TTL_MS,
) {
    private val lock = SynchronizedObject()
    private val inFlight = mutableMapOf<String, CompletableDeferred<AgentContextViewStatusEntry>>()
    private val entriesFlow = MutableStateFlow<Map<String, AgentContextViewStatusEntry>>(emptyMap())

    val entries: StateFlow<Map<String, AgentContextViewStatusEntry>> = entriesFlow.asStateFlow()

    fun cacheKey(agentId: String, serverId: ServerId? = null): String =
        "${serverId?.value.orEmpty()}:$agentId"

    fun entryFor(agentId: String, serverId: ServerId? = null): AgentContextViewStatusEntry? =
        entriesFlow.value[cacheKey(agentId, serverId)]

    suspend fun loadStatus(
        agentId: String,
        serverId: ServerId? = null,
        force: Boolean = false,
    ): AgentContextViewStatusEntry {
        val cleanAgentId = agentId.trim()
        if (cleanAgentId.isEmpty()) {
            return AgentContextViewStatusEntry(gone = true, atMillis = nowMillis())
        }
        val key = cacheKey(cleanAgentId, serverId)
        val existingDeferred: CompletableDeferred<AgentContextViewStatusEntry>?
        val ownedDeferred: CompletableDeferred<AgentContextViewStatusEntry>?
        synchronized(lock) {
            val running = inFlight[key]
            if (running != null) {
                existingDeferred = running
                ownedDeferred = null
            } else {
                val cached = entriesFlow.value[key]
                if (!force && cached != null && cached.isFresh(nowMillis(), ttlMillis)) {
                    return cached
                }
                val created = CompletableDeferred<AgentContextViewStatusEntry>()
                inFlight[key] = created
                existingDeferred = null
                ownedDeferred = created
            }
        }
        if (existingDeferred != null) {
            return existingDeferred.await()
        }
        val deferred = ownedDeferred!!
        patch(key) { current -> (current ?: AgentContextViewStatusEntry()).copy(pending = true) }
        val result =
            try {
                val response = api.status(cleanAgentId, serverId)
                when {
                    response.code == 404 ->
                        AgentContextViewStatusEntry(
                            status = null,
                            atMillis = nowMillis(),
                            pending = false,
                            failed = false,
                            gone = true,
                        )
                    response.isSuccessful -> {
                        val parsed = response.bodyText?.let(::parseAgentContextViewStatus)
                        if (parsed != null) {
                            AgentContextViewStatusEntry(
                                status = parsed,
                                atMillis = nowMillis(),
                                pending = false,
                                failed = false,
                                gone = false,
                            )
                        } else {
                            AgentContextViewStatusEntry(
                                status = null,
                                atMillis = null,
                                pending = false,
                                failed = true,
                                gone = false,
                                traceId = response.traceId,
                            )
                        }
                    }
                    else ->
                        AgentContextViewStatusEntry(
                            status = null,
                            atMillis = null,
                            pending = false,
                            failed = true,
                            gone = false,
                            traceId = response.traceId,
                        )
                }
            } catch (cancellation: CancellationException) {
                synchronized(lock) {
                    if (inFlight[key] === deferred) inFlight.remove(key)
                }
                patch(key) { current -> current?.copy(pending = false) ?: AgentContextViewStatusEntry() }
                deferred.cancel(cancellation)
                throw cancellation
            } catch (_: Throwable) {
                AgentContextViewStatusEntry(
                    status = null,
                    atMillis = null,
                    pending = false,
                    failed = true,
                    gone = false,
                )
            }
        synchronized(lock) {
            if (inFlight[key] === deferred) inFlight.remove(key)
        }
        patch(key) { result }
        deferred.complete(result)
        return result
    }

    /** Records a 404 from the view endpoint so the menu stops offering View Context for the TTL. */
    fun markGone(agentId: String, serverId: ServerId? = null) {
        val cleanAgentId = agentId.trim()
        if (cleanAgentId.isEmpty()) return
        val key = cacheKey(cleanAgentId, serverId)
        patch(key) {
            AgentContextViewStatusEntry(
                status = null,
                atMillis = nowMillis(),
                pending = false,
                failed = false,
                gone = true,
            )
        }
    }

    fun clearAll() {
        val pendingDeferreds =
            synchronized(lock) {
                val copy = inFlight.values.toList()
                inFlight.clear()
                copy
            }
        pendingDeferreds.forEach { it.cancel() }
        entriesFlow.value = emptyMap()
    }

    private fun patch(
        key: String,
        transform: (AgentContextViewStatusEntry?) -> AgentContextViewStatusEntry,
    ) {
        entriesFlow.update { current -> current + (key to transform(current[key])) }
    }
}

/**
 * Audited View Context fetcher + Viewed-by loader (`GET /agents/:id/messages/:messageId/context`,
 * `GET /agents/:id/context-views?messageId=`). Context payloads are never cached: every open and
 * every Retry is an audited server request (web `agentContextViewResult.ts`).
 */
class AgentContextViewRepository(
    private val api: RaftAgentContextViewApi,
    val statusStore: AgentContextViewStatusStore,
) {
    suspend fun fetchContext(
        agentId: String,
        messageId: String,
        serverId: ServerId? = null,
    ): AgentContextViewAnswer {
        val response =
            try {
                api.viewContext(agentId, messageId, serverId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return AgentContextViewAnswer.Failed(reason = AgentContextViewFailureReason.ReadFailed)
            }
        if (response.code == 404) {
            statusStore.markGone(agentId, serverId)
            return AgentContextViewAnswer.Gone
        }
        if (!response.isSuccessful) {
            return AgentContextViewAnswer.Failed(
                reason = AgentContextViewFailureReason.ReadFailed,
                traceId = response.traceId,
            )
        }
        return parseAgentContextViewAnswer(response.bodyText, traceId = response.traceId)
    }

    /**
     * Fetches `Viewed by` records for [messageId], patching the viewer's newest non-agent row with
     * [ownOutcome] when this panel just attempted the view (matching web `loadViewedBy`).
     * Returns `null` when the footnote request fails (never blocks showing the context).
     */
    suspend fun fetchViewedBy(
        agentId: String,
        messageId: String,
        viewerUserId: String? = null,
        ownOutcome: AgentContextViewOutcome? = null,
        serverId: ServerId? = null,
    ): List<AgentContextViewRecord>? {
        val response =
            try {
                api.viewedBy(agentId, messageId, serverId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return null
            }
        if (!response.isSuccessful) return null
        val rows = response.bodyText?.let(::parseAgentContextViewRecords) ?: return null
        return patchOwnViewedByOutcome(rows, viewerUserId, ownOutcome)
    }
}

fun patchOwnViewedByOutcome(
    rows: List<AgentContextViewRecord>,
    viewerUserId: String?,
    ownOutcome: AgentContextViewOutcome?,
): List<AgentContextViewRecord> {
    if (viewerUserId.isNullOrBlank() || ownOutcome == null) return rows
    val ownIndex = rows.indexOfFirst { !it.viewerIsAgent && it.viewer?.id == viewerUserId }
    if (ownIndex < 0) return rows
    return rows.mapIndexed { index, row ->
        if (index == ownIndex) {
            row.copy(outcome = ownOutcome, outcomeRaw = ownOutcome.wireValue)
        } else {
            row
        }
    }
}

/**
 * Settings › Administration › Context access for agents (`botiverse/slock` #13548 / #13550).
 */
class AgentContextViewGrantsRepository(
    private val api: RaftAgentContextViewApi,
) {
    suspend fun listGrants(serverId: ServerId): DataResult<List<AgentContextViewGrant>> {
        val response =
            try {
                api.listGrants(serverId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "context_view_grants_load_failed"))
            }
        if (!response.isSuccessful) {
            return response.toGrantsError("context_view_grants_load_failed")
        }
        val parsed = response.bodyText?.let(::parseAgentContextViewGrantList)
            ?: return DataResult.Error(
                ScreenError(
                    code = "context_view_grants_malformed",
                    traceId = response.traceId,
                ),
            )
        return DataResult.Content(parsed)
    }

    suspend fun grantAccess(
        serverId: ServerId,
        agentId: String,
    ): DataResult<AgentContextViewGrantCreated> {
        val response =
            try {
                api.grantAccess(serverId, encodeAgentContextViewGrantCreateBody(agentId))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "context_view_grant_create_failed"))
            }
        if (!response.isSuccessful) {
            return response.toGrantsError("context_view_grant_create_failed")
        }
        val parsed = response.bodyText?.let(::parseAgentContextViewGrantCreated)
            ?: return DataResult.Error(
                ScreenError(
                    code = "context_view_grant_create_malformed",
                    traceId = response.traceId,
                ),
            )
        return DataResult.Content(parsed)
    }

    /**
     * Revoke a grant:
     * - 200 `{ ok: true, revoked: true }` -> [AgentContextViewGrantRevokeOutcome.Revoked]
     * - 200 `{ ok: true, revoked: false }` -> [AgentContextViewGrantRevokeOutcome.Gone]
     * - 404 with refusal code `context_view_grant_not_found` -> [AgentContextViewGrantRevokeOutcome.Gone]
     * - Any other 404 (e.g. flag off or `context_view_grants_unavailable`) or error -> [DataResult.Error]
     */
    suspend fun revokeGrant(
        serverId: ServerId,
        grantId: String,
    ): DataResult<AgentContextViewGrantRevokeOutcome> {
        val response =
            try {
                api.revokeGrant(serverId, grantId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "context_view_grant_revoke_failed"))
            }
        if (response.code == 404) {
            val refusal = parseAgentContextViewGrantRefusal(response.errorBodyText ?: response.bodyText)
            if (refusal?.code == "context_view_grant_not_found") {
                return DataResult.Content(AgentContextViewGrantRevokeOutcome.Gone)
            }
            return response.toGrantsError("context_view_grant_revoke_failed")
        }
        if (!response.isSuccessful) {
            return response.toGrantsError("context_view_grant_revoke_failed")
        }
        val revoked = response.bodyText?.let(::parseAgentContextViewGrantRevoked)
            ?: return DataResult.Error(
                ScreenError(
                    code = "context_view_grant_revoke_malformed",
                    traceId = response.traceId,
                ),
            )
        return DataResult.Content(
            if (revoked) AgentContextViewGrantRevokeOutcome.Revoked else AgentContextViewGrantRevokeOutcome.Gone,
        )
    }

    private fun HomeNetworkResponse.toGrantsError(fallbackCode: String): DataResult.Error {
        val refusal = parseAgentContextViewGrantRefusal(errorBodyText ?: bodyText)
        return DataResult.Error(
            ScreenError(
                code = refusal?.code?.takeIf { it.isNotBlank() } ?: fallbackCode,
                retryable = code >= 500 || code == 0,
                serverMessage = refusal?.error?.takeIf { it.isNotBlank() },
                serverCode = refusal?.code?.takeIf { it.isNotBlank() },
                traceId = traceId,
            ),
        )
    }
}
