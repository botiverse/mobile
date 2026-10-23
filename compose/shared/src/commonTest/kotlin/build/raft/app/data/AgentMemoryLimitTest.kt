package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The daemon's memory-limit activity texts read back, as botiverse/slock 24873fd5be37
 * packages/shared/src/agentMemoryLimit.ts (`formatMemoryLimitExitDetail`,
 * `formatBackgroundOomActivityText`) writes them.
 */
class AgentMemoryLimitTest {
    @Test
    fun readsTheExitDetailWithAndWithoutALimit() {
        assertEquals(
            AgentMemoryLimitText("6G"),
            parseAgentMemoryLimitExitDetail("Stopped: used more than this Computer's per-agent memory limit (6G)")
        )
        assertEquals(
            AgentMemoryLimitText(null),
            parseAgentMemoryLimitExitDetail("Stopped: used more than this Computer's per-agent memory limit")
        )
        // Not a size the daemon accepts, or another text: not this notice.
        assertNull(parseAgentMemoryLimitExitDetail("Stopped: used more than this Computer's per-agent memory limit (/home/x)"))
        assertNull(parseAgentMemoryLimitExitDetail("Crashed (exit 137)"))
        assertNull(parseAgentMemoryLimitExitDetail(null))
    }

    @Test
    fun onlyACrashDetailIsAMemoryLimitExit() {
        val text = "Stopped: used more than this Computer's per-agent memory limit (1.5G)"
        assertEquals(AgentMemoryLimitText("1.5G"), agentMemoryLimitExit(text, "runtime_crashed"))
        assertEquals(AgentMemoryLimitText("1.5G"), agentMemoryLimitExit(text, null))
        assertNull(agentMemoryLimitExit(text, "stopped"))
    }

    @Test
    fun readsTheBackgroundNotice() {
        val base = "A background process this agent started was stopped for using more than this Computer's per-agent memory limit"
        assertEquals(AgentMemoryLimitText("50%"), parseAgentBackgroundOomActivityText("$base (50%)."))
        assertEquals(AgentMemoryLimitText(null), parseAgentBackgroundOomActivityText("$base."))
        assertNull(parseAgentBackgroundOomActivityText("$base"))
        assertTrue(isAgentBackgroundOomActivityTitle(" Background process stopped: memory limit "))
        assertFalse(isAgentBackgroundOomActivityTitle("System"))
    }
}
