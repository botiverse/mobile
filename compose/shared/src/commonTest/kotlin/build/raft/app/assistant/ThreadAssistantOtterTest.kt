package build.raft.app.assistant

import build.raft.app.data.MyAssistantRecord
import build.raft.app.data.MyAssistantState
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import build.raft.app.model.AgentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where the otter shows and what it shows, against web assistant/AssistantOtter.tsx
 * (`AssistantOtterAnchor`'s `eligible`) and assistantOtterState.ts (`useAssistantOtterMood`).
 */
class ThreadAssistantOtterTest {
    private val assistant = MyAssistantRecord(id = "a1", name = "Ada", agentId = "a1", dmChannelId = "dm1")

    @Test
    fun showsOnlyWhenReadyWithItsDmAndNotInThatDm() {
        val ready = MyAssistantState.Ready(assistant)
        assertEquals("dm1", assistantOtterDmChannelId(ready, conversationChannelId = "general"))
        // Never on the assistant's own DM, nor a thread in it (whose parent channel is passed).
        assertNull(assistantOtterDmChannelId(ready, conversationChannelId = "dm1"))
        // Its DM is still being set up.
        assertNull(assistantOtterDmChannelId(MyAssistantState.Ready(assistant.copy(dmChannelId = null)), "general"))
        // No assistant yet, loading, or off (flag, API, guest).
        assertNull(assistantOtterDmChannelId(MyAssistantState.Ready(null), "general"))
        assertNull(assistantOtterDmChannelId(MyAssistantState.Loading, "general"))
        assertNull(assistantOtterDmChannelId(MyAssistantState.Off, "general"))
        // A conversation whose channel is not known yet.
        assertNull(assistantOtterDmChannelId(ready, conversationChannelId = null))
    }

    @Test
    fun moodFromTheAgentStatusAndTheDmUnread() {
        assertEquals(RaftOtterMood.Idle, assistantOtterMood(agentStatus = null, dmUnreadCount = 0))
        assertEquals(RaftOtterMood.Idle, assistantOtterMood(AgentStatus.Online, 0))
        assertEquals(RaftOtterMood.Idle, assistantOtterMood(AgentStatus.Idle, 0))
        assertEquals(RaftOtterMood.Working, assistantOtterMood(AgentStatus.Thinking, 0))
        assertEquals(RaftOtterMood.Working, assistantOtterMood(AgentStatus.Working, 0))
        assertEquals(RaftOtterMood.Away, assistantOtterMood(AgentStatus.Offline, 0))
        // Unread outranks working and offline (web otterMood order).
        assertEquals(RaftOtterMood.New, assistantOtterMood(AgentStatus.Working, dmUnreadCount = 3))
        assertEquals(RaftOtterMood.New, assistantOtterMood(AgentStatus.Offline, dmUnreadCount = 1))
        // The inputs the app has no source for yet keep the web order when given.
        assertEquals(RaftOtterMood.Confirm, assistantOtterMood(AgentStatus.Working, 1, confirm = true))
        assertEquals(RaftOtterMood.Away, assistantOtterMood(AgentStatus.Working, 1, paused = true, confirm = true))
    }

    @Test
    fun composingIsTextOrAnAttachment() {
        assertFalse(threadComposerComposing(text = "", attachmentCount = 0))
        // Web `content.trim().length > 0`.
        assertFalse(threadComposerComposing(text = "  \n", attachmentCount = 0))
        assertTrue(threadComposerComposing(text = "hi", attachmentCount = 0))
        assertTrue(threadComposerComposing(text = "", attachmentCount = 1))
    }

    @Test
    fun dmUnreadComesFromHomeForThatServer() {
        assertEquals(0, SharedHomeState().directMessageUnreadCount(serverId = "s1", channelId = "dm1"), "Home not loaded")
        val home =
            SharedHomeState(
                content =
                    SharedHomeContentState.Content(
                        snapshot =
                            HomeSnapshot(
                                server = ServerSummary(id = ServerId("s1"), name = "One"),
                                currentMember = MemberProfile(id = MemberId("u1"), displayName = "Viewer", handle = "viewer", kind = MemberKind.Human),
                                channels = emptyList(),
                                directMessages =
                                    listOf(
                                        HomeDirectMessageRow(threadId = ThreadId("dm1"), channelId = ChannelId("dm1"), name = "Ada", unreadCount = 2, unreadCountKnown = true),
                                        HomeDirectMessageRow(threadId = ThreadId("dm2"), channelId = ChannelId("dm2"), name = "Bea"),
                                    ),
                                unreadTotal = 2,
                            ),
                        session = StoredAuthSession(user = StoredAuthUser(id = "u1", email = "u1@raft.test", displayName = "Viewer"), accessToken = "a", refreshToken = "r", selectedServerId = "s1"),
                    )
            )
        assertEquals(2, home.directMessageUnreadCount(serverId = "s1", channelId = "dm1"))
        assertEquals(0, home.directMessageUnreadCount(serverId = "s1", channelId = "dm2"))
        // Home holds another server: nothing is read across servers.
        assertEquals(0, home.directMessageUnreadCount(serverId = "s2", channelId = "dm1"))
    }
}
