package build.raft.app.thread

import build.raft.app.ReplyCacheProvenance
import build.raft.app.SharedThreadFreshnessGeneration
import build.raft.app.SharedThreadPayloadCacheKey
import build.raft.app.ThreadRouteIdentity
import build.raft.app.data.ThreadPendingSendJournal
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.get
import build.raft.app.isCurrentOrSuccessorForLocalSend
import build.raft.app.isSameTimelineCacheAccount
import build.raft.app.messages
import build.raft.app.resolveThreadRouteIdentity
import build.raft.app.sharedThreadTimelineOwnerScopeId
import build.raft.app.thread.resolveThreadRouteIdentity
import build.raft.app.threadPayloadOwnerScopeId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The reply-cache provenance bookkeeping and pending-send journal access of
 * [SharedThreadDataSource], moved out of it unchanged; the data source forwards to them. Sole owner
 * of [replyCacheProvenanceLock] and of the state it guards (the current provenance, the latest
 * published capture sequence and the last account seen before an identity gap). The data source's
 * remaining lock-held cache paths take this same lock object; no lock is added or split.
 */
internal class ThreadReplyCacheProvenanceOwner(
    private val pendingSendJournal: ThreadPendingSendJournal?,
    private val payloadCache: SharedThreadPayloadStore,
    private val diagnosticsLogger: (String) -> Unit,
) {
    // Last identity published before an identity gap, so the account fence can compare across it.
    private var lastTimelineAccountProvenance: ReplyCacheProvenance? = null
    internal val replyCacheProvenanceLock = SynchronizedObject()
    private val replyCacheProvenance = MutableStateFlow<ReplyCacheProvenance?>(null)
    internal val replyCacheProvenanceState: StateFlow<ReplyCacheProvenance?> =
        replyCacheProvenance.asStateFlow()
    private var latestPublishedReplyCacheCapture = 0L

    internal fun pendingSendJournalOrNull(): ThreadPendingSendJournal? = pendingSendJournal

    /** Durable admission gate for a local send. Must complete before the caller starts HTTP. */
    internal fun persistPendingLocalSend(
        provenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        message: RaftThreadMessageItem,
    ): Boolean = pendingSendJournal?.persistSending(provenance, route, message) ?: true

    internal fun clearPendingLocalSend(
        provenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        localId: String,
    ): ThreadPendingSendJournal.ConsumeResult =
        pendingSendJournal?.consume(provenance, route, localId)
            ?: ThreadPendingSendJournal.ConsumeResult.NotFound

    /**
     * Terminal cleanup for an already-admitted send. The old provenance names the exact journal
     * row to retire; the live provenance is checked first and may be a same-binding token
     * successor. This keeps cleanup from becoming an escape hatch across account/server fences.
     */
    internal fun clearPendingLocalSendForLocalSend(
        expectedProvenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        localId: String,
    ): ThreadPendingSendJournal.ConsumeResult =
        synchronized(replyCacheProvenanceLock) {
            if (replyCacheProvenance.value?.isCurrentOrSuccessorForLocalSend(expectedProvenance) != true) {
                return@synchronized ThreadPendingSendJournal.ConsumeResult.Failed
            }
            pendingSendJournal?.consume(expectedProvenance, route, localId)
                ?: ThreadPendingSendJournal.ConsumeResult.NotFound
        }

    /** True when terminal local-send cleanup has a durable row to fence. */
    internal val hasDurablePendingSendJournal: Boolean
        get() = pendingSendJournal != null

    /**
     * Records a live send's failure on the send's own journal row (proj-mobile#66). Not fenced
     * on the current selection: [sendProvenance] is the identity the send was admitted under,
     * and the failure belongs to that server's channel even after the user switched away.
     */
    internal fun markPendingLocalSendFailedForSendOwner(
        sendProvenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        localId: String,
        randomId: String?,
        errorCode: String?,
        errorMessage: String?,
    ): Boolean {
        val journal = pendingSendJournal ?: return true
        val cleanRandomId = randomId?.trim()?.takeIf { it.isNotBlank() } ?: return false
        return journal.markFailedForSendOwner(
            provenance = sendProvenance,
            route = route,
            localId = localId,
            randomId = cleanRandomId,
            errorCode = errorCode,
            errorMessage = errorMessage,
        )
    }

    internal fun markPendingLocalSendFailedForLocalSend(
        expectedProvenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        localId: String,
        errorCode: String?,
        errorMessage: String?,
    ): Boolean =
        synchronized(replyCacheProvenanceLock) {
            if (replyCacheProvenance.value?.isCurrentOrSuccessorForLocalSend(expectedProvenance) != true) {
                return@synchronized false
            }
            pendingSendJournal?.markFailed(
                provenance = expectedProvenance,
                route = route,
                localId = localId,
                errorCode = errorCode,
                errorMessage = errorMessage,
            ) ?: true
        }

    internal fun restorePendingLocalSends(
        provenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        canonicalPayload: RaftThreadScreenPayload,
    ): RaftThreadScreenPayload {
        val journal = pendingSendJournal ?: return canonicalPayload
        // A cached/launch payload is only a canonical candidate. Pending rows
        // are owned by the scoped journal, so discard any compatibility shadow
        // before merging the journal projection back in.
        val canonicalProjection = canonicalPayload.withoutLocalPendingSendMessages()
        val canonicalRandomIds =
            canonicalProjection.messages
                .asSequence()
                .filter { it.pendingSendContext == null }
                .mapNotNull { it.randomId?.trim()?.takeIf(String::isNotBlank) }
                .toSet()
        val restored = journal.restore(provenance, route)
        val surviving = restored.filterNot { item ->
            val randomId = item.pendingSendContext?.randomId?.trim()?.takeIf(String::isNotBlank)
            if (randomId == null || randomId !in canonicalRandomIds) return@filterNot false
            when (journal.consumeCanonicalRandomId(provenance, route, randomId)) {
                // A canonical row is authoritative even if the local row was already consumed by
                // another merge path.  Keeping that stale shadow would render a duplicate bubble
                // until the next refresh, so both terminal success and an idempotent miss remove
                // it from this projection.
                ThreadPendingSendJournal.ConsumeResult.Deleted,
                ThreadPendingSendJournal.ConsumeResult.NotFound -> true
                // Do not drop the recovered projection merely because the durable delete failed.
                // The row remains recoverable on the next launch and the diagnostic preserves the
                // distinction between a storage failure and an idempotent miss.
                ThreadPendingSendJournal.ConsumeResult.Failed -> {
                    diagnosticsLogger(
                        "thread_pending_send_canonical_cleanup_failed randomId=$randomId " +
                            "serverId=${provenance.serverId} route=${route.instanceKey}"
                    )
                    false
                }
            }
        }
        val uniqueSurviving = surviving.newestPerLocalId()
        if (uniqueSurviving.size != surviving.size) {
            val repeatedIds = surviving.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
            diagnosticsLogger(
                "thread_pending_send_restore_duplicate_local_id level=warn " +
                    "localIds=${repeatedIds.joinToString(",")} dropped=${surviving.size - uniqueSurviving.size} " +
                    "serverId=${provenance.serverId} route=${route.instanceKey}"
            )
        }
        val existingIds = canonicalProjection.messages.mapTo(mutableSetOf()) { it.id }
        val additions = uniqueSurviving.filter { it.id !in existingIds }
        return if (additions.isEmpty()) {
            canonicalProjection
        } else {
            canonicalProjection.copy(
                status = "content",
                messages = (canonicalProjection.messages + additions).sortedByThreadTimeline()
            ).withOriginReplyCountAtLeastLoadedReplies()
        }
    }

    internal fun publishReplyCacheProvenance(
        source: ThreadReplyCacheIdentitySnapshot,
        captureSequence: Long
    ): ReplyCacheProvenance =
        synchronized(replyCacheProvenanceLock) {
            val current = replyCacheProvenance.value
            val sameIdentity =
                current != null &&
                current.environmentId == source.environmentId &&
                current.principalId == source.principalId &&
                current.serverId == source.serverId &&
                current.authGeneration == source.authGeneration &&
                current.selectionGeneration == source.selectionGeneration &&
                current.bindingGeneration == source.bindingGeneration
            if (sameIdentity) {
                if (captureSequence > latestPublishedReplyCacheCapture) {
                    latestPublishedReplyCacheCapture = captureSequence
                }
                current
            } else if (captureSequence < latestPublishedReplyCacheCapture) {
                ReplyCacheProvenance(
                    environmentId = source.environmentId,
                    principalId = source.principalId,
                    serverId = source.serverId,
                    generation = captureSequence,
                    authGeneration = source.authGeneration,
                    selectionGeneration = source.selectionGeneration,
                    bindingGeneration = source.bindingGeneration,
                )
            } else {
                latestPublishedReplyCacheCapture = captureSequence
                val next =
                    ReplyCacheProvenance(
                        environmentId = source.environmentId,
                        principalId = source.principalId,
                        serverId = source.serverId,
                        generation = captureSequence,
                        authGeneration = source.authGeneration,
                        selectionGeneration = source.selectionGeneration,
                        bindingGeneration = source.bindingGeneration,
                    )
                retireTimelineAccountIfChangedLocked(next)
                next.also { replyCacheProvenance.value = it }
            }
        }

    internal fun clearReplyCacheProvenance(captureSequence: Long) {
        synchronized(replyCacheProvenanceLock) {
            if (captureSequence >= latestPublishedReplyCacheCapture) {
                latestPublishedReplyCacheCapture = captureSequence
                // Not retired here: an identity gap is usually a server switch or token rotation
                // in progress, and a late response for the previous server is still that server's
                // data (Tenny rule 1). The account fence runs when the next identity is published
                // ([retireTimelineAccountIfChangedLocked]) and in [cachePayload], which refuses
                // every write while no identity is current.
                replyCacheProvenance.value?.let { lastTimelineAccountProvenance = it }
                replyCacheProvenance.value = null
            }
        }
    }

    internal fun isCurrentReplyCacheProvenance(expected: ReplyCacheProvenance): Boolean =
        synchronized(replyCacheProvenanceLock) {
            replyCacheProvenance.value == expected
        }

    /**
     * Timeline-cache account fence (Tenny rule 1). A server switch or a token rotation keeps every
     * in-flight producer able to commit to its own server's key; only a different environment or
     * account retires the previous account's generations.
     */
    private fun retireTimelineAccountIfChangedLocked(next: ReplyCacheProvenance) {
        val previous = replyCacheProvenance.value ?: lastTimelineAccountProvenance
        lastTimelineAccountProvenance = next
        if (previous == null || previous.isSameTimelineCacheAccount(next)) return
        payloadCache.retireAccount(previous.environmentId, previous.principalId)
    }

    /**
     * Send terminal work may cross a transparent token rotation, but never a new auth binding or
     * selected server. Keep the ordinary cache/read fence above strict; this relation is only for
     * the already-admitted local send state machine.
     */
    internal fun isCurrentReplyCacheProvenanceForLocalSend(expected: ReplyCacheProvenance): Boolean =
        synchronized(replyCacheProvenanceLock) {
            replyCacheProvenance.value?.isCurrentOrSuccessorForLocalSend(expected) == true
        }

    internal fun currentReplyCacheProvenanceForLocalSend(
        expected: ReplyCacheProvenance
    ): ReplyCacheProvenance? =
        synchronized(replyCacheProvenanceLock) {
            replyCacheProvenance.value?.takeIf { current ->
                current.isCurrentOrSuccessorForLocalSend(expected)
            }
        }

    internal fun currentReplyCacheProvenance(): ReplyCacheProvenance? =
        synchronized(replyCacheProvenanceLock) {
            replyCacheProvenance.value
        }

    internal fun <T> withCurrentReplyCacheProvenance(
        expected: ReplyCacheProvenance,
        block: () -> T
    ): T? =
        synchronized(replyCacheProvenanceLock) {
            if (replyCacheProvenance.value != expected) return@synchronized null
            block()
        }

    /**
     * Re-applies the recorded reply-count fact ledger to a projected payload,
     * guarded by the expected reply-cache provenance. Returns `null` when the
     * provenance rotated (caller must drop), otherwise the ledger-projected
     * payload. Used by commit paths whose projections can overwrite a newer
     * realtime fact with a stale summary-snapshot value.
     */
    internal fun applyReplyCountFacts(
        threadId: String,
        expected: ReplyCacheProvenance,
        payload: RaftThreadScreenPayload,
    ): RaftThreadScreenPayload? =
        synchronized(replyCacheProvenanceLock) {
            if (replyCacheProvenance.value != expected) return@synchronized null
            payloadCache.applyReplyCountFacts(
                serverId = expected.serverId,
                threadId = threadId,
                environmentId = expected.environmentId,
                principalId = expected.principalId,
                payload = payload,
            )
        }

    internal fun <T> withCurrentReplyCacheProvenanceForLocalSend(
        expected: ReplyCacheProvenance,
        block: (ReplyCacheProvenance) -> T
    ): T? =
        synchronized(replyCacheProvenanceLock) {
            val current = replyCacheProvenance.value
            if (current?.isCurrentOrSuccessorForLocalSend(expected) != true) {
                return@synchronized null
            }
            block(current)
        }

    /**
     * Writes a canonical timeline into the payload cache under [ownerScopeId] (env + account +
     * server). Fence (Tenny rule 1): the write is admitted while the CURRENT identity is the same
     * environment + account as [expectedProvenance] (when given) and [ownerScopeId] is exactly
     * that account's key for [serverId]. A server switch or token rotation since the request does
     * not refuse it — the payload is written to its own server's key.
     */
    internal fun cachePayload(
        payload: RaftThreadScreenPayload,
        serverId: String,
        expectedGeneration: SharedThreadFreshnessGeneration?,
        ownerScopeId: String,
        expectedProvenance: ReplyCacheProvenance?
    ): Boolean =
        synchronized(replyCacheProvenanceLock) {
            // Offline durable stand-ins are never written to (or allowed to evict) the memory
            // payload cache: a memory entry outranks SQLite as network-fresh on the next open.
            if (payload.durableLastKnown) return@synchronized false
            val current = replyCacheProvenance.value ?: return@synchronized false
            if (expectedProvenance != null) {
                if (!current.isSameTimelineCacheAccount(expectedProvenance)) return@synchronized false
                if (expectedProvenance.serverId != serverId) return@synchronized false
            }
            if (ownerScopeId.isBlank() || serverId.isBlank()) return@synchronized false
            if (current.threadPayloadOwnerScopeId == null) return@synchronized false
            if (
                ownerScopeId !=
                    sharedThreadTimelineOwnerScopeId(current.environmentId, current.principalId, serverId)
            ) {
                return@synchronized false
            }
            val canonicalRoute = payload.resolveThreadRouteIdentity()
            val canonicalProvenance = expectedProvenance ?: replyCacheProvenance.value
            val threadId =
                canonicalRoute.timelineChannelId
                    ?: return@synchronized false
            val cacheablePayload =
                (if (pendingSendJournal == null) {
                    // Fixture/legacy data sources without a journal retain their historical
                    // failed-send cache projection.
                    payload.withoutTransientLocalSendingMessages()
                } else {
                    // Production's durable journal is the sole pending-state authority. Do not
                    // turn the canonical payload cache into a second pending database.
                    payload.withoutLocalPendingSendMessages()
                })
                    .withOriginReplyCountAtLeastLoadedReplies()
                    .asLoadedEmptyReplyThreadPayload()
            if (cacheablePayload.hasRenderableThreadContent()) {
                val candidate =
                    cacheablePayload.copy(
                        status = if (cacheablePayload.isLoadedEmptyReplyThread()) "empty" else "content",
                        backgroundRefreshing = false,
                        errorCode = null
                    )
                if (expectedGeneration == null) {
                    payloadCache.put(serverId, threadId, candidate, ownerScopeId)
                } else if (
                    expectedGeneration.key != SharedThreadPayloadCacheKey(serverId, threadId) ||
                    expectedGeneration.ownerScopeId != ownerScopeId ||
                    !payloadCache.putIfCurrent(expectedGeneration, candidate, ownerScopeId)
                ) {
                    return@synchronized false
                }
            } else {
                if (expectedGeneration == null) {
                    payloadCache.remove(serverId, threadId, ownerScopeId)
                } else if (
                    expectedGeneration.key != SharedThreadPayloadCacheKey(serverId, threadId) ||
                    expectedGeneration.ownerScopeId != ownerScopeId ||
                    !payloadCache.removeIfCurrent(expectedGeneration)
                ) {
                    return@synchronized false
                }
            }
            // The cache write is the commit point for this canonical observation.  Only after it
            // succeeds do we attempt to retire local pending rows; if the delete itself fails,
            // the canonical cache remains valid and a later pull can retry the idempotent cleanup.
            if (canonicalProvenance != null) {
                cacheablePayload.messages
                    .asSequence()
                    .filter { it.pendingSendContext == null }
                    .mapNotNull { it.randomId?.trim()?.takeIf(String::isNotBlank) }
                    .distinct()
                    .forEach { randomId ->
                        when (
                            pendingSendJournal?.consumeCanonicalRandomId(
                                provenance = canonicalProvenance,
                                route = canonicalRoute,
                                randomId = randomId,
                            )
                        ) {
                            ThreadPendingSendJournal.ConsumeResult.Failed ->
                                diagnosticsLogger(
                                    "thread_pending_send_canonical_cleanup_failed randomId=$randomId " +
                                        "serverId=${canonicalProvenance.serverId} route=${canonicalRoute.instanceKey}"
                                )
                            else -> Unit
                        }
                    }
            }
            true
        }

    internal fun cachedPayloadIfCurrent(
        threadId: String,
        expectedProvenance: ReplyCacheProvenance
    ): RaftThreadScreenPayload? =
        synchronized(replyCacheProvenanceLock) {
            if (replyCacheProvenance.value != expectedProvenance) return@synchronized null
            val ownerScopeId = expectedProvenance.threadPayloadOwnerScopeId ?: return@synchronized null
            payloadCache.get(
                expectedProvenance.serverId,
                threadId,
                expectedProvenance.environmentId,
                expectedProvenance.principalId,
                ownerScopeId
            )
        }

    /** Hands 4cf4c781: [SharedThreadDataSource.rememberResolvedThreadAccess] under the same provenance gate as [cachedPayloadIfCurrent]. */
    internal fun hasResolvedThreadAccessIfCurrent(
        threadId: String,
        expectedProvenance: ReplyCacheProvenance,
    ): Boolean =
        synchronized(replyCacheProvenanceLock) {
            if (replyCacheProvenance.value != expectedProvenance) return@synchronized false
            val ownerScopeId = expectedProvenance.threadPayloadOwnerScopeId ?: return@synchronized false
            payloadCache.hasResolvedAccess(expectedProvenance.serverId, threadId, ownerScopeId)
        }

    /** Hands 4b6ccb0d: [SharedThreadPayloadCache.getSuperseded] under the same provenance gate as [cachedPayloadIfCurrent]. */
    internal fun supersededCachedPayloadIfCurrent(
        threadId: String,
        expectedProvenance: ReplyCacheProvenance
    ): RaftThreadScreenPayload? =
        synchronized(replyCacheProvenanceLock) {
            if (replyCacheProvenance.value != expectedProvenance) return@synchronized null
            val ownerScopeId = expectedProvenance.threadPayloadOwnerScopeId ?: return@synchronized null
            payloadCache.getSuperseded(
                expectedProvenance.serverId,
                threadId,
                expectedProvenance.environmentId,
                expectedProvenance.principalId,
                ownerScopeId
            )
        }

    /** Hands 7e198cab: why this conversation's local copies are not to be shown as complete, or `null`. */
    internal fun staleConversationCauseIfCurrent(
        threadId: String,
        expectedProvenance: ReplyCacheProvenance
    ): String? =
        synchronized(replyCacheProvenanceLock) {
            if (replyCacheProvenance.value != expectedProvenance) return@synchronized null
            val ownerScopeId = expectedProvenance.threadPayloadOwnerScopeId ?: return@synchronized null
            payloadCache.staleConversationCause(expectedProvenance.serverId, threadId, ownerScopeId)
        }

    internal fun invalidateCachedPayload(
        payload: RaftThreadScreenPayload,
        expectedProvenance: ReplyCacheProvenance,
    ): Boolean =
        synchronized(replyCacheProvenanceLock) {
            if (replyCacheProvenance.value != expectedProvenance) return@synchronized false
            val threadId =
                payload.resolveThreadRouteIdentity().timelineChannelId
                    ?: return@synchronized false
            val ownerScopeId =
                expectedProvenance.threadPayloadOwnerScopeId
                    ?: return@synchronized false
            payloadCache.remove(expectedProvenance.serverId, threadId, ownerScopeId)
            true
        }
}
