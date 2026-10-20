package build.raft.app

import build.raft.app.data.ThreadAgentFollower
import build.raft.app.data.ThreadAgentFollowerRoster
import build.raft.app.data.ThreadAgentFollowersState
import build.raft.app.data.ThreadFollowerUndo
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals

/** Task #24: a follower row opens the agent's profile; its X removes only what web lets it. */
class ThreadFollowerRowActionsTest {
    private fun follower(id: String, serverId: String = "server-a", currentServer: Boolean = true, canRemove: Boolean = true) =
        ThreadAgentFollower(
            id = id, name = id, status = "online", serverId = serverId,
            isCurrentServer = currentServer, canRemove = canRemove,
        )

    private fun state(vararg agents: ThreadAgentFollower, canManage: Boolean = true) =
        ThreadAgentFollowersState(roster = ThreadAgentFollowerRoster("thread-1", canManage, agents.toList()))

    @Test fun rowOpensTheAgentProfileOnItsOwnServer() {
        assertEquals(
            MemberDetailIdentity(MemberId("agent-1"), MemberKind.Agent, ServerId("server-a")),
            threadFollowerProfileIdentity(follower("agent-1")),
        )
        // A peer server's agent keeps its server, so the profile opens read-only (web opens it too).
        assertEquals(
            MemberDetailIdentity(MemberId("agent-2"), MemberKind.Agent, ServerId("server-b")),
            threadFollowerProfileIdentity(follower("agent-2", serverId = " server-b ", currentServer = false)),
        )
        assertEquals(
            MemberDetailIdentity(MemberId("agent-3"), MemberKind.Agent, null),
            threadFollowerProfileIdentity(follower("agent-3", serverId = " ")),
        )
    }

    @Test fun removeButtonIsEnabledOnlyForRemovableFollowersWhileIdle() {
        val local = follower("local")
        val peer = follower("peer", serverId = "server-b", currentServer = false)
        val locked = follower("locked", canRemove = false)
        data class Case(val name: String, val agent: ThreadAgentFollower, val state: ThreadAgentFollowersState, val expected: Boolean)
        listOf(
            Case("local follower", local, state(local), true),
            Case("peer server follower", peer, state(peer), false),
            Case("server forbids removal", locked, state(locked), false),
            Case("viewer cannot manage", local, state(local, canManage = false), false),
            Case("removal in flight", local, state(local).copy(mutating = true), false),
            Case("undo window open", local, state(local).copy(undo = ThreadFollowerUndo(local, "token", 0L)), false),
        ).forEach { case ->
            assertEquals(case.expected, threadFollowerRemoveEnabled(case.agent, case.state), case.name)
        }
    }
}
