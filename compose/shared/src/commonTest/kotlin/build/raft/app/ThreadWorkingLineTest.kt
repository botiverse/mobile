package build.raft.app

import build.raft.app.data.RaftLiveAgentActivityItem
import build.raft.app.model.AgentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The line above a channel's composer (web AgentTypingLine with `workingAgentIdKey`, slock #13551):
 * typing is one kind of working. Faces: the working agents in the order they started, then any typer
 * not reported working. Sentence: "3 agents working here", "Kirby is typing… · 2 others working", or
 * the typing sentence alone when everyone working types.
 */
class ThreadWorkingLineTest {
    private fun working(id: String, startedAt: Long) =
        RaftLiveAgentActivityItem(id, id.replaceFirstChar(Char::uppercase), null, AgentStatus.Working, startedAt = startedAt)

    private fun typer(id: String) = ThreadTypingAgent(id, id.replaceFirstChar(Char::uppercase), "pixel:random:$id")

    private val niko = working("niko", 1)
    private val kirby = working("kirby", 2)
    private val tenny = working("tenny", 3)

    private fun format(line: ThreadWorkingLine, typingSentence: String) =
        formatThreadWorkingLineSentence(
            line,
            typingSentence,
            workingHere = { count -> if (count == 1) "$count agent working here" else "$count agents working here" },
            typingAndOthers = { typing, others -> if (others == 1) "$typing · $others other working" else "$typing · $others others working" },
        )

    @Test fun nobodyTypingCountsEveryoneWorkingHere() {
        val line = threadWorkingLine(typing = emptyList(), working = listOf(niko, kirby, tenny))
        assertEquals(ThreadWorkingLineSentence.WorkingHere(3), line.sentence)
        assertEquals("3 agents working here", format(line, ""))
        assertEquals("1 agent working here", format(threadWorkingLine(emptyList(), listOf(niko)), ""))
        assertEquals(listOf("niko", "kirby", "tenny"), line.faces.map { it.agentId })
        assertTrue(line.typingIds.isEmpty())
    }

    @Test fun aTyperAmongOthersAtWorkSaysTypingThenTheOthers() {
        val line = threadWorkingLine(typing = listOf(typer("kirby")), working = listOf(niko, kirby, tenny))
        assertEquals(ThreadWorkingLineSentence.TypingAndOthers(2), line.sentence)
        assertEquals("Kirby is typing… · 2 others working", format(line, "Kirby is typing…"))
        val one = threadWorkingLine(typing = listOf(typer("kirby")), working = listOf(niko, kirby))
        assertEquals("Kirby is typing… · 1 other working", format(one, "Kirby is typing…"))
    }

    @Test fun everyoneWorkingTypesSoTheTypingSentenceStandsAlone() {
        val line = threadWorkingLine(typing = listOf(typer("niko"), typer("kirby")), working = listOf(niko, kirby))
        assertEquals(ThreadWorkingLineSentence.Typing, line.sentence)
        assertEquals("Niko and Kirby are typing…", format(line, "Niko and Kirby are typing…"))
    }

    @Test fun aTyperNobodyReportsWorkingIsTypingAloneAndComesAfterTheWorkers() {
        // Only typing: the typing sentence.
        val alone = threadWorkingLine(typing = listOf(typer("mo")), working = emptyList())
        assertEquals(ThreadWorkingLineSentence.Typing, alone.sentence)
        assertEquals(listOf("mo"), alone.faces.map { it.agentId })
        assertEquals("pixel:random:mo", alone.faces.single().avatarWireValue)
        // A typer outside the working list: after the workers; the workers are the "others".
        val mixed = threadWorkingLine(typing = listOf(typer("mo")), working = listOf(niko, kirby))
        assertEquals(listOf("niko", "kirby", "mo"), mixed.faces.map { it.agentId })
        assertEquals(ThreadWorkingLineSentence.TypingAndOthers(2), mixed.sentence)
        assertEquals(setOf("mo"), mixed.typingIds)
    }

    @Test fun typingNeverReordersTheFacesAndTheStackShowsThree() {
        val line = threadWorkingLine(typing = listOf(typer("tenny"), typer("volta")), working = listOf(niko, kirby, tenny))
        assertEquals(listOf("niko", "kirby", "tenny", "volta"), line.faces.map { it.agentId })
        assertEquals(listOf("niko", "kirby", "tenny"), line.faces.workingNowFaces().map { it.agentId })
        assertEquals(ThreadWorkingLineSentence.TypingAndOthers(2), line.sentence)
    }

    @Test fun workingHereIsTheChannelsAgentsInTheOrderTheyStarted() {
        val all = listOf(niko, working("outsider", 2), kirby)
        assertEquals(listOf("niko", "kirby"), all.workingAmong(setOf("kirby", "niko")).map { it.agentId })
        assertTrue(all.workingAmong(emptySet()).isEmpty())
    }

    @Test fun onlyAChannelThatIsNoLoungeSaysWhoWorksThere() {
        assertTrue(RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.Channel).showsChannelWorkingLine(isLounge = false))
        assertFalse(RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.Channel).showsChannelWorkingLine(isLounge = true))
        assertFalse(RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.DirectMessage).showsChannelWorkingLine(isLounge = false))
        assertFalse(RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.ReplyThread).showsChannelWorkingLine(isLounge = false))
    }
}
