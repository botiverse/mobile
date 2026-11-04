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
    private fun agent(
        id: String,
        status: AgentStatus = AgentStatus.Working,
        name: String = id.uppercase(),
        signal: RaftLiveAgentSignal? = null,
    ) = RaftWorkingAgent(agentId = id, name = name, avatarWireValue = "pixel:random:$id", status = status, signal = signal)

    /** A detailed `agent:activity` frame (heartbeats included). */
    private fun frame(seq: Long) = RaftLiveAgentSignal(serverSeq = seq, coarse = false)

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

    /**
     * Web `LIVE_AGENT_ACTIVITY_VISIBLE_MS`: a row from a detailed frame leaves 90 s after the last
     * frame; a heartbeat (a new seq) restarts the window; the registry still holding the old fact
     * does not bring it back, a new frame does (at the end).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aDetailedRowLeavesNinetySecondsAfterItsLastFrame() = runTest {
        val working = MutableStateFlow(listOf(agent("niko", signal = frame(1)), agent("kirby")))
        val seen = mutableListOf<List<String>>()
        val job = launch { liveAgentActivityFlow(working, now = { currentTime }).collect { seen += it.ids() } }
        runCurrent()
        assertEquals(listOf("niko", "kirby"), seen.last())

        // The daemon's 60 s heartbeat.
        advanceTimeBy(60_000)
        working.value = listOf(agent("niko", signal = frame(2)), agent("kirby"))
        runCurrent()
        advanceTimeBy(RaftLiveAgentActivityVisibleMillis - 1)
        runCurrent()
        assertEquals(listOf("niko", "kirby"), seen.last(), "still within 90 s of the heartbeat")

        // No frame since: the safety net drops it, though its fact still says working.
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf("kirby"), seen.last(), "90 s without a frame")
        working.value = listOf(agent("niko", signal = frame(2)), agent("kirby", AgentStatus.Thinking))
        runCurrent()
        assertEquals(listOf("kirby"), seen.last(), "the old fact is no new signal")

        // A new frame is a new start, at the end.
        working.value = listOf(agent("niko", signal = frame(3)), agent("kirby", AgentStatus.Thinking))
        runCurrent()
        assertEquals(listOf("kirby", "niko"), seen.last())
        job.cancel()
    }

    /**
     * No timeout without a heartbeat to expect: a coarse `agent:activity_state` (Web
     * `heldUntilChange`) and a fact that is no frame (a REST projection) stay until they change. A
     * reconnect's carried-over fact (seq dropped) is no signal: the detailed row's window runs on.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun onlyDetailedFramesExpireAndAReconnectDoesNotRestartTheWindow() = runTest {
        val working =
            MutableStateFlow(
                listOf(
                    agent("coarse", signal = RaftLiveAgentSignal(serverSeq = 7, coarse = true)),
                    agent("projected"),
                    agent("detailed", signal = frame(1)),
                )
            )
        var latest = emptyList<RaftLiveAgentActivityItem>()
        val job = launch { liveAgentActivityFlow(working, now = { currentTime }).collect { latest = it } }
        runCurrent()
        advanceTimeBy(50_000)
        // Reconnect: the registry carries the facts over without their seq.
        working.value = working.value.map { it.copy(signal = it.signal?.takeIf { s -> s.coarse }) }
        runCurrent()
        advanceTimeBy(RaftLiveAgentActivityVisibleMillis - 50_000 + 100)
        runCurrent()
        assertEquals(listOf("coarse", "projected"), latest.ids(), "the detailed row left 90 s after its frame")
        advanceTimeBy(10 * 60_000)
        runCurrent()
        assertEquals(listOf("coarse", "projected"), latest.ids())
        job.cancel()
    }
}
