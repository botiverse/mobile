package build.raft.app.assistant

import build.raft.app.data.MyAssistantRecord
import build.raft.app.data.MyAssistantState
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
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
    fun pausedComesFromTheRostersAgentAndOutranksEverything() {
        fun agent(id: String, pausedAt: String?) =
            MemberProfile(id = MemberId(id), displayName = id, handle = id, kind = MemberKind.Agent, assistantPausedAt = pausedAt)
        val roster =
            SharedServerMembersEntry(
                serverId = "s1",
                members = listOf(agent("a1", "2026-10-30T09:12:44.512Z"), agent("a2", null)),
            )
        assertTrue(roster.assistantPaused("a1"))
        assertFalse(roster.assistantPaused("a2"))
        assertFalse(roster.assistantPaused("unknown"), "not in the roster yet: not paused")
        // Web otterMood: a pause is away, over unread and working.
        assertEquals(RaftOtterMood.Away, assistantOtterMood(AgentStatus.Working, dmUnreadCount = 2, paused = true))
    }
}
