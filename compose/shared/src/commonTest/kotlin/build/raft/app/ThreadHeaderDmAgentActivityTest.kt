package build.raft.app

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadHeaderDmAgentActivityTest {
    @Test
    fun agentDirectMessageResolvesThePeerForLiveHeaderActivity() {
        val display = displayModel(MemberKind.Agent)

        assertEquals("agent-friday", display.dmAgentActivityMemberId())
    }

    @Test
    fun humanDirectMessageNeverShowsAgentActivity() {
        assertNull(displayModel(MemberKind.Human).dmAgentActivityMemberId())
    }

    @Test
    fun channelHeaderNeverReusesAnAgentIdentityAsDmActivity() {
        val display = displayModel(MemberKind.Agent).copy(surfaceKind = RaftThreadSurfaceKind.Channel)

        assertNull(display.dmAgentActivityMemberId())
    }

    @Test
    fun missingOrBlankPeerIdentityFailsClosed() {
        assertNull(displayModel(MemberKind.Agent).copy(dmPeerIdentity = null).dmAgentActivityMemberId())
        assertNull(
            displayModel(MemberKind.Agent)
                .copy(
                    dmPeerIdentity =
                        MemberDetailIdentity(
                            memberId = MemberId("   "),
                            kind = MemberKind.Agent,
                            sourceServerId = ServerId("server-1")
                        )
                )
                .dmAgentActivityMemberId()
        )
    }

    private fun displayModel(kind: MemberKind): ThreadHeaderDisplayModel =
        ThreadHeaderDisplayModel(
            title = "Friday",
            subtitle = null,
            surfaceKind = RaftThreadSurfaceKind.DirectMessage,
            channelKind = RaftThreadSurfaceKind.DirectMessage,
            dmPeerIdentity =
                MemberDetailIdentity(
                    memberId = MemberId("agent-friday"),
                    kind = kind,
                    sourceServerId = ServerId("server-1")
                )
        )
}
