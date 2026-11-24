package build.raft.designsystem

import build.raft.designsystem.components.RaftRowPlanColumn
import build.raft.designsystem.components.raftPaneGridLeadingCrowdsRow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #337: whether the leading column (the sidebar) at its width leaves the row too little room for the conversation's
 * 360 and the content minimum of every column open right of it. Widths are the leading column with its 1dp seam.
 */
class RaftPaneGridLeadingCrowdsRowTest {
    private val cards = RaftRowPlanColumn("sideCards", listOf("tasks", "files"), listOf(280f, 280f), 360f)
    private val profile = RaftRowPlanColumn("profile", listOf("profile", "details"), listOf(320f, 320f), 400f)
    private val thread = RaftRowPlanColumn("thread", listOf("thread"), listOf(360f), 400f)
    private val artifact = RaftRowPlanColumn("artifact", listOf("artifact"), listOf(480f), 480f)

    @Test
    fun aPortraitIPadsSidebarCrowdsTasksAndFiles() {
        // 820 less the 57 rail: 763. With the 241 sidebar the row has 522, under 360 + 280.
        assertTrue(raftPaneGridLeadingCrowdsRow(763f, 241f, listOf(cards)))
        // The channel details (320) alone too: 522 < 680.
        assertTrue(raftPaneGridLeadingCrowdsRow(763f, 241f, listOf(profile)))
        // Without the sidebar's width, the 763 holds them (what the collapsed sidebar gives the row).
        assertFalse(raftPaneGridLeadingCrowdsRow(763f, 0f, listOf(cards)))
    }

    @Test
    fun aLandscapeIPadKeepsItsSidebar() {
        // 1180 less the rail: 1123; with the sidebar 882, over 640 and 680.
        assertFalse(raftPaneGridLeadingCrowdsRow(1123f, 241f, listOf(cards)))
        assertFalse(raftPaneGridLeadingCrowdsRow(1123f, 241f, listOf(profile)))
    }

    @Test
    fun theStackAsksForItsWidestSectionAndEveryColumnCounts() {
        // Tasks over Files: 280, not 560. 360 + 280 = 640 fits exactly.
        assertFalse(raftPaneGridLeadingCrowdsRow(881f, 241f, listOf(cards)))
        assertTrue(raftPaneGridLeadingCrowdsRow(880f, 241f, listOf(cards)))
        // Cards and the details: 360 + 280 + 320 = 960.
        assertTrue(raftPaneGridLeadingCrowdsRow(1123f, 241f, listOf(cards, profile)))
        // The Artifact asks for its 480.
        assertTrue(raftPaneGridLeadingCrowdsRow(1080f, 241f, listOf(artifact)))
    }

    @Test
    fun theThreadAsksForNothingAsItFoldsBeforeAnythingClips() {
        assertFalse(raftPaneGridLeadingCrowdsRow(763f, 241f, listOf(thread)))
        // A thread stacked with Tasks asks for Tasks' 280 only.
        val stacked = RaftRowPlanColumn("thread", listOf("thread", "tasks"), listOf(360f, 280f), 400f)
        assertTrue(raftPaneGridLeadingCrowdsRow(763f, 241f, listOf(stacked)))
        assertFalse(raftPaneGridLeadingCrowdsRow(1123f, 241f, listOf(stacked)))
    }

    @Test
    fun nothingOpenOrNoRowCrowdsNothing() {
        assertFalse(raftPaneGridLeadingCrowdsRow(500f, 241f, emptyList()))
        assertFalse(raftPaneGridLeadingCrowdsRow(0f, 241f, listOf(cards)))
    }
}
