package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlin.time.TimeSource

/** The most pages one pull reads before it stops (the cursor is kept; the next trigger goes on). */
internal const val SYNC_CHANGES_MAX_PAGES_PER_PULL: Int = 40

/**
 * One page of changes, coalesced for the stores: the newest change per (entity, id) wins, so
 * duplicates (a change the snapshot already held) are harmless. Changes made before a scope was
 * removed in the same page are dropped with it.
 */
internal data class RaftSyncApplyPlan(
    /** Upsert rows per entity, by id (the newest row of the page). */
    val upserts: Map<RaftSyncRowEntity, Map<String, JsonObject>> = emptyMap(),
    /** Deleted ids per entity (no later upsert in the page). */
    val deletes: Map<RaftSyncRowEntity, Set<String>> = emptyMap(),
    /** Conversation id -> the largest read position the page moved it to (null: not a read move). */
    val staleConversations: Map<String, Long?> = emptyMap(),
    /** Snapshots to retake: known entities with an unknown op, and added scopes. */
    val snapshots: Set<RaftSyncSnapshot> = emptySet(),
    /** Channels the viewer gained (their member rosters are retaken with [snapshots]). */
    val addedChannelIds: Set<String> = emptySet(),
    /** Scopes the viewer lost, in order: drop everything held under them. */
    val removedScopes: List<RaftSyncChange.ScopeRemoved> = emptyList(),
) {
    val isEmpty: Boolean
        get() =
            upserts.isEmpty() && deletes.isEmpty() && staleConversations.isEmpty() && snapshots.isEmpty() &&
                addedChannelIds.isEmpty() && removedScopes.isEmpty()

    fun upsertsOf(entity: RaftSyncRowEntity): Map<String, JsonObject> = upserts[entity].orEmpty()

    fun deletesOf(entity: RaftSyncRowEntity): Set<String> = deletes[entity].orEmpty()
}

internal fun List<RaftSyncChange>.toSyncApplyPlan(): RaftSyncApplyPlan {
    // entity -> id -> row (upsert) or null (delete), newest last.
    val rows = LinkedHashMap<RaftSyncRowEntity, LinkedHashMap<String, JsonObject?>>()
    val stale = LinkedHashMap<String, Long?>()
    val snapshots = LinkedHashSet<RaftSyncSnapshot>()
    val addedChannels = LinkedHashSet<String>()
    val removed = ArrayList<RaftSyncChange.ScopeRemoved>()
    for (change in this) {
        when (change) {
            is RaftSyncChange.Upsert -> rows.getOrPut(change.entity) { LinkedHashMap() }.apply {
                remove(change.id)
                put(change.id, change.row)
            }
            is RaftSyncChange.Delete -> rows.getOrPut(change.entity) { LinkedHashMap() }.apply {
                remove(change.id)
                put(change.id, null)
            }
            is RaftSyncChange.ConversationStale -> {
                val previous = stale[change.conversationId]
                stale[change.conversationId] =
                    when {
                        previous == null -> change.refSeq
                        change.refSeq == null -> previous
                        else -> maxOf(previous, change.refSeq)
                    }
            }
            is RaftSyncChange.ScopeAdded -> {
                snapshots += change.snapshots
                if (change.kind == RaftSyncScopeKind.Channel) addedChannels += change.id
            }
            is RaftSyncChange.ScopeRemoved -> {
                removed += change
                when (change.kind) {
                    RaftSyncScopeKind.Channel -> {
                        val channelId = change.id
                        rows[RaftSyncRowEntity.Channel]?.remove(channelId)
                        rows[RaftSyncRowEntity.ChannelMember]?.keys?.removeAll { it.substringBefore(':') == channelId }
                        stale.remove(channelId)
                        addedChannels.remove(channelId)
                    }
                    // The whole server left: nothing collected so far may be applied.
                    RaftSyncScopeKind.Server -> {
                        rows.clear()
                        stale.clear()
                        snapshots.clear()
                        addedChannels.clear()
                    }
                }
            }
            is RaftSyncChange.Resnapshot -> snapshots += change.snapshots
        }
    }
    val upserts = LinkedHashMap<RaftSyncRowEntity, Map<String, JsonObject>>()
    val deletes = LinkedHashMap<RaftSyncRowEntity, Set<String>>()
    rows.forEach { (entity, byId) ->
        val entityUpserts = LinkedHashMap<String, JsonObject>()
        val entityDeletes = LinkedHashSet<String>()
        byId.forEach { (id, row) -> if (row == null) entityDeletes += id else entityUpserts[id] = row }
        if (entityUpserts.isNotEmpty()) upserts[entity] = entityUpserts
        if (entityDeletes.isNotEmpty()) deletes[entity] = entityDeletes
    }
    return RaftSyncApplyPlan(
        upserts = upserts,
        deletes = deletes,
        staleConversations = stale,
        snapshots = snapshots,
        addedChannelIds = addedChannels,
        removedScopes = removed,
    )
}

/** Where pulled changes go: the existing stores, through their reducers. */
internal interface RaftSyncChangesSink {
    /**
     * Retake [snapshots] through the existing list endpoints ([reason]: `first_sync`, `reset_<reason>`).
     * False when one could not be taken: the cursor is then not advanced, so the next pull asks again.
     */
    suspend fun reloadSnapshots(scope: RaftSyncScope, snapshots: Set<RaftSyncSnapshot>, reason: String): Boolean

    /** Applies one page's changes. */
    suspend fun apply(scope: RaftSyncScope, plan: RaftSyncApplyPlan)
}

internal sealed interface RaftSyncPullResult {
    data class Synced(
        val pages: Int,
        val changes: Int,
        val firstSync: Boolean,
        val resetReason: String?,
        /** Stopped at [SYNC_CHANGES_MAX_PAGES_PER_PULL] with `hasMore` still set. */
        val truncated: Boolean,
    ) : RaftSyncPullResult

    data class Failed(val code: String) : RaftSyncPullResult
}

/**
 * The incremental sync loop of one server (contract: [parseSyncChangesPage]).
 *
 * - No cursor: read the current cursor (no `since`), take every snapshot, store the cursor, then
 *   pull from it. A change the snapshots already hold may come again; applying by (entity, id) is
 *   idempotent.
 * - Cursor: pull pages and apply each one; store the page's cursor after it is applied; while
 *   `hasMore`, pull again at once (at most [maxPagesPerPull] pages per pull).
 * - `reset`: retake `reset.snapshots`, then store the returned cursor.
 * - 400 `invalid_cursor`: forget the cursor and start over as a first sync (once per pull).
 *
 * Pulls of one scope run one at a time; a trigger during a pull waits and pulls again (cheap: no
 * changes). A pull stops as soon as [isCurrent] says the scope is no longer the signed-in binding.
 *
 * The pull itself is cheap and runs at once. Its snapshot reloads (first sync, reset) are full-list
 * reads: a pull with `jitterSnapshots` (a socket RE-connect, when every client reconnects together
 * after a deploy or a forced reset) waits [snapshotJitterDelay] before them (web #11453, mobile #3756).
 */
internal class RaftSyncChangesEngine(
    private val api: RaftSyncChangesApi,
    private val cursors: RaftSyncCursorStore,
    private val sink: RaftSyncChangesSink,
    private val isCurrent: (RaftSyncScope) -> Boolean = { true },
    private val pageLimit: Int = SYNC_CHANGES_PAGE_LIMIT,
    private val maxPagesPerPull: Int = SYNC_CHANGES_MAX_PAGES_PER_PULL,
    private val snapshotJitterDelay: suspend () -> Unit = {},
    private val logger: (String) -> Unit = {},
) {
    private val lock = SynchronizedObject()
    private val mutexes = mutableMapOf<RaftSyncScope, Mutex>()

    suspend fun pull(scope: RaftSyncScope, trigger: String, jitterSnapshots: Boolean = false): RaftSyncPullResult {
        val mutex = synchronized(lock) { mutexes.getOrPut(scope) { Mutex() } }
        return mutex.withLock {
            val startedAt = TimeSource.Monotonic.markNow()
            val result =
                try {
                    pullLocked(scope, jitterSnapshots)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    RaftSyncPullResult.Failed("exception_${error::class.simpleName ?: "unknown"}")
                }
            logger(
                "sync_changes_pull serverId=${scope.serverId} trigger=$trigger " + when (result) {
                    is RaftSyncPullResult.Synced ->
                        "result=synced pages=${result.pages} changes=${result.changes} firstSync=${result.firstSync} " +
                            "reset=${result.resetReason ?: "none"} truncated=${result.truncated}"
                    is RaftSyncPullResult.Failed -> "result=failed code=${result.code}"
                } + " durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
            )
            result
        }
    }

    private suspend fun pullLocked(scope: RaftSyncScope, jitterSnapshots: Boolean): RaftSyncPullResult {
        val serverId = ServerId(scope.serverId)
        var firstSync = false
        var restarted = false
        var resetReason: String? = null
        var pages = 0
        var changes = 0
        var cursor = cursors.read(scope)
        while (true) {
            if (!isCurrent(scope)) return RaftSyncPullResult.Failed("scope_retired")
            if (cursor == null) {
                firstSync = true
                val start =
                    when (val fetch = api.changes(serverId, since = null, limit = pageLimit)) {
                        is RaftSyncChangesFetch.Page -> fetch.page
                        RaftSyncChangesFetch.InvalidCursor -> return RaftSyncPullResult.Failed("invalid_cursor")
                        is RaftSyncChangesFetch.Failed -> return RaftSyncPullResult.Failed(fetch.code)
                    }
                // Snapshots after the cursor was read: whatever changes during them comes again.
                if (jitterSnapshots) snapshotJitterDelay()
                if (!isCurrent(scope)) return RaftSyncPullResult.Failed("scope_retired")
                if (!sink.reloadSnapshots(scope, RaftSyncSnapshot.All, reason = "first_sync")) {
                    return RaftSyncPullResult.Failed("snapshot_failed")
                }
                if (!isCurrent(scope)) return RaftSyncPullResult.Failed("scope_retired")
                cursors.write(scope, start.cursor)
                cursor = start.cursor
            }
            if (pages >= maxPagesPerPull) {
                return RaftSyncPullResult.Synced(pages, changes, firstSync, resetReason, truncated = true)
            }
            val page =
                when (val fetch = api.changes(serverId, since = cursor, limit = pageLimit)) {
                    is RaftSyncChangesFetch.Page -> fetch.page
                    RaftSyncChangesFetch.InvalidCursor -> {
                        cursors.clear(scope)
                        if (restarted) return RaftSyncPullResult.Failed("invalid_cursor")
                        restarted = true
                        cursor = null
                        continue
                    }
                    is RaftSyncChangesFetch.Failed -> return RaftSyncPullResult.Failed(fetch.code)
                }
            pages += 1
            if (!isCurrent(scope)) return RaftSyncPullResult.Failed("scope_retired")
            val reset = page.reset
            if (reset != null) {
                resetReason = reset.reason
                if (jitterSnapshots) snapshotJitterDelay()
                if (!isCurrent(scope)) return RaftSyncPullResult.Failed("scope_retired")
                if (!sink.reloadSnapshots(scope, reset.snapshots, reason = "reset_${reset.reason}")) {
                    return RaftSyncPullResult.Failed("snapshot_failed")
                }
            } else {
                changes += page.changes.size
                val plan = page.changes.toSyncApplyPlan()
                if (!plan.isEmpty) sink.apply(scope, plan)
            }
            if (!isCurrent(scope)) return RaftSyncPullResult.Failed("scope_retired")
            cursors.write(scope, page.cursor)
            cursor = page.cursor
            if (!page.hasMore) {
                return RaftSyncPullResult.Synced(pages, changes, firstSync, resetReason, truncated = false)
            }
        }
    }
}
