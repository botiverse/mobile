package build.raft.app

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MembersAdaptiveWorkspaceTest {
    private val local = ServerId("local")
    private val peer = ServerId("peer")
    private val agent = MemberProfile(MemberId("same-id"), "Same display name", "same", MemberKind.Agent)

    @Test fun selectionUsesKindAndServerInsteadOfDisplayNameOrBareId() {
        val selected = MemberDetailIdentity(agent.id, MemberKind.Agent, local)
        assertTrue(agent.matchesMemberSelection(selected, local))
        assertFalse(agent.copy(kind = MemberKind.Human).matchesMemberSelection(selected, local))
        assertFalse(agent.copy(serverId = peer).matchesMemberSelection(selected, local))
        assertFalse(agent.matchesMemberSelection(selected, peer))
        assertFalse(agent.matchesMemberSelection(null, local))
    }

    @Test fun remoteSelectionNeverHighlightsSameIdLocalMember() {
        val selected = MemberDetailIdentity(agent.id, MemberKind.Agent, peer)
        assertFalse(agent.matchesMemberSelection(selected, local))
        assertTrue(agent.copy(serverId = peer).matchesMemberSelection(selected, local))
        assertFalse(agent.copy(id = MemberId("another")).matchesMemberSelection(selected, local))
    }
}
