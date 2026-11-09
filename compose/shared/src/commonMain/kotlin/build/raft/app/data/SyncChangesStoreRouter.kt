package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.ServerSummary
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The stores incremental sync writes into, each through its own reducer (MVI: no UI state is
 * touched here). The provider binds them to every shell store showing the server.
 */
internal interface RaftSyncStoreTargets {
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
     * Another device marked conversation [channelId] Done (a `conversation` change with
     * `done.done` true): every Activity store showing the server drops its row when
     * [doneFrontierSeq] covers it ([SharedActivityStore.applyRemoteDone]).
     * [ActivityRemoteDoneOutcome.CannotTell] from any of them (or no store): read the lists.
     */
    fun applyRemoteDone(serverId: String, channelId: String, doneFrontierSeq: String?): ActivityRemoteDoneOutcome

    /**
     * Loaded channel member rosters refetch: [channelIds], or every loaded one when null. False when
     * one of them [RaftSyncSnapshotRead.Failed] (a channel the viewer can no longer read is not a failure).
     */
    suspend fun refreshChannelMembers(serverId: String, channelIds: Set<String>?, reason: String): Boolean

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
}

/** How one snapshot reload ended, for the cursor. */
internal enum class RaftSyncSnapshotRead {
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
internal fun DataResult<*>.syncSnapshotRead(): RaftSyncSnapshotRead =
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
internal fun List<DataResult<*>?>.syncSnapshotRead(): RaftSyncSnapshotRead {
    val reads = map { it?.syncSnapshotRead() ?: RaftSyncSnapshotRead.Failed }
    return when {
        RaftSyncSnapshotRead.Taken in reads -> RaftSyncSnapshotRead.Taken
        RaftSyncSnapshotRead.NotReadable in reads -> RaftSyncSnapshotRead.NotReadable
        else -> RaftSyncSnapshotRead.Failed
    }
}

/** A list a sync change reloads without awaiting it (a store reload launched through its reducer). */
internal enum class RaftSyncListRead { Home, Activity }

/** What the next pull reads again: the reloads after an earlier pull that failed. */
internal data class RaftSyncRetakes(
    val lists: Set<RaftSyncListRead> = emptySet(),
    val snapshots: Set<RaftSyncSnapshot> = emptySet(),
) {
    val isEmpty: Boolean
        get() = lists.isEmpty() && snapshots.isEmpty()
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
internal class RaftSyncReloadLedger {
    private val lock = SynchronizedObject()
    private var clock = 0L
    private val requested = mutableMapOf<Pair<String, RaftSyncListRead>, Long>()
    private val failedLists = mutableSetOf<Pair<String, RaftSyncListRead>>()
    private val failedSnapshots = mutableMapOf<String, MutableSet<RaftSyncSnapshot>>()

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
        synchronized(lock) { failedSnapshots.getOrPut(serverId) { mutableSetOf() } += snapshots }
    }

    /** What [serverId]'s next pull reads again; forgotten here (a retake that fails is marked again). */
    fun takeRetakes(serverId: String): RaftSyncRetakes =
        synchronized(lock) {
            val lists = failedLists.filter { it.first == serverId }.mapTo(LinkedHashSet()) { it.second }
            failedLists.removeAll { it.first == serverId }
            RaftSyncRetakes(lists = lists, snapshots = failedSnapshots.remove(serverId).orEmpty())
        }
}

/**
 * Routes pulled changes to the stores ([RaftSyncChangesSink] of the provider).
 *
 * Row-level today: `server_member`, `agent` (roster), `machine` (Computers), `message` (the open
 * conversation pages) and a `conversation` change carrying another device's Done (Activity).
 * Signal-level (the store's existing reload, once per page and only when something changed):
 * `channel`, `sidebar_order`, `server_order`, any other `conversation` change (Home + Activity) and
 * `channel_member` (the loaded rosters of those channels). A row that does not decode retakes its
 * entity's snapshot (a `message` row, which has none, is left to its conversation's next read).
 *
 * A reload after a pull that fails is read again by the next pull ([RaftSyncReloadLedger]); a
 * first-sync / reset roster or machine snapshot that fails still fails its pull.
 */
internal class RaftSyncChangesStoreRouter(
    private val targets: RaftSyncStoreTargets,
    private val ledger: RaftSyncReloadLedger = RaftSyncReloadLedger(),
    private val logger: (String) -> Unit = {},
) : RaftSyncChangesSink {
    override suspend fun reloadSnapshots(scope: RaftSyncScope, snapshots: Set<RaftSyncSnapshot>, reason: String): Boolean {
        val failed = reload(scope.serverId, snapshots, channelIds = null, reason = "sync_$reason")
        // The cursor stands on the roster and machine lists (the change log patches them row by
        // row). Channel member rosters are only the loaded ones: retaken by the next pull.
        val pullFailed = failed.any { it != RaftSyncSnapshot.ChannelMembers }
        if (!pullFailed) ledger.markFailed(scope.serverId, failed)
        return !pullFailed
    }

    override suspend fun retakeFailedReloads(scope: RaftSyncScope) {
        val serverId = scope.serverId
        val retakes = ledger.takeRetakes(serverId)
        if (retakes.isEmpty) return
        val reason = "sync_retake"
        logger(
            "sync_changes_retake serverId=$serverId lists=${retakes.lists.joinToString(",") { it.name }.ifEmpty { "none" }} " +
                "snapshots=${retakes.snapshots.joinToString(",") { it.wireName }.ifEmpty { "none" }}"
        )
        if (RaftSyncListRead.Home in retakes.lists) refreshHome(serverId, reason)
        if (RaftSyncListRead.Activity in retakes.lists) refreshActivity(serverId, reason)
        if (retakes.snapshots.isNotEmpty()) {
            ledger.markFailed(serverId, reload(serverId, retakes.snapshots, channelIds = null, reason = reason))
        }
    }

    override suspend fun apply(scope: RaftSyncScope, plan: RaftSyncApplyPlan) {
        val serverId = scope.serverId
        val reason = "sync_changes"
        val resnapshot = LinkedHashSet(plan.snapshots)

        val serverRemoved = plan.removedScopes.any { it.kind == RaftSyncScopeKind.Server && it.id == serverId }
        if (serverRemoved) {
            // Lost the whole server: the roster / machine / Home loads validate the access loss and drop
            // what is held under it through their existing access-lost paths.
            resnapshot += setOf(RaftSyncSnapshot.Channels, RaftSyncSnapshot.ServerMembers, RaftSyncSnapshot.Machines)
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

        // Home: channel list, orders, the viewer's own conversation state.
        val conversations = applyConversationStates(serverId, plan)
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

        // Channel member rows: the loaded roster of each channel they name.
        val memberChannels =
            (plan.upsertsOf(RaftSyncRowEntity.ChannelMember).keys + plan.deletesOf(RaftSyncRowEntity.ChannelMember))
                .mapNotNullTo(LinkedHashSet()) { id -> id.substringBefore(':').takeIf { it.isNotBlank() } }
        if (memberChannels.isNotEmpty() && RaftSyncSnapshot.ChannelMembers !in resnapshot) {
            // Retaken whole (every loaded roster) by the next pull when one of them failed.
            if (!targets.refreshChannelMembers(serverId, memberChannels, reason)) {
                ledger.markFailed(serverId, setOf(RaftSyncSnapshot.ChannelMembers))
            }
        }

        // Messages changed in place (a card message's update): replace them where they are held. A
        // row that doesn't read is left to the next read of its conversation (there is no snapshot).
        val messageRows = plan.upsertsOf(RaftSyncRowEntity.Message)
        val messages = messageRows.values.mapNotNull { row -> parseSharedMessageRow(row) }
        if (messages.isNotEmpty()) targets.applyMessageRows(serverId, messages)

        if (resnapshot.isNotEmpty()) {
            // Snapshots of an added channel scope: only that channel's member roster.
            val channelIds = plan.addedChannelIds.takeIf { it.isNotEmpty() && !serverRemoved }
            // The cursor goes on past this page: what failed is read again by the next pull.
            ledger.markFailed(serverId, reload(serverId, resnapshot, channelIds, reason = "sync_resnapshot"))
        }
        logger(
            "sync_changes_apply serverId=$serverId rosterUpserts=${rosterUpserts.size} rosterDeletes=${rosterDeletes.size} " +
                "machines=${machineRows.size}/${machineDeletes.size} home=$homeChanged activity=$activityChanged " +
                "remoteDone=${conversations.remoteDone} channelMembers=${memberChannels.size} resnapshot=${resnapshot.joinToString(",") { it.wireName }.ifEmpty { "none" }} " +
                "removedScopes=${plan.removedScopes.size} messages=${messages.size}/${messageRows.size}"
        )
    }

    private class ConversationRefresh(val home: Boolean, val activity: Boolean, val remoteDone: Int)

    /**
     * Web socketBridge `applySyncConversation` for Done (botiverse/slock bab9dd8c0): another
     * device's Done is applied to the Activity rows in place; anything this client can't patch
     * (no state, an undone, a follow change, a read alone, a row it can't judge) reads the lists,
     * and like web the conversations after the first such one are left to that read.
     */
    private fun applyConversationStates(serverId: String, plan: RaftSyncApplyPlan): ConversationRefresh {
        var home = false
        var activity = false
        var remoteDone = 0
        for (conversationId in plan.staleConversations.keys) {
            val state = plan.conversationStates[conversationId]
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

    /** Takes [snapshots]; returns the awaited ones that failed (Home / Activity settle in the ledger). */
    private suspend fun reload(
        serverId: String,
        snapshots: Set<RaftSyncSnapshot>,
        channelIds: Set<String>?,
        reason: String,
    ): Set<RaftSyncSnapshot> {
        val failed = LinkedHashSet<RaftSyncSnapshot>()
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
        if (RaftSyncSnapshot.ChannelMembers in snapshots && !targets.refreshChannelMembers(serverId, channelIds, reason)) {
            failed += RaftSyncSnapshot.ChannelMembers
        }
        return failed
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
