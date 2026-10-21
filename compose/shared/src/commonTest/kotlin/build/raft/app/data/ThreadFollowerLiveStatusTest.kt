package build.raft.app.data

import build.raft.app.RaftColor
import build.raft.app.raftScopedAgentStatusResolver
import build.raft.app.threadFollowerStatusIndicator
import build.raft.app.threadFollowerStatusResolver
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.RaftAgentStatusIndicatorState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task #20: the followers endpoint's `status` is the agents.status lifecycle column ("active"),
 * so a working agent read "Online" in the popover while its message avatar in the same thread
 * was yellow. A follower row now reads only the live member-status source the message avatar
 * reads; the lifecycle field never drives the dot or the caption.
 */
class ThreadFollowerLiveStatusTest {
    private val serverId = ServerId("server-1")

    private fun follower(id: String, currentServer: Boolean = true) =
        ThreadAgentFollower(
            id = id, name = id, status = "active", // what the endpoint sends for a live agent
            serverId = if (currentServer) serverId.value else "server-peer",
            isCurrentServer = currentServer, canRemove = currentServer,
        )

    private fun store() =
        SharedServerMembersStore(PresenceNoopMemberRepository, presenceRegistry = ProcessMemberPresenceRegistry())

    private fun SharedServerMembersStore.seedAgent(id: String) =
        seed(
            MemberSnapshot(
                ServerSummary(id = serverId, name = "Workspace", slug = "workspace"),
                listOf(
                    MemberProfile(
                        id = MemberId(id), displayName = id, handle = id, kind = MemberKind.Agent,
                        agentStatus = AgentStatus.Online, presence = Presence.Active, serverId = serverId,
                    )
                )
            )
        )

    private fun SharedServerMembersStore.pushStatus(id: String, status: AgentStatus, seq: Long) =
        applyPresence(
            serverId,
            MemberPresenceUpdated(
                memberId = MemberId(id), presence = status.presence, kind = MemberKind.Agent,
                agentStatus = status, serverSeq = seq,
            )
        )

    /** The row's indicator exactly as FollowersPopover resolves it, minus the Compose collection. */
    private fun rowIndicator(store: SharedServerMembersStore, agent: ThreadAgentFollower) =
        threadFollowerStatusResolver(agent, raftScopedAgentStatusResolver(store, serverId.value))(agent.id)
            .map { threadFollowerStatusIndicator(agent, it) }

    @Test
    fun liveWorkingStatusDrivesTheRowNotTheWireLifecycleStatus() = runBlocking {
        val store = store()
        store.seedAgent("lagoon")
        store.pushStatus("lagoon", AgentStatus.Working, seq = 10L)

        val indicator = rowIndicator(store, follower("lagoon")).first()

        assertEquals(AgentStatus.Working, indicator?.status)
        assertEquals(RaftColor.BrandYellow, indicator?.color)
        assertTrue(indicator?.pulsing == true, "a working follower pulses like its message avatar")
        assertEquals("Working", indicator?.label)
    }

    @Test
    fun noLiveEntryShowsNoStatusEvenThoughTheWireSaysActive() = runBlocking {
        // Same follower, same wire "active", same resolver; only the live source's entry differs.
        assertNull(rowIndicator(store(), follower("lagoon")).first())
    }

    @Test
    fun peerServerFollowerShowsNoStatusAndCurrentServerTwinDoes() = runBlocking {
        val store = store()
        store.seedAgent("lagoon")
        store.pushStatus("lagoon", AgentStatus.Working, seq = 10L)

        assertNull(rowIndicator(store, follower("lagoon", currentServer = false)).first())
        assertNull(threadFollowerStatusIndicator(follower("lagoon", currentServer = false), rowIndicator(store, follower("lagoon")).first()))
        assertEquals(AgentStatus.Working, rowIndicator(store, follower("lagoon")).first()?.status)
    }

    @Test
    fun rowFollowsTheLiveStatusWhileThePopoverIsOpen() = runBlocking {
        val store = store()
        store.seedAgent("lagoon")
        store.pushStatus("lagoon", AgentStatus.Working, seq = 10L)
        // One subscription held across the change, as the open popover row collects it as state.
        val row = MutableStateFlow<RaftAgentStatusIndicatorState?>(null)
        val held = launch { rowIndicator(store, follower("lagoon")).collect { row.value = it } }
        try {
            assertEquals(AgentStatus.Working, withTimeout(5_000) { row.filterNotNull().first() }.status)
            store.pushStatus("lagoon", AgentStatus.Online, seq = 11L)
            val after = withTimeout(5_000) { row.filter { it?.status == AgentStatus.Online }.first() }
            assertEquals(RaftColor.AccentLime, after?.color)
            assertEquals(false, after?.pulsing)
        } finally {
            held.cancel()
        }
    }
}
