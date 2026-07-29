package build.raft.app.network.sync

import build.raft.app.model.ThreadUpdated
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

internal const val THREAD_REPLIES_SYNC_DOMAIN: String = "threadReplies"

internal data class ThreadRepliesPageIdentity(
    val serverId: String,
    val principalId: String,
    val parentScopeKind: String,
    val parentScopeId: String,
) {
    val key: String = listOf(serverId, principalId, parentScopeKind, parentScopeId).joinToString("\u001f")

    fun isValid(): Boolean =
        serverId.isNotBlank() &&
            principalId.isNotBlank() &&
            parentScopeKind in ThreadRepliesAllowedParentScopeKinds &&
            parentScopeId.isNotBlank()
}

internal data class ThreadRepliesScopeIdentity(
    val page: ThreadRepliesPageIdentity,
    val parentMessageId: String,
    val threadChannelId: String,
) {
    val scopeId: String =
        listOf(
            page.serverId,
            page.principalId,
            page.parentScopeKind,
            page.parentScopeId,
            parentMessageId,
            threadChannelId,
        ).joinToString("\u001f")

    fun isValid(): Boolean = page.isValid() && parentMessageId.isNotBlank() && threadChannelId.isNotBlank()
}

internal data class ThreadRepliesAuthoritativeSnapshot(
    val identity: ThreadRepliesScopeIdentity,
    val replies: List<ReplyPreview>,
    val replyCount: Int,
)

internal data class ThreadRepliesAcceptedProjection(
    val identity: ThreadRepliesScopeIdentity,
    val model: ThreadRepliesReadModel,
    val revision: Long,
)

private data class ThreadRepliesDomainEvent(
    val reply: ReplyPreview,
    val replyCount: Int,
)

/**
 * Mobile host for the already-shipped replies SyncCore v1/null-epoch domain.
 *
 * A page request starts a generation before transport. Eligible create frames update the active
 * accepted core and are also parked. A successful embedded/targeted summary builds a replacement
 * core from the authoritative snapshot, replays that generation's parked frames with the shared
 * reducer, then atomically swaps. Missing/invalid summaries and stale generations never swap.
 */
internal class ThreadRepliesSyncHost(
    /** Deterministic concurrency seam: pending consumed, replacement not yet swapped. */
    private val beforeAuthoritativeSwap: (() -> Unit)? = null,
    /** Deterministic concurrency seam: current revisions re-read, Store callback not yet committed. */
    private val beforeProjectionCommit: (() -> Unit)? = null,
) {
    private class ScopeOwner(
        val lock: SynchronizedObject = SynchronizedObject(),
        var activeCore: SyncCore = newThreadRepliesSyncCore(),
        var revision: Long = 0L,
    )

    private data class PendingRebaseline(
        val page: ThreadRepliesPageIdentity,
        val generation: Long,
        val parkedFrames: MutableMap<String, MutableList<SyncFrame>> = mutableMapOf(),
    )

    /** One linearization boundary for frame apply+park and pending consume+scope swap. */
    private val linearizationLock = SynchronizedObject()
    private val ownersLock = SynchronizedObject()
    private val owners = mutableMapOf<String, ScopeOwner>()
    private val pendingLock = SynchronizedObject()
    private val pendingByPage = mutableMapOf<String, PendingRebaseline>()

    private fun owner(scopeId: String): ScopeOwner =
        synchronized(ownersLock) { owners.getOrPut(scopeId, ::ScopeOwner) }

    private fun ownerOrNull(scopeId: String): ScopeOwner? =
        synchronized(ownersLock) { owners[scopeId] }

    fun beginAuthoritativeRebaseline(page: ThreadRepliesPageIdentity, generation: Long): Boolean =
        synchronized(linearizationLock) {
            beginAuthoritativeRebaselineLocked(page, generation)
        }

    private fun beginAuthoritativeRebaselineLocked(page: ThreadRepliesPageIdentity, generation: Long): Boolean {
        if (!page.isValid() || generation <= 0L) return false
        return synchronized(pendingLock) {
            val current = pendingByPage[page.key]
            if (current != null && generation <= current.generation) {
                false
            } else {
                pendingByPage[page.key] = PendingRebaseline(page, generation)
                true
            }
        }
    }

    fun abortAuthoritativeRebaseline(page: ThreadRepliesPageIdentity, generation: Long) {
        synchronized(linearizationLock) {
            synchronized(pendingLock) {
                if (pendingByPage[page.key]?.generation == generation) pendingByPage.remove(page.key)
            }
        }
    }

    fun ingestFrame(page: ThreadRepliesPageIdentity, event: ThreadUpdated): ThreadRepliesAcceptedProjection? =
        synchronized(linearizationLock) {
            ingestFrameLocked(page, event)
        }

    private fun ingestFrameLocked(
        page: ThreadRepliesPageIdentity,
        event: ThreadUpdated,
    ): ThreadRepliesAcceptedProjection? {
        val identity = event.scopeIdentityOrNull(page) ?: return null
        val reply = event.latestReply ?: return null
        val frame =
            SyncFrame(
                scopeId = identity.scopeId,
                seq = reply.seq.toSyncSeq(),
                epoch = null,
                event = ThreadRepliesDomainEvent(reply, event.replyCount.coerceAtLeast(0)),
            )
        val scopeOwner = owner(identity.scopeId)
        var revision = 0L
        val model =
            synchronized(scopeOwner.lock) {
                val before = scopeOwner.activeCore.threadRepliesState(identity.scopeId)
                val outcome = scopeOwner.activeCore.ingestFrame(THREAD_REPLIES_SYNC_DOMAIN, frame)
                var accepted = scopeOwner.activeCore.threadRepliesState(identity.scopeId)
                // The deployed v1 contract may redeliver an at/below-seam create with a newer
                // authoritative count. Generic SyncCore correctly drops it by seq; the replies
                // reducer still owns that count merge inside this domain host.
                if (outcome is SyncIngestOutcome.DuplicateDropped) {
                    val merged = accepted.applyReplyFrame(reply, event.replyCount, InlineThreadReplyPreviewLimit)
                    if (merged !== accepted) {
                        val syncState = scopeOwner.activeCore.scopeSyncState(THREAD_REPLIES_SYNC_DOMAIN, identity.scopeId)
                        val replacement = newThreadRepliesSyncCore()
                        replacement.ingestSnapshot(
                            THREAD_REPLIES_SYNC_DOMAIN,
                            SyncSnapshot(
                                scopeId = identity.scopeId,
                                watermark = syncState?.appliedSeq ?: reply.seq.toSyncSeq(),
                                epoch = null,
                                state = merged,
                            ),
                        )
                        scopeOwner.activeCore = replacement
                        accepted = merged
                    }
                }
                if (accepted != before) scopeOwner.revision += 1L
                revision = scopeOwner.revision
                accepted
            }
        synchronized(pendingLock) {
            pendingByPage[page.key]
                ?.takeIf { it.page == page }
                ?.parkedFrames
                ?.getOrPut(identity.scopeId) { mutableListOf() }
                ?.add(frame)
        }
        return ThreadRepliesAcceptedProjection(identity, model, revision)
    }

    fun ingestAuthoritativeSnapshots(
        page: ThreadRepliesPageIdentity,
        generation: Long,
        snapshots: List<ThreadRepliesAuthoritativeSnapshot>,
    ): Map<String, ThreadRepliesAcceptedProjection>? =
        synchronized(linearizationLock) {
            ingestAuthoritativeSnapshotsLocked(page, generation, snapshots)
        }

    private fun ingestAuthoritativeSnapshotsLocked(
        page: ThreadRepliesPageIdentity,
        generation: Long,
        snapshots: List<ThreadRepliesAuthoritativeSnapshot>,
    ): Map<String, ThreadRepliesAcceptedProjection>? {
        if (snapshots.any { !it.identity.isValid() || it.identity.page != page }) return null
        val pending =
            synchronized(pendingLock) {
                pendingByPage[page.key]
                    ?.takeIf { it.page == page && it.generation == generation }
                    ?.also { pendingByPage.remove(page.key) }
            } ?: return null

        beforeAuthoritativeSwap?.invoke()

        val projections = linkedMapOf<String, ThreadRepliesAcceptedProjection>()
        for (snapshot in snapshots) {
            val identity = snapshot.identity
            val parked = pending.parkedFrames[identity.scopeId].orEmpty().sortedBy { it.seq }
            var replacementState =
                initialThreadRepliesReadModel(
                    replies = snapshot.replies,
                    replyCount = snapshot.replyCount.coerceAtLeast(0),
                    limit = InlineThreadReplyPreviewLimit,
                )
            var watermark = replacementState.snapshotSeq.toSyncSeq()
            for (frame in parked) {
                val event = frame.event as? ThreadRepliesDomainEvent ?: continue
                replacementState =
                    replacementState.applyReplyFrame(
                        reply = event.reply,
                        frameReplyCount = event.replyCount,
                        limit = InlineThreadReplyPreviewLimit,
                    )
                watermark = maxOf(watermark, frame.seq)
            }
            val replacement = newThreadRepliesSyncCore()
            val outcome =
                replacement.ingestSnapshot(
                    THREAD_REPLIES_SYNC_DOMAIN,
                    SyncSnapshot(
                        scopeId = identity.scopeId,
                        watermark = watermark,
                        epoch = null,
                        state = replacementState,
                    ),
                )
            if (outcome !is SyncIngestOutcome.Applied) return null
            val scopeOwner = owner(identity.scopeId)
            val revision =
                synchronized(scopeOwner.lock) {
                    scopeOwner.activeCore = replacement
                    scopeOwner.revision += 1L
                    scopeOwner.revision
                }
            projections[identity.parentMessageId] =
                ThreadRepliesAcceptedProjection(identity, replacementState, revision)
        }
        return projections
    }

    /**
     * Re-read accepted revisions and execute the Store projection commit while the same host
     * linearization lock is held. A realtime frame can therefore commit either before this block
     * (and be re-read here) or after it (and publish after HTTP), never in the rollback seam.
     */
    fun <T> withCurrentAcceptedProjections(
        identities: Collection<ThreadRepliesScopeIdentity>,
        block: (Map<String, ThreadRepliesAcceptedProjection>) -> T,
    ): T =
        synchronized(linearizationLock) {
            val current = linkedMapOf<String, ThreadRepliesAcceptedProjection>()
            identities.distinctBy { it.scopeId }.forEach { identity ->
                val scopeOwner = ownerOrNull(identity.scopeId) ?: return@forEach
                synchronized(scopeOwner.lock) {
                    current[identity.parentMessageId] =
                        ThreadRepliesAcceptedProjection(
                            identity = identity,
                            model = scopeOwner.activeCore.threadRepliesState(identity.scopeId),
                            revision = scopeOwner.revision,
                        )
                }
            }
            beforeProjectionCommit?.invoke()
            block(current)
        }
}

private fun ThreadUpdated.scopeIdentityOrNull(page: ThreadRepliesPageIdentity): ThreadRepliesScopeIdentity? {
    val envelope = syncCoreReplyWindow ?: return null
    if (envelope.producer != ThreadRepliesSyncWindowProducer) return null
    if (envelope.rootKind != "message" || envelope.rootServerId != page.serverId) return null
    if (envelope.rootMessageId != parentMessageId.value) return null
    if (envelope.relationKind != "replies" || envelope.backing != "sync-scope") return null
    if (envelope.parentScopeServerId != page.serverId) return null
    if (envelope.parentScopeKind != page.parentScopeKind || envelope.parentScopeId != page.parentScopeId) return null
    if (envelope.windowKind != "sync-scope-window") return null
    // scopeCursor/epoch are intentionally not admission/version axes in v1/null-epoch compat.
    return ThreadRepliesScopeIdentity(
        page = page,
        parentMessageId = parentMessageId.value,
        threadChannelId = threadChannelId.value,
    ).takeIf(ThreadRepliesScopeIdentity::isValid)
}

private fun newThreadRepliesSyncCore(): SyncCore =
    createSyncCore(SyncCoreConfig(domains = listOf(threadRepliesSyncDomain())))

private fun threadRepliesSyncDomain(): SyncDomainConfig =
    SyncDomainConfig(
        name = THREAD_REPLIES_SYNC_DOMAIN,
        density = SyncDensity.Sparse,
        initialState = {
            initialThreadRepliesReadModel(emptyList(), 0, InlineThreadReplyPreviewLimit)
        },
        fold = { state, event, _ ->
            val current =
                state as? ThreadRepliesReadModel
                    ?: initialThreadRepliesReadModel(emptyList(), 0, InlineThreadReplyPreviewLimit)
            val replyEvent = event as? ThreadRepliesDomainEvent
            if (replyEvent == null) {
                current
            } else {
                current.applyReplyFrame(replyEvent.reply, replyEvent.replyCount, InlineThreadReplyPreviewLimit)
            }
        },
        fromSnapshot = { snapshot ->
            snapshot.state as? ThreadRepliesReadModel
                ?: initialThreadRepliesReadModel(emptyList(), 0, InlineThreadReplyPreviewLimit)
        },
    )

private fun SyncCore.threadRepliesState(scopeId: String): ThreadRepliesReadModel =
    state(THREAD_REPLIES_SYNC_DOMAIN, scopeId) as? ThreadRepliesReadModel
        ?: initialThreadRepliesReadModel(emptyList(), 0, InlineThreadReplyPreviewLimit)

private val ThreadRepliesAllowedParentScopeKinds = setOf("channel", "private", "joint", "dm")
