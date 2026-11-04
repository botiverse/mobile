package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.ServerSummary

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
     * its realtime refresh reducer (coalesced; marked dirty while Home is hidden).
     */
    fun refreshHome(serverId: String, reason: String)

    /** Activity (inbox) and its unread summary refetch through the Activity realtime reducer. */
    fun refreshActivity(serverId: String, reason: String)

    /**
     * Another device marked conversation [channelId] Done (a `conversation` change with
     * `done.done` true): every Activity store showing the server drops its row when
     * [doneFrontierSeq] covers it ([SharedActivityStore.applyRemoteDone]).
     * [ActivityRemoteDoneOutcome.CannotTell] from any of them (or no store): read the lists.
     */
    fun applyRemoteDone(serverId: String, channelId: String, doneFrontierSeq: String?): ActivityRemoteDoneOutcome

    /** Loaded channel member rosters refetch: [channelIds], or every loaded one when null. */
    suspend fun refreshChannelMembers(serverId: String, channelIds: Set<String>?, reason: String)

    /** Forced roster reload (`/servers/:id/members` + `/agents` + `/servers/:id/machines`); false on failure. */
    suspend fun reloadRoster(serverId: String, reason: String): Boolean

    /** Forced machine list reload of the Computers store; false on failure. */
    suspend fun reloadMachines(serverId: String, reason: String): Boolean

    /**
     * `message` rows (RFC 082 §4.4): each replaces that message, by id, in the open conversation
     * pages of the server, through their reducers (as a `message:updated` would). A page that does
     * not hold the message changes nothing.
     */
    suspend fun applyMessageRows(serverId: String, messages: List<Message>)
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
 */
internal class RaftSyncChangesStoreRouter(
    private val targets: RaftSyncStoreTargets,
    private val logger: (String) -> Unit = {},
) : RaftSyncChangesSink {
    override suspend fun reloadSnapshots(scope: RaftSyncScope, snapshots: Set<RaftSyncSnapshot>, reason: String): Boolean =
        reload(scope.serverId, snapshots, channelIds = null, reason = "sync_$reason")

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
        if (homeChanged && !resnapshot.any { it in HomeSnapshots }) targets.refreshHome(serverId, reason)
        if (activityChanged && !resnapshot.any { it in ActivitySnapshots }) targets.refreshActivity(serverId, reason)

        // Channel member rows: the loaded roster of each channel they name.
        val memberChannels =
            (plan.upsertsOf(RaftSyncRowEntity.ChannelMember).keys + plan.deletesOf(RaftSyncRowEntity.ChannelMember))
                .mapNotNullTo(LinkedHashSet()) { id -> id.substringBefore(':').takeIf { it.isNotBlank() } }
        if (memberChannels.isNotEmpty() && RaftSyncSnapshot.ChannelMembers !in resnapshot) {
            targets.refreshChannelMembers(serverId, memberChannels, reason)
        }

        // Messages changed in place (a card message's update): replace them where they are held. A
        // row that doesn't read is left to the next read of its conversation (there is no snapshot).
        val messageRows = plan.upsertsOf(RaftSyncRowEntity.Message)
        val messages = messageRows.values.mapNotNull { row -> parseSharedMessageRow(row) }
        if (messages.isNotEmpty()) targets.applyMessageRows(serverId, messages)

        if (resnapshot.isNotEmpty()) {
            // Snapshots of an added channel scope: only that channel's member roster.
            val channelIds = plan.addedChannelIds.takeIf { it.isNotEmpty() && !serverRemoved }
            reload(serverId, resnapshot, channelIds, reason = "sync_resnapshot")
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

    private suspend fun reload(
        serverId: String,
        snapshots: Set<RaftSyncSnapshot>,
        channelIds: Set<String>?,
        reason: String,
    ): Boolean {
        var ok = true
        if (snapshots.any { it in HomeSnapshots }) targets.refreshHome(serverId, reason)
        if (snapshots.any { it in ActivitySnapshots }) targets.refreshActivity(serverId, reason)
        // The roster load reads members, agents and machine names together.
        if (RaftSyncSnapshot.ServerMembers in snapshots || RaftSyncSnapshot.Agents in snapshots) {
            ok = targets.reloadRoster(serverId, reason) && ok
        }
        if (RaftSyncSnapshot.Machines in snapshots) ok = targets.reloadMachines(serverId, reason) && ok
        if (RaftSyncSnapshot.ChannelMembers in snapshots) targets.refreshChannelMembers(serverId, channelIds, reason)
        return ok
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
