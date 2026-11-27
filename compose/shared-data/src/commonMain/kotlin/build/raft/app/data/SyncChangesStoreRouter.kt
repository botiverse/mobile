package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.ServerSummary
import build.raft.app.model.TaskId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.JsonPrimitive

/**
 * The stores incremental sync writes into, each through its own reducer (MVI: no UI state is
 * touched here). The provider binds them to every shell store showing the server.
 */
@RaftCoreInternalApi interface RaftSyncStoreTargets {
    /** The loaded roster's server (null: no roster loaded; `server_member` rows wait for its load). */
    fun rosterServer(serverId: String): ServerSummary?

    /**
     * `server_member` rows arrived while no roster is loaded: a roster load in flight was read
     * before them and must not win (Tenny 10-27); retire it so its waiters read the server again.
     */
    suspend fun supersedeRosterLoad(serverId: String, reason: String)

    /** Machine id -> name, for the computer label of `agent` rows. */
    fun machineNames(serverId: String): Map<String, String>

    /** `server_member` + `agent` rows into [SharedServerMembersStore.applySyncedRosterRows]. */
    suspend fun applyRosterRows(serverId: String, upserts: List<MemberProfile>, deletes: List<Pair<MemberId, MemberKind>>)

    /** `machine` rows into [SharedComputersStore.applySyncedMachineRows]. */
    suspend fun applyMachineRows(serverId: String, upserts: List<ComputerSummary>, deletes: Set<String>)

    /**
     * Home (channels, DMs, sidebar order, server order, followed threads, unread) reloads through
     * its realtime refresh reducer (coalesced; marked dirty while Home is hidden). Not awaited: the
     * Home load reports its outcome to [RaftSyncReloadLedger] ([RaftSyncListRead.Home]).
     */
    fun refreshHome(serverId: String, reason: String)

    /**
     * Activity (inbox) and its unread summary refetch through the Activity realtime reducer. Not
     * awaited: the inbox read reports its outcome to [RaftSyncReloadLedger] ([RaftSyncListRead.Activity]).
     */
    fun refreshActivity(serverId: String, reason: String)

    /**
     * `inbox_row` changes (inbox_row capability, in seq order) into every Activity store showing the
     * server ([SharedActivityStore.applySyncInboxRows]): each through the same merge as a
     * `GET /api/channels/inbox?ids=` answer. [RaftSyncInboxRowsOutcome.NotApplied] when no store showing
     * the server could take them (none, or none with a fresh All list kept by the position rule): the
     * caller then goes on as without the capability.
     */
    fun applyInboxRows(serverId: String, changes: List<RaftSyncInboxRowChange>): RaftSyncInboxRowsOutcome =
        RaftSyncInboxRowsOutcome.NotApplied

    /**
     * Another device marked conversation [channelId] Done (a `conversation` change with
     * `done.done` true): every Activity store showing the server drops its row when
     * [doneFrontierSeq] covers it ([SharedActivityStore.applyRemoteDone]).
     * [ActivityRemoteDoneOutcome.CannotTell] from any of them (or no store): read the lists.
     */
    fun applyRemoteDone(serverId: String, channelId: String, doneFrontierSeq: String?): ActivityRemoteDoneOutcome

    /**
     * Loaded channel member rosters refresh: [channelIds], or every loaded one when null.
     * Actively observed slices refetch immediately while unobserved loaded slices are invalidated
     * for on-demand refresh on next open. Returns [RaftSyncChannelMembersRefreshResult.AllSucceeded]
     * when every targeted observed roster succeeded, or the specific failed channel ids to retake
     * on the next pull (a channel the viewer can no longer read is not a failure).
     */
    suspend fun refreshChannelMembers(
        serverId: String,
        channelIds: Set<String>?,
        reason: String,
    ): RaftSyncChannelMembersRefreshResult

    /** Forced roster reload (`/servers/:id/members` + `/agents` + `/servers/:id/machines`). */
    suspend fun reloadRoster(serverId: String, reason: String): RaftSyncSnapshotRead

    /** Forced machine list reload of the Computers store. */
    suspend fun reloadMachines(serverId: String, reason: String): RaftSyncSnapshotRead

    /**
     * `message` rows (RFC 082 §4.4): each replaces that message, by id, in the open conversation
     * pages of the server, through their reducers (as a `message:updated` would). A page that does
     * not hold the message changes nothing.
     */
    suspend fun applyMessageRows(serverId: String, messages: List<Message>)

    /**
     * `task` upserts and channel-scoped deletes (botiverse/slock #14975), in seq order, into
     * [SharedTasksDataStore]. Returns any channel ids whose upserted task hit a canonical conflict
     * and needs a channel resnapshot.
     */
    suspend fun applyTaskChanges(serverId: String, mutations: List<SharedSyncedTaskMutation>): Set<String> = emptySet()

    /**
     * Channel tasks left the viewer's scope (`scope` `removed` for a channel): drop cached tasks
     * for [channelIds] on [serverId] without touching unrelated channels or servers.
     */
    suspend fun dropChannelTasks(serverId: String, channelIds: Set<String>) = Unit

    /**
     * Task list resnapshot (`task` `op: "resnapshot"`, `reset` naming `"tasks"`, or an added
     * channel scope): re-read loaded task lists for [channelIds], or every held task scope on
     * [serverId] when null. False when one of them [RaftSyncSnapshotRead.Failed] (a channel the
     * viewer can no longer read is not a failure).
     */
    suspend fun refreshTasks(serverId: String, channelIds: Set<String>?, reason: String): Boolean = true
}

/** What the Activity stores did with a page's `inbox_row` changes ([RaftSyncStoreTargets.applyInboxRows]). */
@RaftCoreInternalApi sealed interface RaftSyncInboxRowsOutcome {
    /**
     * Applied to every store showing the server. [settledIds]: the row ids whose change was applied
     * (or judged and kept) there; a `conversation` change of one of them needs no Activity read.
     */
    data class Applied(val settledIds: Set<String>) : RaftSyncInboxRowsOutcome

    /** No store could take them: `conversation` changes read the lists as before. */
    data object NotApplied : RaftSyncInboxRowsOutcome
}

/**
 * Result of refreshing loaded channel member rosters ([RaftSyncStoreTargets.refreshChannelMembers]).
 *
 * - [AllSucceeded]: every targeted observed roster succeeded (and any unobserved loaded slices were
 *   invalidated so their next open fetches fresh).
 * - [FailedChannels]: only these specific [channelIds] failed and should be retaken on the next pull
 *   without rescanning unrelated channels (Hands 5f4a14be).
 * - [FailedAll]: whole-server fallback when specific failed channel ids are unknown.
 */
@RaftCoreInternalApi sealed interface RaftSyncChannelMembersRefreshResult {
    data object AllSucceeded : RaftSyncChannelMembersRefreshResult

    data class FailedChannels(val channelIds: Set<String>) : RaftSyncChannelMembersRefreshResult

    data object FailedAll : RaftSyncChannelMembersRefreshResult
}

/** How one snapshot reload ended, for the cursor. */
@RaftCoreInternalApi enum class RaftSyncSnapshotRead {
    /** The list was read. */
    Taken,

    /**
     * The server answered 403: this viewer may not read the list at all (a guest and
     * `servers/:id/members`, slock routes/servers.ts:2121; a viewer without `viewMachines` and
     * `servers/:id/machines`). The answer will not change on a retry, and the change log sends this
     * viewer nothing for that list either, so the snapshot counts as taken (empty for this viewer):
     * the cursor is kept and advances. Failing it instead dropped the cursor on every pull, so a
     * guest never got past its first sync (Tenny, #proj-mobile:#112).
     */
    NotReadable,

    /** Network, 5xx, timeout, signed out: the snapshot was not taken. */
    Failed,
}

/**
 * A list read's outcome for incremental sync. A 403 that is the server's answer for this viewer is
 * [RaftSyncSnapshotRead.NotReadable] (the same test as an access-lost conversation page); a 401 / no
 * session, or an edge block (not the server's answer), is a failure like any other.
 */
@RaftCoreInternalApi fun DataResult<*>.syncSnapshotRead(): RaftSyncSnapshotRead =
    when (this) {
        is DataResult.Content, is DataResult.Empty -> RaftSyncSnapshotRead.Taken
        is DataResult.NoPermission ->
            if (!blocker.isAuthSessionBlocker() && !blocker.serverMessage.isEdgeBlockedMarker()) {
                RaftSyncSnapshotRead.NotReadable
            } else {
                RaftSyncSnapshotRead.Failed
            }
        is DataResult.Error -> RaftSyncSnapshotRead.Failed
    }

/**
 * One list read by several stores of the same server (every shell showing it; they share the
 * process read): taken when one of them read it, not readable when none did and one was refused,
 * failed otherwise (null: that store threw). No store at all is a failure: nothing was read.
 */
@RaftCoreInternalApi fun List<DataResult<*>?>.syncSnapshotRead(): RaftSyncSnapshotRead {
    val reads = map { it?.syncSnapshotRead() ?: RaftSyncSnapshotRead.Failed }
    return when {
        RaftSyncSnapshotRead.Taken in reads -> RaftSyncSnapshotRead.Taken
        RaftSyncSnapshotRead.NotReadable in reads -> RaftSyncSnapshotRead.NotReadable
        else -> RaftSyncSnapshotRead.Failed
    }
}

/** A list a sync change reloads without awaiting it (a store reload launched through its reducer). */
@RaftCoreInternalApi enum class RaftSyncListRead { Home, Activity }

/** What the next pull reads again: the reloads after an earlier pull that failed. */
@RaftCoreInternalApi data class RaftSyncRetakes(
    val lists: Set<RaftSyncListRead> = emptySet(),
    val snapshots: Set<RaftSyncSnapshot> = emptySet(),
    val channelMemberIds: Set<String> = emptySet(),
) {
    val isEmpty: Boolean
        get() = lists.isEmpty() && snapshots.isEmpty() && channelMemberIds.isEmpty()
}

/**
 * The reloads a pull asked for that failed, per server, so the next pull reads them again.
 *
 * Why not hold the cursor back until they succeed (Tenny, #proj-mobile:#112 should-fix b): the
 * page's rows are already applied when its reloads start, and Home and Activity reload through
 * their own coalesced, superseding loads (deferred while hidden), which the pull would have to wait
 * for under its scope lock. A reload is a full-list read that does not depend on the cursor, so
 * reading it again on the next pull brings the same data; re-pulling the page would only ask for
 * the same reload again.
 *
 * - Awaited reloads (roster, machines, channel members) that fail are [markFailed].
 * - Home / Activity: the router [requestListRead]s before it dispatches; the store's list read
 *   [beginListRead]s / [endListRead]s around its request. A read that began after the latest
 *   request settles it: failed, it is retaken; read (or not readable), it is done. A read that
 *   began before the request (it may predate the change) settles nothing, and neither does a load
 *   that is cancelled (the superseding load settles it) or one not run (a hidden Home reads when it
 *   is shown). A later successful read also clears an earlier failure.
 *
 * Kept in memory: a cold start reads every list anyway.
 */
@RaftCoreInternalApi class RaftSyncReloadLedger {
    private val lock = SynchronizedObject()
    private var clock = 0L
    private val requested = mutableMapOf<Pair<String, RaftSyncListRead>, Long>()
    private val failedLists = mutableSetOf<Pair<String, RaftSyncListRead>>()
    private val failedSnapshots = mutableMapOf<String, MutableSet<RaftSyncSnapshot>>()
    private val failedChannelMemberChannels = mutableMapOf<String, MutableSet<String>>()

    fun requestListRead(serverId: String, list: RaftSyncListRead) {
        synchronized(lock) { requested[serverId to list] = ++clock }
    }

    /** The ticket for [endListRead]. */
    fun beginListRead(serverId: String, list: RaftSyncListRead): Long = synchronized(lock) { clock }

    fun endListRead(serverId: String, list: RaftSyncListRead, ticket: Long, read: RaftSyncSnapshotRead) {
        val key = serverId to list
        synchronized(lock) {
            val failed = read == RaftSyncSnapshotRead.Failed
            if (!failed) failedLists -= key
            val request = requested[key] ?: return
            if (ticket < request) return
            requested -= key
            if (failed) failedLists += key
        }
    }

    fun markFailed(serverId: String, snapshots: Set<RaftSyncSnapshot>) {
        if (snapshots.isEmpty()) return
        synchronized(lock) {
            failedSnapshots.getOrPut(serverId) { mutableSetOf() } += snapshots
            if (RaftSyncSnapshot.ChannelMembers in snapshots) {
                // Whole-server channel_members snapshot subsumes per-channel retakes.
                failedChannelMemberChannels.remove(serverId)
            }
        }
    }

    fun markFailedChannelMembers(serverId: String, channelIds: Set<String>) {
        val clean = channelIds.mapNotNullTo(LinkedHashSet()) { it.trim().takeIf(String::isNotEmpty) }
        if (clean.isEmpty()) return
        synchronized(lock) {
            if (RaftSyncSnapshot.ChannelMembers in failedSnapshots[serverId].orEmpty()) return
            failedChannelMemberChannels.getOrPut(serverId) { LinkedHashSet() } += clean
        }
    }

    /** What [serverId]'s next pull reads again; forgotten here (a retake that fails is marked again). */
    fun takeRetakes(serverId: String): RaftSyncRetakes =
        synchronized(lock) {
            val lists = failedLists.filter { it.first == serverId }.mapTo(LinkedHashSet()) { it.second }
            failedLists.removeAll { it.first == serverId }
            val snapshots = failedSnapshots.remove(serverId).orEmpty()
            val channelMemberIds =
                if (RaftSyncSnapshot.ChannelMembers in snapshots) {
                    failedChannelMemberChannels.remove(serverId)
                    emptySet()
                } else {
                    failedChannelMemberChannels.remove(serverId).orEmpty()
                }
            RaftSyncRetakes(lists = lists, snapshots = snapshots, channelMemberIds = channelMemberIds)
        }
}

/**
 * Routes pulled changes to the stores ([RaftSyncChangesSink] of the provider).
 *
 * Row-level today: `server_member`, `agent` (roster), `machine` (Computers), `message` (the open
 * conversation pages), `task` (Tasks store), and a `conversation` change carrying another device's
 * Done (Activity). Signal-level (the store's existing reload, once per page and only when something
 * changed): `channel`, `sidebar_order`, `server_order`, any other `conversation` change (Home +
 * Activity), `channel_member` (the loaded rosters of those channels), and `task` `resnapshot` /
 * `tasks` snapshot (the held task scopes of those channels or the server). A row that does not
 * decode retakes its entity's snapshot (a `message` row, which has none, is left to its
 * conversation's next read).
 *
 * A reload after a pull that fails is read again by the next pull ([RaftSyncReloadLedger]); a
 * first-sync / reset roster or machine snapshot that fails still fails its pull.
 */
@RaftCoreInternalApi class RaftSyncChangesStoreRouter(
    private val targets: RaftSyncStoreTargets,
    private val ledger: RaftSyncReloadLedger = RaftSyncReloadLedger(),
    private val logger: (String) -> Unit = {},
) : RaftSyncChangesSink {
    override suspend fun reloadSnapshots(scope: RaftSyncScope, snapshots: Set<RaftSyncSnapshot>, reason: String): Boolean {
        val failures = reload(scope.serverId, snapshots, channelIds = null, reason = "sync_$reason")
        // The cursor stands on the roster and machine lists (the change log patches them row by
        // row). Channel member rosters and task lists are only the loaded ones: retaken by the next pull.
        val pullFailed = failures.snapshots.any { it != RaftSyncSnapshot.ChannelMembers && it != RaftSyncSnapshot.Tasks }
        if (!pullFailed) recordReloadFailures(scope.serverId, failures)
        return !pullFailed
    }

    override suspend fun reloadInbox(scope: RaftSyncScope, reason: String) {
        refreshActivity(scope.serverId, "sync_$reason")
    }

    override suspend fun retakeFailedReloads(scope: RaftSyncScope) {
        val serverId = scope.serverId
        val retakes = ledger.takeRetakes(serverId)
        if (retakes.isEmpty) return
        val reason = "sync_retake"
        logger(
            "sync_changes_retake serverId=$serverId lists=${retakes.lists.joinToString(",") { it.name }.ifEmpty { "none" }} " +
                "snapshots=${retakes.snapshots.joinToString(",") { it.wireName }.ifEmpty { "none" }}" +
                (if (retakes.channelMemberIds.isNotEmpty()) " channelMembers=${retakes.channelMemberIds.size}" else "")
        )
        if (RaftSyncListRead.Home in retakes.lists) refreshHome(serverId, reason)
        if (RaftSyncListRead.Activity in retakes.lists) refreshActivity(serverId, reason)
        if (retakes.snapshots.isNotEmpty()) {
            recordReloadFailures(serverId, reload(serverId, retakes.snapshots, channelIds = null, reason = reason))
        }
        if (retakes.channelMemberIds.isNotEmpty() && RaftSyncSnapshot.ChannelMembers !in retakes.snapshots) {
            recordChannelMembersRefreshFailure(
                serverId = serverId,
                result = targets.refreshChannelMembers(serverId, retakes.channelMemberIds, reason),
            )
        }
    }

    override suspend fun applyPage(scope: RaftSyncScope, plan: RaftSyncApplyPlan): Int =
        applyPlanAndCountInboxRows(scope, plan)

    override suspend fun apply(scope: RaftSyncScope, plan: RaftSyncApplyPlan) {
        applyPlanAndCountInboxRows(scope, plan)
    }

    private suspend fun applyPlanAndCountInboxRows(scope: RaftSyncScope, plan: RaftSyncApplyPlan): Int {
        val serverId = scope.serverId
        val reason = "sync_changes"
        val resnapshot = LinkedHashSet(plan.snapshots)
        var wholeServerTaskResnapshot = RaftSyncSnapshot.Tasks in plan.snapshots

        val serverRemoved = plan.removedScopes.any { it.kind == RaftSyncScopeKind.Server && it.id == serverId }
        if (serverRemoved) {
            // Lost the whole server: the roster / machine / Home loads validate the access loss and drop
            // what is held under it through their existing access-lost paths.
            resnapshot += setOf(RaftSyncSnapshot.Channels, RaftSyncSnapshot.ServerMembers, RaftSyncSnapshot.Machines)
        }
        val removedChannelIds =
            plan.removedScopes
                .filter { it.kind == RaftSyncScopeKind.Channel }
                .mapTo(LinkedHashSet()) { it.id }
        if (removedChannelIds.isNotEmpty()) {
            targets.dropChannelTasks(serverId, removedChannelIds)
        }

        // Roster: server_member + agent rows.
        val server = targets.rosterServer(serverId)
        val rosterUpserts = mutableListOf<MemberProfile>()
        val rosterDeletes = mutableListOf<Pair<MemberId, MemberKind>>()
        plan.deletesOf(RaftSyncRowEntity.ServerMember).forEach { rosterDeletes += MemberId(it) to MemberKind.Human }
        plan.deletesOf(RaftSyncRowEntity.Agent).forEach { rosterDeletes += MemberId(it) to MemberKind.Agent }
        val memberRows = plan.upsertsOf(RaftSyncRowEntity.ServerMember)
        if (server != null) {
            memberRows.values.forEach { row ->
                val member = parseSharedServerMemberRow(row, server)
                if (member == null) resnapshot += RaftSyncSnapshot.ServerMembers else rosterUpserts += member
            }
        } else if (memberRows.isNotEmpty()) {
            // No roster to patch: the load that brings it must be read after these rows.
            targets.supersedeRosterLoad(serverId, reason)
        }
        val agentRows = plan.upsertsOf(RaftSyncRowEntity.Agent)
        if (agentRows.isNotEmpty()) {
            val agents = mutableListOf<MemberProfile>()
            agentRows.forEach { (id, row) ->
                when (val parsed = parseSharedAgentRow(row)) {
                    is SharedSyncedAgentRow.Member -> agents += parsed.profile
                    SharedSyncedAgentRow.Deleted -> rosterDeletes += MemberId(id) to MemberKind.Agent
                    SharedSyncedAgentRow.Malformed -> resnapshot += RaftSyncSnapshot.Agents
                }
            }
            rosterUpserts += agents.resolveComputerLabels(targets.machineNames(serverId))
        }
        if (rosterUpserts.isNotEmpty() || rosterDeletes.isNotEmpty()) {
            targets.applyRosterRows(serverId, rosterUpserts, rosterDeletes)
        }

        // Computers: machine rows. A renamed machine also renames the roster's agent labels.
        val machineRows = plan.upsertsOf(RaftSyncRowEntity.Machine)
        val machineDeletes = plan.deletesOf(RaftSyncRowEntity.Machine)
        if (machineRows.isNotEmpty() || machineDeletes.isNotEmpty()) {
            val namesBefore = targets.machineNames(serverId)
            val machines = machineRows.values.mapNotNull { row ->
                parseSyncedComputerRow(row).also { if (it == null) resnapshot += RaftSyncSnapshot.Machines }
            }
            targets.applyMachineRows(serverId, machines, machineDeletes)
            if (machines.any { machine -> namesBefore[machine.id]?.let { it != machine.name } == true }) {
                resnapshot += RaftSyncSnapshot.Agents
            }
        }

        // Activity rows (inbox_row capability): applied in place by the Activity stores. A conversation
        // change whose row came with it needs no Activity read; one without (an Artifact row, a row the
        // server could not read) reads the lists as before.
        val inboxRows =
            if (plan.inboxRows.isEmpty()) RaftSyncInboxRowsOutcome.NotApplied else targets.applyInboxRows(serverId, plan.inboxRows)
        val settledRows = (inboxRows as? RaftSyncInboxRowsOutcome.Applied)?.settledIds.orEmpty()

        // Home: channel list, orders, the viewer's own conversation state.
        val conversations = applyConversationStates(serverId, plan, settledRows)
        val homeChanged =
            RaftSyncRowEntity.Channel in plan.upserts || RaftSyncRowEntity.Channel in plan.deletes ||
                RaftSyncRowEntity.SidebarOrder in plan.upserts || RaftSyncRowEntity.SidebarOrder in plan.deletes ||
                RaftSyncRowEntity.ServerOrder in plan.upserts || RaftSyncRowEntity.ServerOrder in plan.deletes ||
                conversations.home ||
                plan.removedScopes.any { it.kind == RaftSyncScopeKind.Channel }
        val activityChanged =
            RaftSyncRowEntity.Channel in plan.upserts || RaftSyncRowEntity.Channel in plan.deletes ||
                conversations.activity ||
                plan.removedScopes.any { it.kind == RaftSyncScopeKind.Channel }
        if (homeChanged && !resnapshot.any { it in HomeSnapshots }) refreshHome(serverId, reason)
        if (activityChanged && !resnapshot.any { it in ActivitySnapshots }) refreshActivity(serverId, reason)

        // Channel member rows: actively observed rosters refetch; unobserved loaded slices invalidate.
        val memberChannels =
            (plan.upsertsOf(RaftSyncRowEntity.ChannelMember).keys + plan.deletesOf(RaftSyncRowEntity.ChannelMember))
                .mapNotNullTo(LinkedHashSet()) { id -> id.substringBefore(':').takeIf { it.isNotBlank() } }
        if (memberChannels.isNotEmpty() && RaftSyncSnapshot.ChannelMembers !in resnapshot) {
            // Only the exact failed observed channel ids are retaken on the next pull (Hands 5f4a14be).
            recordChannelMembersRefreshFailure(
                serverId = serverId,
                result = targets.refreshChannelMembers(serverId, memberChannels, reason),
            )
        }

        // Messages changed in place (a card message's update): replace them where they are held. A
        // row that doesn't read is left to the next read of its conversation (there is no snapshot).
        val messageRows = plan.upsertsOf(RaftSyncRowEntity.Message)
        val messages = messageRows.values.mapNotNull { row -> parseSharedMessageRow(row) }
        if (messages.isNotEmpty()) targets.applyMessageRows(serverId, messages)

        // Tasks (botiverse/slock #14975): upserts and channel-scoped deletes apply in seq order; a
        // malformed row or canonical conflict falls back fail-closed to a channel/tasks resnapshot
        // without discarding valid changes in the same page.
        val taskResnapshotChannels = LinkedHashSet(plan.taskResnapshotChannelIds)
        val taskMutations = ArrayList<SharedSyncedTaskMutation>(plan.taskChanges.size)
        var taskMalformedCount = 0
        for (taskChange in plan.taskChanges) {
            when (taskChange) {
                is RaftSyncChange.TaskUpsert -> {
                    val parsed = parseSharedTaskRow(taskChange.row)
                    val parsedChannelId = parsed?.channelId?.value?.trim()?.takeIf { it.isNotEmpty() }
                    if (parsed != null && parsed.id.value == taskChange.id && parsedChannelId != null) {
                        taskMutations += SharedSyncedTaskMutation.Upsert(parsed)
                    } else {
                        taskMalformedCount += 1
                        val fallbackChannelId =
                            (taskChange.row["channelId"] as? JsonPrimitive)
                                ?.takeIf { it.isString }
                                ?.content
                                ?.trim()
                                ?.takeIf { it.isNotEmpty() }
                        if (fallbackChannelId != null) {
                            taskResnapshotChannels += fallbackChannelId
                        } else {
                            resnapshot += RaftSyncSnapshot.Tasks
                            wholeServerTaskResnapshot = true
                        }
                    }
                }
                is RaftSyncChange.TaskDelete -> {
                    val channelId = taskChange.channelId.trim()
                    if (channelId.isNotEmpty()) {
                        taskMutations += SharedSyncedTaskMutation.Delete(ChannelId(channelId), TaskId(taskChange.id))
                    } else {
                        taskMalformedCount += 1
                        resnapshot += RaftSyncSnapshot.Tasks
                        wholeServerTaskResnapshot = true
                    }
                }
                is RaftSyncChange.TaskResnapshot -> {
                    val channelId = taskChange.channelId.trim()
                    if (channelId.isNotEmpty()) {
                        taskResnapshotChannels += channelId
                    } else {
                        resnapshot += RaftSyncSnapshot.Tasks
                        wholeServerTaskResnapshot = true
                    }
                }
            }
        }
        if (taskMutations.isNotEmpty()) {
            taskResnapshotChannels += targets.applyTaskChanges(serverId, taskMutations)
        }
        // `scope` `added` for a channel: slock leaves `"tasks"` out of SYNC_CHANNEL_SCOPE_SNAPSHOTS
        // for legacy web compatibility; a client that keeps task lists also re-reads added channels.
        if (!serverRemoved && plan.addedChannelIds.isNotEmpty()) {
            taskResnapshotChannels += plan.addedChannelIds
        }
        if (taskResnapshotChannels.isNotEmpty() && RaftSyncSnapshot.Tasks !in resnapshot) {
            if (!targets.refreshTasks(serverId, taskResnapshotChannels, reason)) {
                ledger.markFailed(serverId, setOf(RaftSyncSnapshot.Tasks))
            }
        }

        if (resnapshot.isNotEmpty()) {
            // Snapshots of an added channel scope: only that channel's member roster.
            val channelIds = plan.addedChannelIds.takeIf { it.isNotEmpty() && !serverRemoved }
            val taskChannelIds = if (wholeServerTaskResnapshot) null else channelIds
            // The cursor goes on past this page: what failed is read again by the next pull.
            recordReloadFailures(
                serverId = serverId,
                failures = reload(serverId, resnapshot, channelIds, reason = "sync_resnapshot", taskChannelIds = taskChannelIds),
            )
        }
        logger(
            "sync_changes_apply serverId=$serverId rosterUpserts=${rosterUpserts.size} rosterDeletes=${rosterDeletes.size} " +
                "machines=${machineRows.size}/${machineDeletes.size} home=$homeChanged activity=$activityChanged " +
                "remoteDone=${conversations.remoteDone} channelMembers=${memberChannels.size} resnapshot=${resnapshot.joinToString(",") { it.wireName }.ifEmpty { "none" }} " +
                "removedScopes=${plan.removedScopes.size} messages=${messages.size}/${messageRows.size} " +
                "tasks=${taskMutations.size}/${plan.taskChanges.size} taskResnapshot=${taskResnapshotChannels.size}" +
                (if (taskMalformedCount > 0) " taskMalformed=$taskMalformedCount" else "") +
                " inboxRows=${settledRows.size}/${plan.inboxRows.size}" +
                (if (plan.inboxRows.isNotEmpty() && inboxRows == RaftSyncInboxRowsOutcome.NotApplied) " inboxRowsApplied=false" else "")
        )
        return settledRows.size
    }

    private class ConversationRefresh(val home: Boolean, val activity: Boolean, val remoteDone: Int)

    /**
     * Web socketBridge `applySyncConversation` for Done (botiverse/slock bab9dd8c0): another
     * device's Done is applied to the Activity rows in place; anything this client can't patch
     * (no state, an undone, a follow change, a read alone, a row it can't judge) reads the lists,
     * and like web the conversations after the first such one are left to that read.
     *
     * A conversation in [settledRows] (its `inbox_row` came in the same page and the Activity stores
     * took it) needs no Activity read and no Done patch: the row change is the newer state. Home still
     * reads its lists for what it shows of it (a read, a follow, an undone, or no state at all).
     */
    private fun applyConversationStates(serverId: String, plan: RaftSyncApplyPlan, settledRows: Set<String>): ConversationRefresh {
        var home = false
        var activity = false
        var remoteDone = 0
        for (conversationId in plan.staleConversations.keys) {
            val state = plan.conversationStates[conversationId]
            if (conversationId in settledRows) {
                if (state == null || state.read != null || state.follow != null || state.done?.done == false) home = true
                continue
            }
            val done = state?.done
            if (state == null || state.follow != null || done == null || !done.done) {
                return ConversationRefresh(home = true, activity = true, remoteDone = remoteDone)
            }
            when (targets.applyRemoteDone(serverId, conversationId, done.doneFrontierSeq)) {
                ActivityRemoteDoneOutcome.CannotTell ->
                    return ConversationRefresh(home = true, activity = true, remoteDone = remoteDone)
                ActivityRemoteDoneOutcome.Removed -> remoteDone += 1
                // The row stays as shown; a read that came with the Done only comes with the lists.
                ActivityRemoteDoneOutcome.KeptNewerActivity -> if (state.read != null) activity = true
            }
            // This client patches no read from a sync change: Home's unread takes it from its lists.
            if (state.read != null) home = true
        }
        return ConversationRefresh(home = home, activity = activity, remoteDone = remoteDone)
    }

    private fun refreshHome(serverId: String, reason: String) {
        ledger.requestListRead(serverId, RaftSyncListRead.Home)
        targets.refreshHome(serverId, reason)
    }

    private fun refreshActivity(serverId: String, reason: String) {
        ledger.requestListRead(serverId, RaftSyncListRead.Activity)
        targets.refreshActivity(serverId, reason)
    }

    private class ReloadFailures(
        val snapshots: Set<RaftSyncSnapshot>,
        val failedChannelMemberIds: Set<String>,
    )

    private fun recordReloadFailures(serverId: String, failures: ReloadFailures) {
        ledger.markFailed(serverId, failures.snapshots)
        ledger.markFailedChannelMembers(serverId, failures.failedChannelMemberIds)
    }

    private fun recordChannelMembersRefreshFailure(
        serverId: String,
        result: RaftSyncChannelMembersRefreshResult,
    ) {
        when (result) {
            RaftSyncChannelMembersRefreshResult.AllSucceeded -> Unit
            is RaftSyncChannelMembersRefreshResult.FailedChannels -> {
                val clean = result.channelIds.mapNotNullTo(LinkedHashSet()) { it.trim().takeIf(String::isNotEmpty) }
                if (clean.isEmpty()) {
                    ledger.markFailed(serverId, setOf(RaftSyncSnapshot.ChannelMembers))
                } else {
                    ledger.markFailedChannelMembers(serverId, clean)
                }
            }
            RaftSyncChannelMembersRefreshResult.FailedAll ->
                ledger.markFailed(serverId, setOf(RaftSyncSnapshot.ChannelMembers))
        }
    }

    /** Takes [snapshots]; returns the awaited ones that failed (Home / Activity settle in the ledger). */
    private suspend fun reload(
        serverId: String,
        snapshots: Set<RaftSyncSnapshot>,
        channelIds: Set<String>?,
        reason: String,
        taskChannelIds: Set<String>? = channelIds,
    ): ReloadFailures {
        val failed = LinkedHashSet<RaftSyncSnapshot>()
        val failedChannelMemberIds = LinkedHashSet<String>()
        if (snapshots.any { it in HomeSnapshots }) refreshHome(serverId, reason)
        if (snapshots.any { it in ActivitySnapshots }) refreshActivity(serverId, reason)
        // The roster load reads members, agents and machine names together.
        val rosterSnapshots = snapshots.filterTo(LinkedHashSet()) { it == RaftSyncSnapshot.ServerMembers || it == RaftSyncSnapshot.Agents }
        if (rosterSnapshots.isNotEmpty()) {
            when (targets.reloadRoster(serverId, reason)) {
                RaftSyncSnapshotRead.Taken -> Unit
                RaftSyncSnapshotRead.NotReadable -> logger("sync_changes_snapshot_not_readable serverId=$serverId snapshot=roster reason=$reason")
                RaftSyncSnapshotRead.Failed -> failed += rosterSnapshots
            }
        }
        if (RaftSyncSnapshot.Machines in snapshots) {
            when (targets.reloadMachines(serverId, reason)) {
                RaftSyncSnapshotRead.Taken -> Unit
                RaftSyncSnapshotRead.NotReadable -> logger("sync_changes_snapshot_not_readable serverId=$serverId snapshot=machines reason=$reason")
                RaftSyncSnapshotRead.Failed -> failed += RaftSyncSnapshot.Machines
            }
        }
        if (RaftSyncSnapshot.ChannelMembers in snapshots) {
            when (val refresh = targets.refreshChannelMembers(serverId, channelIds, reason)) {
                RaftSyncChannelMembersRefreshResult.AllSucceeded -> Unit
                is RaftSyncChannelMembersRefreshResult.FailedChannels -> {
                    val clean = refresh.channelIds.mapNotNullTo(LinkedHashSet()) { it.trim().takeIf(String::isNotEmpty) }
                    if (clean.isEmpty()) failed += RaftSyncSnapshot.ChannelMembers else failedChannelMemberIds += clean
                }
                RaftSyncChannelMembersRefreshResult.FailedAll -> failed += RaftSyncSnapshot.ChannelMembers
            }
        }
        if (RaftSyncSnapshot.Tasks in snapshots && !targets.refreshTasks(serverId, taskChannelIds, reason)) {
            failed += RaftSyncSnapshot.Tasks
        }
        return ReloadFailures(snapshots = failed, failedChannelMemberIds = failedChannelMemberIds)
    }

    private companion object {
        val HomeSnapshots =
            setOf(
                RaftSyncSnapshot.Channels,
                RaftSyncSnapshot.SidebarOrder,
                RaftSyncSnapshot.ServerOrder,
                RaftSyncSnapshot.FollowedThreads,
                RaftSyncSnapshot.Unread,
            )
        val ActivitySnapshots = setOf(RaftSyncSnapshot.Channels, RaftSyncSnapshot.Inbox, RaftSyncSnapshot.Unread)
    }
}
