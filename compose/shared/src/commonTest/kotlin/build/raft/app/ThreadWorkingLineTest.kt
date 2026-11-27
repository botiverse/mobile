package build.raft.app

import build.raft.app.data.RaftLiveAgentActivityItem
import build.raft.app.model.AgentStatus
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.ThreadTypingAgent
import build.raft.app.thread.ThreadWorkingLine
import build.raft.app.thread.ThreadWorkingLineSentence
import build.raft.app.thread.formatThreadWorkingLineSentence
import build.raft.app.thread.showsChannelWorkingLine
import build.raft.app.thread.threadWorkingLine
import build.raft.app.thread.workingAmong
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

    @Test fun workingHereIsTheChannelsAgentsInTheOrderTheyStarted() {
        val all = listOf(niko, working("outsider", 2), kirby)
        assertEquals(listOf("niko", "kirby"), all.workingAmong(setOf("kirby", "niko")).map { it.agentId })
        assertTrue(all.workingAmong(emptySet()).isEmpty())
    }

    // Web renders the working line only in ChatPanel's channel timeline; a task conversation (#proj-mobile:#89:
    // "7 agents working here" counted the whole channel) and a thread opened on the channel surface say only who types.
    @Test fun aTaskConversationOrAThreadOnTheChannelSurfaceSaysOnlyWhoTypes() {
        assertTrue(RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.Channel, channelKind = "public").showsChannelWorkingLine(isLounge = false))
        assertTrue(RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.Channel, channelKind = "joint").showsChannelWorkingLine(isLounge = false))
        for (kind in listOf(RaftTaskConversationChannelKind, "Task", RaftThreadSurfaceKind.ReplyThread, RaftThreadSurfaceKind.DirectMessage)) {
            assertFalse(
                RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.Channel, channelKind = kind).showsChannelWorkingLine(isLounge = false),
                "channelKind=$kind",
            )
        }
    }
}
