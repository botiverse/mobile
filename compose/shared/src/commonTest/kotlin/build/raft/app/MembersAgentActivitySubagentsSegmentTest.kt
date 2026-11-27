package build.raft.app

import build.raft.app.data.AgentRuntimesWithSubagents
import build.raft.app.members.membersAgentActivityShowsSubagents
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * slock #14961 (botiverse/slock 979e2a0b1, staging 8600b62c6): shared `RUNTIMES_WITH_SUBAGENTS`
 * and AgentActivityLog `showSubagents = hasSubagents || subagents.length > 0 || view === "subagents"`.
 */
class MembersAgentActivitySubagentsSegmentTest {
    @Test
    fun aRuntimeThatCanStartSubagentsAlwaysShowsTheSegmentWithZero() {
        AgentRuntimesWithSubagents.forEach { runtime ->
            assertTrue(membersAgentActivityShowsSubagents(runtime = runtime, subagentCount = 0, viewing = false), runtime)
        }
    }

    @Test
    fun anyOtherRuntimeShowsItOnceOneHasAppearedOrWhileViewing() {
        listOf("grok", "builtin", "opencode", null).forEach { runtime ->
            assertFalse(membersAgentActivityShowsSubagents(runtime = runtime, subagentCount = 0, viewing = false), "$runtime")
            assertTrue(membersAgentActivityShowsSubagents(runtime = runtime, subagentCount = 1, viewing = false), "$runtime")
            assertTrue(membersAgentActivityShowsSubagents(runtime = runtime, subagentCount = 0, viewing = true), "$runtime")
        }
    }

    @Test
    fun workerV1AlwaysShowsTheWorkersSegmentEvenWithZeroSubagentsOnAnyRuntime() {
        listOf("grok", "builtin", "opencode", "claude", null).forEach { runtime ->
            assertTrue(
                membersAgentActivityShowsSubagents(
                    runtime = runtime,
                    subagentCount = 0,
                    viewing = false,
                    workersEnabled = true,
                ),
                "$runtime",
            )
        }
    }
}
