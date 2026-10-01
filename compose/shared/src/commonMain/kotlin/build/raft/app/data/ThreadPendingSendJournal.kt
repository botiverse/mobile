package build.raft.app.data

import build.raft.app.KuiklyThreadMessageItem
import build.raft.app.KuiklyThreadPendingSendContext
import build.raft.app.ReplyCacheProvenance
import build.raft.app.ThreadMessageDeliveryFailed
import build.raft.app.ThreadMessageDeliverySending
import build.raft.app.ThreadRouteIdentity
import build.raft.app.ThreadTransientLocalSendIdPrefix
import build.raft.app.isPendingSendEquivalentTo
import build.raft.app.isValidPendingSendRoute
import build.raft.app.isTransientLocalSendingMessage
import build.raft.app.threadPendingSendOwnerScopeId
import build.raft.app.model.ConversationKind
import build.raft.app.shared.data.local.ThreadPendingSendEntity
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The durable client-owned send journal.  This is intentionally separate from
 * canonical message facts and from route/pageData: a pending send is a local
 * intent projection until the server confirms it.
 */
internal class ThreadPendingSendJournal(
    private val database: SlockSqlDatabase,
    private val nowMillis: () -> Long = ::defaultNowMillis,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    /**
     * Deterministic storage-failure seam for JVM integration tests.  Production callers leave
     * this null; the journal still treats every real serialization/SQL failure as fail-closed.
     */
    private val failureInjector: ThreadPendingSendJournalFailureInjector? = null,
) {
    internal enum class ConsumeResult {
        /** Exactly one durable row was deleted. */
        Deleted,
        /** No row matched the complete identity and route fence. */
        NotFound,
        /** The storage operation failed or affected an unexpected number of rows. */
        Failed,
    }

    private val lock = SynchronizedObject()

    fun persistSending(
        provenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        message: KuiklyThreadMessageItem,
    ): Boolean = synchronized(lock) {
        val ownerScopeId = provenance.threadPendingSendOwnerScopeId ?: return@synchronized false
        // A route is part of the durable identity, not presentation metadata.
        // In particular, a reply send without a typed parent must fail closed
        // instead of becoming recoverable under a later Channel/DM guess.
        if (!route.hasCompleteReplyIdentity || !route.isValidPendingSendRoute()) return@synchronized false
        val localId = message.id.trim().takeIf { it.isNotBlank() } ?: return@synchronized false
        val context = message.pendingSendContext ?: return@synchronized false
        val randomId = context.randomId?.trim()?.takeIf { it.isNotBlank() } ?: return@synchronized false
        val isExplicitRetry =
            message.id.startsWith(ThreadTransientLocalSendIdPrefix) &&
                message.deliveryState.equals(ThreadMessageDeliveryFailed, ignoreCase = true)
        if (!message.isTransientLocalSendingMessage() && !isExplicitRetry) {
            return@synchronized false
        }
        if (failureInjector?.rejectAdmissionSafely() == true) return@synchronized false
        var admitted = false
        runCatching {
            // Serialization is part of durable admission.  Keep it inside the same fail-closed
            // boundary as the SQL transaction: a malformed/unsupported optimistic projection must
            // not reach the UI or network as if it had been journaled.
            val messageJson = json.encodeToString(message)
            val contextJson = json.encodeToString(context)
            val createdAtMs = message.createdAtEpochMillis ?: nowMillis()
            // Candidate inspection and the write must share one SQLite transaction.  The
            // per-instance monitor protects callers in one process; the DB transaction is the
            // fence for a second journal instance (or a second process) racing this admission.
            database.transactionBlocking {
                val existing =
                    candidateRows(provenance, ownerScopeId)
                        .filter { row -> row.localId == localId || row.randomId == randomId }
                if (existing.size > 1) return@transactionBlocking
                val retainedRoute = existing.singleOrNull()?.also { row ->
                    if (
                        row.localId != localId ||
                        row.randomId != randomId ||
                        !row.matchesRoute(route)
                    ) {
                        return@transactionBlocking
                    }
                }
                if (retainedRoute != null) {
                    val updated = database.queries.updateThreadPendingSendSending(
                        messageJson = messageJson,
                        pendingContextJson = contextJson,
                        createdAtMs = createdAtMs,
                        environmentId = provenance.environmentId,
                        principalId = provenance.principalId,
                        serverId = provenance.serverId,
                        ownerScopeId = ownerScopeId,
                        authGeneration = provenance.authGeneration,
                        selectionGeneration = provenance.selectionGeneration,
                        routeInstanceKey = retainedRoute.routeInstanceKey,
                        localId = localId,
                        randomId = randomId,
                    ).value
                    check(updated == 1L) { "pending-send retry update affected $updated rows" }
                } else {
                    database.queries.insertThreadPendingSend(
                        environmentId = provenance.environmentId,
                        principalId = provenance.principalId,
                        serverId = provenance.serverId,
                        ownerScopeId = ownerScopeId,
                        authGeneration = provenance.authGeneration,
                        selectionGeneration = provenance.selectionGeneration,
                        routeInstanceKey = route.instanceKey,
                        routeSurfaceKind = route.surfaceKind,
                        routeChannelKind = route.channelKind,
                        routeParentConversationKind = route.parentConversationKind?.name,
                        routeTimelineKey = route.timelineKey,
                        routeParentKey = route.parentKey,
                        localId = localId,
                        randomId = randomId,
                        messageJson = messageJson,
                        pendingContextJson = contextJson,
                        status = "sending",
                        createdAtMs = createdAtMs,
                        lastErrorCode = null,
                        lastErrorMessage = null,
                    )
                }
                admitted = true
            }
        }.onFailure { admitted = false }
        admitted
    }

    /**
     * Restore is the drain/render visibility path: durable rows belong to the stable
     * identity (environment, account, server). Generation and owner-scope columns only
     * fence in-process concurrent writes — a journaled orphan must stay visible after a
     * process restart, a server re-selection (selection epoch bump), or an auth
     * rotation, otherwise it becomes an unadoptable zombie.
     */
    fun restore(
        provenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
    ): List<KuiklyThreadMessageItem> = synchronized(lock) {
        runCatching {
            stableIdentityRows(provenance)
                .mapNotNull { row -> decodeAndValidate(row, route) }
        }.getOrElse { emptyList() }
    }

    /**
     * Drain-path failure marking with stable-identity visibility (same reasoning as
     * [restore]): the drainer only calls this once no live attempt owns the row, so the
     * in-process write fence must not hide cross-generation orphans. Live send-failure
     * marking keeps using the generation-fenced [markFailed].
     */
    fun markFailedForDrain(
        provenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        localId: String,
        errorCode: String?,
        errorMessage: String?,
    ): Boolean = synchronized(lock) {
        runCatching {
            val row =
                stableIdentityRows(provenance)
                    .filter { candidate -> candidate.localId == localId && candidate.matchesRoute(route) }
                    .singleOrNull()
                    ?: return@runCatching false
            database.queries.updateThreadPendingSendFailureForStableIdentity(
                lastErrorCode = errorCode,
                lastErrorMessage = errorMessage,
                environmentId = provenance.environmentId,
                principalId = provenance.principalId,
                serverId = provenance.serverId,
                routeInstanceKey = row.routeInstanceKey,
                localId = localId,
            ).value == 1L
        }.getOrDefault(false)
    }

    fun markFailed(
        provenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        localId: String,
        errorCode: String?,
        errorMessage: String?,
    ): Boolean = synchronized(lock) {
        val ownerScopeId = provenance.threadPendingSendOwnerScopeId ?: return@synchronized false
        runCatching {
            val row =
                candidateRows(provenance, ownerScopeId)
                    .filter { candidate -> candidate.localId == localId && candidate.matchesRoute(route) }
                    .singleOrNull()
                    ?: return@runCatching false
            database.queries.updateThreadPendingSendFailure(
                lastErrorCode = errorCode,
                lastErrorMessage = errorMessage,
                environmentId = provenance.environmentId,
                principalId = provenance.principalId,
                serverId = provenance.serverId,
                ownerScopeId = ownerScopeId,
                authGeneration = provenance.authGeneration,
                selectionGeneration = provenance.selectionGeneration,
                routeInstanceKey = row.routeInstanceKey,
                localId = localId,
            ).value == 1L
        }.getOrDefault(false)
    }

    fun consume(
        provenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        localId: String,
    ): ConsumeResult = synchronized(lock) {
        val ownerScopeId = provenance.threadPendingSendOwnerScopeId ?: return@synchronized ConsumeResult.Failed
        if (failureInjector?.rejectDeleteSafely() == true) return@synchronized ConsumeResult.Failed
        var result = ConsumeResult.NotFound
        runCatching {
            database.transactionBlocking {
                val row =
                    candidateRows(provenance, ownerScopeId)
                        .filter { candidate -> candidate.localId == localId && candidate.matchesRoute(route) }
                        .singleOrNull()
                        ?: return@transactionBlocking
                val deleted = database.queries.deleteThreadPendingSend(
                    environmentId = provenance.environmentId,
                    principalId = provenance.principalId,
                    serverId = provenance.serverId,
                    ownerScopeId = ownerScopeId,
                    authGeneration = provenance.authGeneration,
                    selectionGeneration = provenance.selectionGeneration,
                    routeInstanceKey = row.routeInstanceKey,
                    localId = localId,
                ).value
                check(deleted == 1L) { "pending-send delete affected $deleted rows" }
                result = ConsumeResult.Deleted
            }
        }.onFailure { result = ConsumeResult.Failed }
        result
    }

    /** Consume a canonical echo exactly once, regardless of which merge path saw it first. */
    fun consumeCanonicalRandomId(
        provenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        randomId: String,
    ): ConsumeResult = synchronized(lock) {
        val cleanRandomId =
            randomId.trim().takeIf { it.isNotBlank() }
                ?: return@synchronized ConsumeResult.NotFound
        // Canonical echoes are authoritative for the stable identity: a journaled row
        // written under an older generation/scope must still be consumed, otherwise the
        // echo leaves a zombie that later sends would duplicate (server dedupes by
        // randomId, so a stable-identity delete is safe).
        if (!provenance.hasCompleteStableIdentity()) return@synchronized ConsumeResult.Failed
        if (failureInjector?.rejectDeleteSafely() == true) return@synchronized ConsumeResult.Failed
        var result = ConsumeResult.NotFound
        runCatching {
            database.transactionBlocking {
                val row =
                    stableIdentityRows(provenance)
                        .filter { candidate ->
                            candidate.randomId == cleanRandomId && candidate.matchesRoute(route)
                        }
                        .singleOrNull()
                        ?: return@transactionBlocking
                val deleted = database.queries.deleteThreadPendingSendByRandomIdForStableIdentity(
                    environmentId = provenance.environmentId,
                    principalId = provenance.principalId,
                    serverId = provenance.serverId,
                    routeInstanceKey = row.routeInstanceKey,
                    randomId = cleanRandomId,
                ).value
                check(deleted == 1L) { "pending-send randomId delete affected $deleted rows" }
                result = ConsumeResult.Deleted
            }
        }.onFailure { result = ConsumeResult.Failed }
        result
    }

    fun clearForAuthRevision(
        environmentId: String,
        principalId: String,
        authGeneration: Long,
    ): Boolean = synchronized(lock) {
        if (environmentId.isBlank() || principalId.isBlank() || authGeneration < 0L) {
            return@synchronized false
        }
        runCatching {
            database.queries.deleteThreadPendingSendsForAuthRevision(
                environmentId = environmentId,
                principalId = principalId,
                authGeneration = authGeneration,
            )
        }.isSuccess
    }

    /**
     * Same-binding token rotation (401 -> silent refresh, authGeneration+1 with the binding
     * unchanged): move the principal's rows from the retired auth revision into the successor
     * revision instead of deleting them, so a journaled pending send stays reachable for
     * restore, drain, and terminal cleanup. This mirrors
     * [ReplyCacheProvenance.isCurrentOrSuccessorForLocalSend]: environment/principal are the
     * binding identity here, each row keeps its own serverId/selectionGeneration fence, and a
     * principal/server switch still goes through [clearForAuthRevision] (delete semantics).
     *
     * ownerScopeId embeds authGeneration, so every rehomed row gets a freshly joined scope id
     * from its own columns. Returns the number of rehomed rows (0 on any failure).
     */
    fun rehomeAuthRevision(
        environmentId: String,
        principalId: String,
        fromAuthGeneration: Long,
        toAuthGeneration: Long,
    ): Int = synchronized(lock) {
        if (
            environmentId.isBlank() || principalId.isBlank() ||
            fromAuthGeneration <= 0L || toAuthGeneration <= fromAuthGeneration
        ) {
            return@synchronized 0
        }
        var rehomed = 0
        val succeeded =
            runCatching {
                database.transactionBlocking {
                    val rows =
                        database.queries.selectThreadPendingSendsForAuthRevision(
                            environmentId = environmentId,
                            principalId = principalId,
                            authGeneration = fromAuthGeneration,
                        ).executeAsList()
                    rows.forEach { row ->
                        val newOwnerScopeId =
                            listOf(
                                row.environmentId,
                                row.principalId,
                                row.serverId,
                                toAuthGeneration.toString(),
                                row.selectionGeneration.toString()
                            ).joinToString("\u001f")
                        database.queries.rehomeThreadPendingSendAuthRevision(
                            newAuthGeneration = toAuthGeneration,
                            newOwnerScopeId = newOwnerScopeId,
                            environmentId = row.environmentId,
                            principalId = row.principalId,
                            serverId = row.serverId,
                            oldOwnerScopeId = row.ownerScopeId,
                            oldAuthGeneration = fromAuthGeneration,
                            selectionGeneration = row.selectionGeneration,
                            routeInstanceKey = row.routeInstanceKey,
                            localId = row.localId,
                        )
                        rehomed += 1
                    }
                }
            }.isSuccess
        // The count is only meaningful when the transaction committed; a rolled-back
        // transaction rehomed nothing.
        if (succeeded) rehomed else 0
    }

    /**
     * Retire every persisted generation for a principal strictly older than
     * the exact generation currently observed by the authority binding.  This is used
     * on process recreation, where there is no in-memory predecessor to feed
     * [clearForAuthRevision].  Keeping the current and any future generation
     * is essential: a cold-start restore must not erase sends admitted by a
     * successor session while cleaning stale rows left by an older process.
     */
    fun clearForPrincipalBeforeAuthRevision(
        environmentId: String,
        principalId: String,
        authGeneration: Long,
    ): Boolean = synchronized(lock) {
        if (environmentId.isBlank() || principalId.isBlank() || authGeneration < 0L) {
            return@synchronized false
        }
        runCatching {
            database.queries.deleteThreadPendingSendsForPrincipalBeforeAuthRevision(
                environmentId = environmentId,
                principalId = principalId,
                authGeneration = authGeneration,
            )
        }.isSuccess
    }

    private fun candidateRows(
        provenance: ReplyCacheProvenance,
        ownerScopeId: String,
    ): List<ThreadPendingSendEntity> =
        database.queries
            .selectThreadPendingSendCandidates(
                environmentId = provenance.environmentId,
                principalId = provenance.principalId,
                serverId = provenance.serverId,
                ownerScopeId = ownerScopeId,
                authGeneration = provenance.authGeneration,
                selectionGeneration = provenance.selectionGeneration,
            )
            .executeAsList()

    /** Blank stable-identity keys fail closed, exactly like the fenced paths. */
    private fun ReplyCacheProvenance.hasCompleteStableIdentity(): Boolean =
        environmentId.isNotBlank() && principalId.isNotBlank() && serverId.isNotBlank()

    private fun stableIdentityRows(provenance: ReplyCacheProvenance): List<ThreadPendingSendEntity> =
        if (!provenance.hasCompleteStableIdentity()) {
            emptyList()
        } else {
            database.queries
            .selectThreadPendingSendForStableIdentity(
                environmentId = provenance.environmentId,
                principalId = provenance.principalId,
                serverId = provenance.serverId,
            )
            .executeAsList()
        }

    private fun ThreadPendingSendEntity.matchesRoute(route: ThreadRouteIdentity): Boolean =
        persistedRouteOrNull()?.isPendingSendEquivalentTo(route) == true

    private fun ThreadPendingSendEntity.persistedRouteOrNull(): ThreadRouteIdentity? {
        if (routeInstanceKey.isBlank() || routeSurfaceKind.isBlank()) return null
        val parentKind =
            routeParentConversationKind?.let { encoded ->
                runCatching { ConversationKind.valueOf(encoded) }.getOrNull() ?: return null
            }
        val route = ThreadRouteIdentity(
            surfaceKind = routeSurfaceKind,
            channelKind = routeChannelKind,
            parentConversationKind = parentKind,
            instanceKey = routeInstanceKey,
            timelineKey = routeTimelineKey,
            parentKey = routeParentKey,
            canRenderTimelineCandidates = !routeTimelineKey.isNullOrBlank(),
        )
        return route.takeIf { it.isValidPendingSendRoute() }
    }

    private fun decodeAndValidate(
        row: ThreadPendingSendEntity,
        route: ThreadRouteIdentity,
    ): KuiklyThreadMessageItem? {
        if (row.status != "sending" && row.status != "failed") return null
        if (!row.matchesRoute(route)) return null
        return runCatching {
            val context = json.decodeFromString<KuiklyThreadPendingSendContext>(row.pendingContextJson)
            val message = json.decodeFromString<KuiklyThreadMessageItem>(row.messageJson)
            val messageContext = message.pendingSendContext
            if (
                message.id != row.localId ||
                messageContext?.randomId?.trim() != context.randomId?.trim() ||
                row.randomId?.trim() != context.randomId?.trim() ||
                context.randomId.isNullOrBlank() ||
                messageContext == null ||
                messageContext.randomId.isNullOrBlank()
            ) return@runCatching null
            message.copy(
                pendingSendContext = context,
                randomId = row.randomId ?: context.randomId,
                deliveryState = if (row.status == "failed") ThreadMessageDeliveryFailed else ThreadMessageDeliverySending,
                pendingSendErrorCode = row.lastErrorCode,
                pendingSendErrorMessage = row.lastErrorMessage,
            )
        }.getOrNull()
    }
}

/**
 * Test-only failure injection for the durable pending-send boundary.
 *
 * These callbacks intentionally model the operation failing before SQL is attempted, which is
 * more deterministic than closing a JDBC driver underneath an already-created SQLDelight
 * database.  No production construction supplies this seam.
 */
internal data class ThreadPendingSendJournalFailureInjector(
    val rejectAdmission: () -> Boolean = { false },
    val rejectDelete: () -> Boolean = { false },
) {
    fun rejectAdmissionSafely(): Boolean = runCatching { rejectAdmission() }.getOrDefault(true)

    fun rejectDeleteSafely(): Boolean = runCatching { rejectDelete() }.getOrDefault(true)
}
