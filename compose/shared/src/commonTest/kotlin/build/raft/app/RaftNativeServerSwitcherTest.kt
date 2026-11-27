package build.raft.app

import build.raft.app.shell.RaftNativeServerSwitcherActionCreateServer
import build.raft.app.shell.RaftNativeServerSwitcherActionInviteHuman
import build.raft.app.shell.RaftNativeServerSwitcherActionJoinCommunityPrefix
import build.raft.app.shell.RaftNativeServerSwitcherBridge
import build.raft.app.shell.RaftNativeServerSwitcherListener
import build.raft.app.shell.RaftNativeServerSwitcherState
import build.raft.app.shell.raftNativeServerSwitcherState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftNativeServerSwitcherTest {
    private val labels =
        RaftHomeShellLabels(
            noServersAvailable = "No servers",
            switchOrCreateServer = "Switch or create server",
            joinCommunityServer = "Join Community",
            joinChineseCommunityServer = "Join Chinese Community",
            inviteHuman = "Invite Human",
            serverNotificationsMuted = "Notifications muted",
        )

    private val sampleServers =
        listOf(
            RaftServerSwitcherItem(
                id = "srv-1",
                name = "Botiverse",
                slug = "botiverse",
                publicPageLive = true,
                activityUnreadCount = 0L,
            ),
            RaftServerSwitcherItem(
                id = "srv-2",
                name = "Engineering",
                slug = "eng",
                activityUnreadCount = 12L,
                serverPushMuted = true,
            ),
        )

    @Test
    fun stateProjectionMapsRowsBadgesActionsAndPendingLockout() {
        val state =
            raftNativeServerSwitcherState(
                open = true,
                servers = sampleServers,
                currentServerId = "srv-1",
                pendingServerId = null,
                prefersChineseCommunity = true,
                labels = labels,
                hasInviteHumanAction = true,
                avatarsByServerId = emptyMap(),
                anchorTopPt = 108.0,
                anchorStartPt = 8.0,
            )

        assertTrue(state.open)
        assertFalse(state.switchPending)
        assertTrue(state.reorderable)
        assertEquals(108.0, state.anchorTopPt)
        assertEquals(8.0, state.anchorStartPt)
        assertEquals(2, state.rows.size)
        assertTrue(state.rows[0].selected)
        assertTrue(state.rows[0].publicPageLive)
        assertNull(state.rows[0].badgeLabel)
        assertEquals("B", state.rows[0].avatarFallbackLabel)

        assertFalse(state.rows[1].selected)
        assertEquals("12", state.rows[1].badgeLabel)
        assertTrue(state.rows[1].badgeMuted)

        assertEquals(
            listOf(
                "${RaftNativeServerSwitcherActionJoinCommunityPrefix}community-cn",
                "${RaftNativeServerSwitcherActionJoinCommunityPrefix}community",
                RaftNativeServerSwitcherActionCreateServer,
                RaftNativeServerSwitcherActionInviteHuman,
            ),
            state.actions.map { it.id },
        )

        val pending =
            raftNativeServerSwitcherState(
                open = true,
                servers = sampleServers,
                currentServerId = "srv-1",
                pendingServerId = "srv-2",
                prefersChineseCommunity = false,
                labels = labels,
                hasInviteHumanAction = false,
                avatarsByServerId = emptyMap(),
                anchorTopPt = 108.0,
                anchorStartPt = 8.0,
            )
        assertTrue(pending.switchPending)
        assertFalse(pending.reorderable)
        assertEquals(
            listOf(
                "${RaftNativeServerSwitcherActionJoinCommunityPrefix}community",
                RaftNativeServerSwitcherActionCreateServer,
            ),
            pending.actions.map { it.id },
        )
    }

    @Test
    fun bridgeDispatchesSelectActionReorderDismissAndRejectsPendingOrInvalidCalls() {
        val bridge = RaftNativeServerSwitcherBridge()
        val observed = mutableListOf<RaftNativeServerSwitcherState>()
        bridge.listener =
            object : RaftNativeServerSwitcherListener {
                override fun onNativeServerSwitcherStateChanged(state: RaftNativeServerSwitcherState) {
                    observed += state
                }
            }
        assertEquals(listOf(RaftNativeServerSwitcherState.Closed), observed)

        val selected = mutableListOf<String>()
        val actions = mutableListOf<String>()
        val reorders = mutableListOf<List<String>>()
        var dismissals = 0
        val token = Any()

        val openState =
            raftNativeServerSwitcherState(
                open = true,
                servers = sampleServers,
                currentServerId = "srv-1",
                pendingServerId = null,
                prefersChineseCommunity = false,
                labels = labels,
                hasInviteHumanAction = true,
                avatarsByServerId = emptyMap(),
                anchorTopPt = 104.0,
                anchorStartPt = 8.0,
            )
        bridge.publish(
            token = token,
            state = openState,
            onSelectServer = { selected += it },
            onTapAction = { actions += it },
            onReorderServers = { reorders += it },
            onDismiss = { dismissals += 1 },
        )

        assertTrue(bridge.selectServer("srv-2"))
        assertFalse(bridge.selectServer("unknown"))
        assertTrue(bridge.tapAction(RaftNativeServerSwitcherActionInviteHuman))
        assertFalse(bridge.tapAction("missing"))
        assertTrue(bridge.reorderServers(listOf("srv-2", "srv-1")))
        assertFalse(bridge.reorderServers(listOf("srv-1", "srv-2")))
        assertFalse(bridge.reorderServers(listOf("srv-1")))
        bridge.dismiss()

        assertEquals(listOf("srv-2"), selected)
        assertEquals(listOf(RaftNativeServerSwitcherActionInviteHuman), actions)
        assertEquals(listOf(listOf("srv-2", "srv-1")), reorders)
        assertEquals(1, dismissals)

        // Pending switch rejects selectServer and reorderServers.
        bridge.publish(
            token = token,
            state = openState.copy(switchPending = true, reorderable = false),
            onSelectServer = { selected += it },
            onTapAction = { actions += it },
            onReorderServers = { reorders += it },
            onDismiss = { dismissals += 1 },
        )
        assertFalse(bridge.selectServer("srv-2"))
        assertFalse(bridge.reorderServers(listOf("srv-2", "srv-1")))

        // Retracting closes state and blocks callbacks.
        bridge.retract(token)
        assertFalse(bridge.state.value.open)
        assertFalse(bridge.selectServer("srv-1"))
    }
}
