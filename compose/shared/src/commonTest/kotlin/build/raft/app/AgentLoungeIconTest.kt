package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Lounge's mark is lucide Sofa, as the web's `AgentLoungeIcon` (huxijin via Oscar,
 * #proj-chat msg=5dedad9c: Users read as Members). The iOS native bar draws the generated
 * `lucide-sofa` asset and falls back to the SF Symbol.
 */
class AgentLoungeIconTest {
    @Test
    fun theLoungeMarkIsTheSofa() {
        assertEquals(RaftLucideIconName.Sofa, AgentLoungeIcon)
        assertEquals("sofa", AgentLoungeIcon.wireName)
        assertEquals("sofa", AgentLoungeSystemSymbol)
    }
}
