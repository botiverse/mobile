package build.raft.app.data

import build.raft.app.model.AgentStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Web `liveAgentActivity.ts` behaviour (raft-design people-and-agents/working-agents.md). */
class RaftLiveAgentActivityTest {
    private fun agent(id: String, status: AgentStatus = AgentStatus.Working, name: String = id.uppercase()) =
        RaftWorkingAgent(agentId = id, name = name, avatarWireValue = "pixel:random:$id", status = status)

    private fun List<RaftLiveAgentActivityItem>.ids() = map { it.agentId }

    @Test
    fun agentsAreListedInTheOrderTheyStarted() {
        var items = reduceLiveAgentActivity(emptyList(), listOf(agent("niko")), now = 1_000)
        // The presence registry's order says nothing; a later starter goes to the end.
        items = reduceLiveAgentActivity(items, listOf(agent("kirby"), agent("niko")), now = 2_000)
        items = reduceLiveAgentActivity(items, listOf(agent("tenny"), agent("kirby"), agent("niko", AgentStatus.Thinking)), now = 3_000)
        assertEquals(listOf("niko", "kirby", "tenny"), items.ids())
        assertEquals(1_000, items.first().startedAt)
        // A new state updates the row where it is.
        assertEquals(AgentStatus.Thinking, items.first().status)
    }

    @Test
    fun onlyThinkingAndWorkingCount() {
        val items =
            reduceLiveAgentActivity(
                emptyList(),
                listOf(agent("a", AgentStatus.Online), agent("b", AgentStatus.Error), agent("c", AgentStatus.Thinking)),
                now = 0,
            )
        assertEquals(listOf("c"), items.ids())
    }

    @Test
    fun anIdleAgentStaysForTheGraceAndKeepsItsPlaceWhenItResumes() {
        var items = reduceLiveAgentActivity(emptyList(), listOf(agent("niko"), agent("kirby")), now = 0)
        items = reduceLiveAgentActivity(items, listOf(agent("kirby")), now = 1_000)
        assertEquals(listOf("niko", "kirby"), items.ids())
        assertEquals(1_000L, items.first().idleSince)
        assertEquals(1_000L + RaftLiveAgentIdleGraceMillis, nextLiveAgentActivityExpiry(items))

        // Back to work within the grace: same place, same start.
        items = reduceLiveAgentActivity(items, listOf(agent("niko"), agent("kirby")), now = 3_000)
        assertEquals(listOf("niko", "kirby"), items.ids())
        assertNull(items.first().idleSince)
        assertEquals(0L, items.first().startedAt)
        assertNull(nextLiveAgentActivityExpiry(items))
    }

    @Test
    fun anIdleAgentLeavesWhenTheGraceRunsOut() {
        var items = reduceLiveAgentActivity(emptyList(), listOf(agent("niko"), agent("kirby")), now = 0)
        items = reduceLiveAgentActivity(items, listOf(agent("kirby")), now = 1_000)
        assertEquals(listOf("niko", "kirby"), pruneLiveAgentActivity(items, now = 5_999).ids())
        assertEquals(listOf("kirby"), pruneLiveAgentActivity(items, now = 6_000).ids())
        // A later change after the grace drops it too, and a return is a new start at the end.
        items = reduceLiveAgentActivity(items, listOf(agent("kirby")), now = 7_000)
        assertEquals(listOf("kirby"), items.ids())
        items = reduceLiveAgentActivity(items, listOf(agent("niko"), agent("kirby")), now = 8_000)
        assertEquals(listOf("kirby", "niko"), items.ids())
    }

    @Test
    fun nameAndFaceFollowTheDirectory() {
        var items = reduceLiveAgentActivity(emptyList(), listOf(agent("niko", name = "Niko")), now = 0)
        items = reduceLiveAgentActivity(items, listOf(agent("niko", name = "Niko the Second")), now = 10)
        assertEquals("Niko the Second", items.single().name)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theFlowPrunesAnIdleAgentWhenItsGraceRunsOut() = runTest {
        val working = MutableStateFlow(listOf(agent("niko"), agent("kirby")))
        val seen = mutableListOf<List<String>>()
        val job = launch { liveAgentActivityFlow(working, now = { currentTime }).collect { seen += it.ids() } }
        runCurrent()
        working.value = listOf(agent("kirby"))
        runCurrent()
        // Still listed during the grace.
        assertEquals(listOf("niko", "kirby"), seen.last())
        advanceTimeBy(RaftLiveAgentIdleGraceMillis + 100)
        runCurrent()
        assertEquals(listOf("kirby"), seen.last())
        working.value = emptyList()
        advanceTimeBy(RaftLiveAgentIdleGraceMillis + 100)
        runCurrent()
        assertEquals(emptyList(), seen.last())
        job.cancel()
    }
}
