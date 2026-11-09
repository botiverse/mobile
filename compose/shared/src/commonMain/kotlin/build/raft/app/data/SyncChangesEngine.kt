package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlin.time.TimeSource

/** The most pages one pull reads before it stops (the cursor is kept; the next trigger goes on). */
internal const val SYNC_CHANGES_MAX_PAGES_PER_PULL: Int = 40

/**
 * The longest one `sync/changes` request may take. Past it the pull fails (`timeout`), a transient
 * failure ([isTransientSyncChangesFailure]): the first on a cursor keeps it and asks for no full
 * refetch, the second in a row drops it and a reconnect / foreground caller falls back to its
 * full-list refetch. Until #proj-mobile:#112 every failure dropped the cursor and fell back at
 * once (web client, Scout 10-27).
 */
internal const val SYNC_CHANGES_REQUEST_TIMEOUT_MILLIS: Long = 5_000L

/**
 * Transient failures ([isTransientSyncChangesFailure]) in a row on the same cursor before it is
 * forgotten (CTO, #proj-mobile:#112; "Pull failures" in slock packages/shared/src/syncChanges.ts). During a server deploy every app pulling in those minutes
 * fails at once: dropping the cursor (or falling back to the full-list refetch) on the first
 * failure turned each of them into a full reload and made the load spike worse. A first failure
 * keeps the cursor; one retry 30–60 s later ([RaftSyncTransientRetry]) or the next normal trigger
 * (reconnect, foreground, server select, with its usual jitter) pulls again, whichever comes
 * first. A cursor that fails twice in a row is still not retried forever. The count is stored with
 * the cursor ([RaftSyncCursorStore.recordFailure]), so an app restart does not reset it.
 */
internal const val SYNC_CHANGES_CURSOR_FAILURES_BEFORE_FORGET: Int = 2

/**
 * A failure the server gets over by itself (a deploy, a brief outage), as the "Pull failures"
 * section of slock packages/shared/src/syncChanges.ts defines it for web and the app alike
 * (slock #13765, #proj-mobile:#112): no answer (`http_0`, the network client's transport error,
 * or [SYNC_CHANGES_REQUEST_TIMEOUT_MILLIS]), a 5xx (any status from 500 up, as web), 408 or 429.
 * These count against the cursor ([SYNC_CHANGES_CURSOR_FAILURES_BEFORE_FORGET]). Anything else
 * drops it at once: `invalid_cursor`, 401, 403 (of `sync/changes` itself; a snapshot list's 403 is
 * "not readable, skip", [RaftSyncSnapshotRead.NotReadable]), any other 4xx, a malformed body.
 */
internal fun isTransientSyncChangesFailure(code: String): Boolean {
    if (code == "timeout") return true
    val status = code.removePrefix("http_").takeIf { it != code }?.toIntOrNull() ?: return false
    return status == 0 || status == 408 || status == 429 || status >= 500
}

/** The most a pull triggered by a socket RE-connect waits before its first request (Ray 10-27). */
internal const val SYNC_CHANGES_RECONNECT_PULL_JITTER_MAX_MILLIS: Long = 2_000L

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
    /**
     * Conversation id -> the state its changes carried, merged per kind (the later change's part
     * wins, web `planSyncChanges`). Null once one of its changes carried none: only the lists can
     * bring that conversation up to date. Same keys as [staleConversations].
     */
    val conversationStates: Map<String, RaftSyncConversationState?> = emptyMap(),
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
    val states = LinkedHashMap<String, RaftSyncConversationState?>()
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
                val previousState = states[change.conversationId]
                states[change.conversationId] =
                    when {
                        change.state == null -> null
                        change.conversationId !in states -> change.state
                        previousState == null -> null
                        else ->
                            RaftSyncConversationState(
                                read = change.state.read ?: previousState.read,
                                done = change.state.done ?: previousState.done,
                                follow = change.state.follow ?: previousState.follow,
                            )
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
                        states.remove(channelId)
                        addedChannels.remove(channelId)
                    }
                    // The whole server left: nothing collected so far may be applied.
                    RaftSyncScopeKind.Server -> {
                        rows.clear()
                        stale.clear()
                        states.clear()
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
        conversationStates = states,
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
     * A list this viewer may not read (403) counts as taken ([RaftSyncSnapshotRead.NotReadable]).
     */
    suspend fun reloadSnapshots(scope: RaftSyncScope, snapshots: Set<RaftSyncSnapshot>, reason: String): Boolean

    /** Applies one page's changes. */
    suspend fun apply(scope: RaftSyncScope, plan: RaftSyncApplyPlan)

    /**
     * Reads again what an earlier pull's reloads failed to read ([RaftSyncReloadLedger]), at the
     * start of a pull from a cursor: a page with no changes reloads nothing by itself.
     */
    suspend fun retakeFailedReloads(scope: RaftSyncScope) {}
}

internal sealed interface RaftSyncPullResult {
    data class Synced(
        val pages: Int,
        val changes: Int,
        val firstSync: Boolean,
        val resetReason: String?,
        /** Stopped at [SYNC_CHANGES_MAX_PAGES_PER_PULL] with `hasMore` still set. */
        val truncated: Boolean,
        /** The stored cursor differs from the one the pull started from (equality only: it stays opaque). */
        val cursorAdvanced: Boolean = true,
    ) : RaftSyncPullResult {
        /**
         * Go on in a follow-up pull: stopped at the page cap with more waiting, and the cursor moved.
         * A server answering `hasMore` with the cursor it was given would otherwise be pulled
         * again forever, 40 requests a round (Tenny, #proj-mobile:#112 should-fix a).
         */
        val needsFollowUp: Boolean
            get() = truncated && cursorAdvanced
    }

    data class Failed(
        val code: String,
        /**
         * A first transient failure on the stored cursor: the cursor stays, and one retry 30–60 s
         * later or the next normal trigger pulls again (#proj-mobile:#112,
         * [SYNC_CHANGES_CURSOR_FAILURES_BEFORE_FORGET], [RaftSyncTransientRetry]).
         */
        val cursorKept: Boolean = false,
    ) : RaftSyncPullResult {
        /**
         * The caller's full-list refetch must stand in for this pull: the cursor was dropped (the
         * second transient failure in a row on it, `invalid_cursor`, a snapshot that failed, any
         * other failure) or there was none. Not on a first failure that kept the cursor
         * ([cursorKept]): a full reload then would put the same load on a server that is
         * deploying as dropping the cursor did (#proj-mobile:#112). Not when the scope itself is
         * gone (signed out, server switched).
         */
        val needsFullRefetchFallback: Boolean
            get() = code != SCOPE_RETIRED && !cursorKept

        companion object {
            const val SCOPE_RETIRED: String = "scope_retired"
        }
    }
}

/**
 * The incremental sync loop of one server (contract: [parseSyncChangesPage]; failures: the "Pull
 * failures" section of slock packages/shared/src/syncChanges.ts, slock #13765, shared with web).
 *
 * - No cursor: read the current cursor (no `since`), take every snapshot, store the cursor, then
 *   pull from it. A change the snapshots already hold may come again; applying by (entity, id) is
 *   idempotent.
 * - Cursor: first read again what an earlier pull's reloads failed to read
 *   ([RaftSyncChangesSink.retakeFailedReloads]); then pull pages and apply each one; while
 *   `hasMore`, pull again at once (at most [maxPagesPerPull] pages per pull). The last page's
 *   cursor is stored only once the whole pull is done (#proj-mobile:#112): a pull that fails part
 *   way keeps the cursor it started from, so the next one asks for the applied pages again
 *   instead of skipping them. A first sync's cursor is stored right after its snapshots (they hold
 *   everything up to it).
 * - `reset`: retake `reset.snapshots` (always after [snapshotJitterDelay], whatever the trigger: a
 *   reset reaches every client at once), then go on from the returned cursor.
 * - A snapshot this viewer may not read (403: a guest's `servers/:id/members`) is not a failure:
 *   the sink counts it as taken (Tenny, #proj-mobile:#112).
 * - A page request that fails transiently (no answer, past [requestTimeoutMillis], 5xx, 408, 429:
 *   [isTransientSyncChangesFailure]) counts against the stored cursor: the first failure keeps it
 *   and asks for no fallback ([RaftSyncPullResult.Failed.cursorKept]); one retry 30–60 s later
 *   ([RaftSyncTransientRetry]) or the next normal trigger pulls again. Only the
 *   [SYNC_CHANGES_CURSOR_FAILURES_BEFORE_FORGET]th in a row on the same cursor forgets it
 *   (#proj-mobile:#112: every client fails together during a deploy, and a full reload for each
 *   made the spike worse). Any other failure (`invalid_cursor`, 401, 403 and other 4xx of
 *   `sync/changes` itself, a malformed body, a snapshot that failed) forgets the cursor at once, as
 *   the 10-27 contract had it for every failure (Tenny):
 *   a cursor that keeps failing must not fail forever. Once forgotten, the next pull re-bootstraps
 *   as a first sync, after [snapshotJitterDelay]. A pull whose scope was retired keeps it.
 * - 400 `invalid_cursor`: forget the cursor and start over as a first sync (once per pull); its
 *   snapshots take [snapshotJitterDelay] (the server may have invalidated every cursor at once).
 *
 * Pulls of one scope run one at a time; a trigger during a pull waits and pulls again (cheap: no
 * changes). A pull stops as soon as [isCurrent] says the scope is no longer the signed-in binding
 * or its server is no longer the selected one (`scope_retired`).
 *
 * The pull itself is cheap. A pull with `jitterPull` (a socket RE-connect: every client reconnects
 * together after a deploy) first waits [pullJitterDelay] (0–2 s), so the pulls do not land on the
 * server in one burst; foreground and server-select pulls run at once. Its snapshot reloads (first
 * sync, reset) are full-list reads: a first sync waits [snapshotJitterDelay] before them when the
 * pull has `jitterSnapshots` (the same RE-connect; sign-in and server select take it at once), a
 * reset always does (web #11453, mobile #3756, Tenny 10-27).
 */
internal class RaftSyncChangesEngine(
    private val api: RaftSyncChangesApi,
    private val cursors: RaftSyncCursorStore,
    private val sink: RaftSyncChangesSink,
    private val isCurrent: (RaftSyncScope) -> Boolean = { true },
    private val pageLimit: Int = SYNC_CHANGES_PAGE_LIMIT,
    private val maxPagesPerPull: Int = SYNC_CHANGES_MAX_PAGES_PER_PULL,
    private val snapshotJitterDelay: suspend () -> Unit = {},
    private val pullJitterDelay: suspend () -> Unit = {},
    private val requestTimeoutMillis: Long = SYNC_CHANGES_REQUEST_TIMEOUT_MILLIS,
    private val logger: (String) -> Unit = {},
) {
    private val lock = SynchronizedObject()
    private val mutexes = mutableMapOf<RaftSyncScope, Mutex>()

    /** Scopes whose cursor a failed pull dropped: their re-bootstrap snapshots wait the jitter. */
    private val droppedAfterFailure = mutableSetOf<RaftSyncScope>()

    suspend fun pull(
        scope: RaftSyncScope,
        trigger: String,
        jitterSnapshots: Boolean = false,
        jitterPull: Boolean = false,
    ): RaftSyncPullResult {
        // Before the scope's lock: a trigger arriving meanwhile is not held behind this wait.
        if (jitterPull) pullJitterDelay()
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
            // A first transient failure keeps the cursor (cursorKept: no fallback either); the
            // caller schedules one retry ([RaftSyncTransientRetry], #proj-mobile:#112).
            when {
                result is RaftSyncPullResult.Failed && result.needsFullRefetchFallback -> {
                    cursors.clear(scope)
                    synchronized(lock) { droppedAfterFailure += scope }
                }
                result is RaftSyncPullResult.Synced -> synchronized(lock) { droppedAfterFailure -= scope }
            }
            logger(
                "sync_changes_pull serverId=${scope.serverId} trigger=$trigger " + when (result) {
                    is RaftSyncPullResult.Synced ->
                        "result=synced pages=${result.pages} changes=${result.changes} firstSync=${result.firstSync} " +
                            "reset=${result.resetReason ?: "none"} truncated=${result.truncated} " +
                            "cursorAdvanced=${result.cursorAdvanced}"
                    is RaftSyncPullResult.Failed -> "result=failed code=${result.code} cursorKept=${result.cursorKept}"
                } + " durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
            )
            result
        }
    }

    private suspend fun fetch(serverId: ServerId, since: String?): RaftSyncChangesFetch =
        withTimeoutOrNull(requestTimeoutMillis) { api.changes(serverId, since = since, limit = pageLimit) }
            ?: RaftSyncChangesFetch.Failed("timeout")

    /**
     * A page request failed with [code] while [stored] is the cursor on disk (the one the pull
     * started from, or a first sync's: pages are only stored once the whole pull is done). A
     * transient failure ([isTransientSyncChangesFailure]) counts against [stored] and keeps it below
     * [SYNC_CHANGES_CURSOR_FAILURES_BEFORE_FORGET] in a row (#proj-mobile:#112); [pull] drops it
     * otherwise. A pull that succeeds rewrites the cursor, which sets the count back to 0.
     */
    private fun failedOnCursor(scope: RaftSyncScope, stored: String, code: String): RaftSyncPullResult.Failed {
        if (!isTransientSyncChangesFailure(code)) return RaftSyncPullResult.Failed(code)
        val failures = cursors.recordFailure(scope, stored)
        val kept = failures < SYNC_CHANGES_CURSOR_FAILURES_BEFORE_FORGET
        logger("sync_changes_cursor_failure serverId=${scope.serverId} code=$code failures=$failures kept=$kept")
        return RaftSyncPullResult.Failed(code, cursorKept = kept)
    }

    private suspend fun pullLocked(scope: RaftSyncScope, jitterSnapshots: Boolean): RaftSyncPullResult {
        val serverId = ServerId(scope.serverId)
        var firstSync = false
        var restarted = false
        var resetReason: String? = null
        var pages = 0
        var changes = 0
        // The cursor on disk. Pages advance only [cursor] (in memory) and it is stored once the whole
        // pull is done: a pull failing at page 3 of 5 keeps the cursor from before page 1, so the
        // kept cursor (#proj-mobile:#112) asks for pages 1-2 again rather than skipping them. They
        // were applied already; applying by (entity, id) again is harmless.
        var stored = cursors.read(scope)
        var cursor = stored
        val startCursor = stored
        val rebootstrap = synchronized(lock) { scope in droppedAfterFailure }

        fun synced(truncated: Boolean): RaftSyncPullResult {
            if (!isCurrent(scope)) return RaftSyncPullResult.Failed(RaftSyncPullResult.Failed.SCOPE_RETIRED)
            val end = cursor ?: return RaftSyncPullResult.Failed("no_cursor")
            // Also when [end] is the stored cursor: a pull that succeeded sets the failure count back to 0.
            cursors.write(scope, end)
            return RaftSyncPullResult.Synced(
                pages, changes, firstSync, resetReason, truncated = truncated, cursorAdvanced = end != startCursor,
            )
        }

        // A first sync takes every snapshot: nothing to retake on top of it.
        if (cursor != null) {
            if (!isCurrent(scope)) return RaftSyncPullResult.Failed("scope_retired")
            // A retake that throws must not drop the cursor (a failed pull would): the pages go on.
            try {
                sink.retakeFailedReloads(scope)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                logger("sync_changes_retake serverId=${scope.serverId} result=exception type=${error::class.simpleName ?: "unknown"}")
            }
        }
        while (true) {
            if (!isCurrent(scope)) return RaftSyncPullResult.Failed("scope_retired")
            if (cursor == null) {
                firstSync = true
                val start =
                    when (val fetch = fetch(serverId, since = null)) {
                        is RaftSyncChangesFetch.Page -> fetch.page
                        RaftSyncChangesFetch.InvalidCursor -> return RaftSyncPullResult.Failed("invalid_cursor")
                        // No cursor to keep: the caller's full refetch stands in.
                        is RaftSyncChangesFetch.Failed -> return RaftSyncPullResult.Failed(fetch.code)
                    }
                // Snapshots after the cursor was read: whatever changes during them comes again.
                // A RE-connect, an invalid_cursor restart or a re-bootstrap after a failure: full lists
                // every client may ask at once. Sign-in / server select take them at once.
                if (jitterSnapshots || restarted || rebootstrap) snapshotJitterDelay()
                if (!isCurrent(scope)) return RaftSyncPullResult.Failed("scope_retired")
                if (!sink.reloadSnapshots(scope, RaftSyncSnapshot.All, reason = "first_sync")) {
                    return RaftSyncPullResult.Failed("snapshot_failed")
                }
                if (!isCurrent(scope)) return RaftSyncPullResult.Failed("scope_retired")
                // Stored at once, unlike a page's: the snapshots hold everything up to it, so a
                // page failing next keeps it (and the snapshots just taken) instead of a full reload.
                cursors.write(scope, start.cursor)
                stored = start.cursor
                cursor = start.cursor
            }
            if (pages >= maxPagesPerPull) return synced(truncated = true)
            val page =
                when (val fetch = fetch(serverId, since = cursor)) {
                    is RaftSyncChangesFetch.Page -> fetch.page
                    RaftSyncChangesFetch.InvalidCursor -> {
                        cursors.clear(scope)
                        if (restarted) return RaftSyncPullResult.Failed("invalid_cursor")
                        restarted = true
                        stored = null
                        cursor = null
                        continue
                    }
                    is RaftSyncChangesFetch.Failed ->
                        return stored?.let { failedOnCursor(scope, it, fetch.code) } ?: RaftSyncPullResult.Failed(fetch.code)
                }
            pages += 1
            if (!isCurrent(scope)) return RaftSyncPullResult.Failed("scope_retired")
            val reset = page.reset
            if (reset != null) {
                resetReason = reset.reason
                snapshotJitterDelay()
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
            cursor = page.cursor
            if (!page.hasMore) return synced(truncated = false)
        }
    }
}
