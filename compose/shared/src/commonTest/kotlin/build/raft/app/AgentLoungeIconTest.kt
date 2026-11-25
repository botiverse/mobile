package build.raft.app

import build.raft.app.data.AgentChannelSummary
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Lounge's mark is lucide Sofa, as the web's `AgentLoungeIcon` (huxijin via Oscar,
 * #proj-chat msg=5dedad9c: Users read as Members; slock #16325 for agent profile channel list).
 * The iOS native bar draws the generated `lucide-sofa` asset and falls back to the SF Symbol.
 */
class AgentLoungeIconTest {
    @Test
    fun theLoungeMarkIsTheSofa() {
        assertEquals(RaftLucideIconName.Sofa, AgentLoungeIcon)
        assertEquals("sofa", AgentLoungeIcon.wireName)
        assertEquals("sofa", AgentLoungeSystemSymbol)
    }

    @Test
    fun agentProfileChannelRowDrawsSofaForLounge() {
        val lounge = AgentChannelSummary(id = "ch-1", name = "Lagoon's Lounge", type = "channel", loungeAgentId = "agent-1")
        val regular = AgentChannelSummary(id = "ch-2", name = "general", type = "channel", loungeAgentId = null)
        val privateCh = AgentChannelSummary(id = "ch-3", name = "ops", type = "private", loungeAgentId = null)
        assertEquals(RaftLucideIconName.Sofa, membersAgentChannelRowIcon(lounge))
        assertEquals(RaftLucideIconName.Hash, membersAgentChannelRowIcon(regular))
        assertEquals(RaftLucideIconName.Lock, membersAgentChannelRowIcon(privateCh))
    }
}
