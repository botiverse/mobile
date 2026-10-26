package build.raft.designsystem

import build.raft.designsystem.components.RaftChoiceListGroup
import build.raft.designsystem.components.RaftChoiceListItem
import build.raft.designsystem.components.raftChoiceListRows
import build.raft.designsystem.components.raftChoiceListSections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** raft-ui ChoiceList `visibleItems` / `layoutSections` (choice-list.tsx, 0.31–0.33). */
class RaftChoiceListTest {
    private val items =
        listOf(
            RaftChoiceListItem("agent:ux", "Product UX Designer", group = "agent"),
            RaftChoiceListItem("agent:cindy", "Cindy", group = "agent"),
            RaftChoiceListItem("human:artin", "artin", group = "human"),
            RaftChoiceListItem("human:designer", "Designer", group = "human"),
            RaftChoiceListItem("x:other", "Other"),
        )

    private fun values(rows: List<RaftChoiceListItem<String>>) = rows.map { it.value }

    @Test
    fun noSearchShowsEveryRowInOrder() {
        val rows = raftChoiceListRows(items, needle = "", pinned = listOf("human:artin"), chosen = listOf("human:artin"), matchedValues = null)
        assertEquals(values(items), values(rows.rows))
        assertFalse(rows.noMatch)
    }

    @Test
    fun pinnedRowsComeFirstThenTheMatches() {
        val rows = raftChoiceListRows(items, needle = "de", pinned = listOf("agent:cindy"), chosen = listOf("agent:cindy"), matchedValues = null)
        assertEquals(listOf("agent:cindy", "agent:ux", "human:designer"), values(rows.rows))
        assertFalse(rows.noMatch)
    }

    @Test
    fun aChosenRowTheSearchMissesStaysInView() {
        // Chosen after the text last changed (not pinned), and not a match: still drawn.
        val rows = raftChoiceListRows(items, needle = "art", pinned = emptyList(), chosen = listOf("agent:cindy"), matchedValues = null)
        assertEquals(listOf("agent:cindy", "human:artin"), values(rows.rows))
    }

    @Test
    fun theCallersMatchesKeepTheirOrderOnce() {
        val rows =
            raftChoiceListRows(
                items,
                needle = "d",
                pinned = emptyList(),
                chosen = emptyList(),
                matchedValues = listOf("human:designer", "agent:cindy", "human:designer"),
            )
        assertEquals(listOf("human:designer", "agent:cindy"), values(rows.rows))
    }

    @Test
    fun noMatchOnlyWhenNothingMatchesAPinnedMatchIsAMatch() {
        assertTrue(raftChoiceListRows(items, "zzz", listOf("human:artin"), listOf("human:artin"), null).noMatch)
        assertFalse(raftChoiceListRows(items, "art", listOf("human:artin"), listOf("human:artin"), null).noMatch)
    }

    @Test
    fun sectionsFollowTheGroupsOrderAndUngroupedRowsGoLast() {
        val groups = listOf(RaftChoiceListGroup("human", "People"), RaftChoiceListGroup("agent", "Agents"))
        val sections = raftChoiceListSections(items, groups)
        assertEquals(listOf("human", "agent", null), sections.map { it.first?.value })
        assertEquals(listOf("human:artin", "human:designer"), values(sections.first().second))
        assertEquals(listOf("x:other"), values(sections.last().second))
        // A group with no rows draws no heading.
        assertEquals(listOf("agent"), raftChoiceListSections(items.take(2), groups).map { it.first?.value })
    }
}
