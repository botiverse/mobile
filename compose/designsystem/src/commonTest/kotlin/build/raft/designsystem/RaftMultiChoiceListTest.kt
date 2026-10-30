package build.raft.designsystem

import build.raft.designsystem.components.RaftChoiceListEntry
import build.raft.designsystem.components.RaftChoiceListGroup
import build.raft.designsystem.components.RaftChoiceListItem
import build.raft.designsystem.components.raftMultiChoiceListEntries
import kotlin.test.Test
import kotlin.test.assertEquals

/** raft-ui ChoiceList `multiple` + `groups` ordering (choice-list.md, Behavior). */
class RaftMultiChoiceListTest {
    private val groups = listOf(RaftChoiceListGroup("agent", "Agents"), RaftChoiceListGroup("human", "Humans"))
    private val items =
        listOf(
            RaftChoiceListItem("human:artin", "artin", group = "human"),
            RaftChoiceListItem("agent:cindy", "Cindy", group = "agent"),
            RaftChoiceListItem("human:designer", "Designer", group = "human"),
            RaftChoiceListItem("agent:ux", "Product UX Designer", group = "agent"),
            RaftChoiceListItem("other", "Loose"),
        )

    private fun render(entries: List<RaftChoiceListEntry<String>>): List<String> =
        entries.map {
            when (it) {
                is RaftChoiceListEntry.Heading -> "#${it.group.label}"
                is RaftChoiceListEntry.Row -> it.item.value
            }
        }

    @Test
    fun rowsComeUnderTheirHeadingsInTheGroupsOrderAndUngroupedLast() {
        assertEquals(
            listOf("#Agents", "agent:cindy", "agent:ux", "#Humans", "human:artin", "human:designer", "other"),
            render(raftMultiChoiceListEntries(items, pinned = emptySet(), needle = "", matchedValues = null, groups = groups)),
        )
    }

    @Test
    fun searchKeepsTheChosenRowsFirstInTheirGroupAndDropsEmptyHeadings() {
        // "designer" matches the UX agent and the Designer human; artin is chosen and stays.
        assertEquals(
            listOf("#Agents", "agent:ux", "#Humans", "human:artin", "human:designer"),
            render(raftMultiChoiceListEntries(items, pinned = setOf("human:artin"), needle = "designer", matchedValues = null, groups = groups)),
        )
        // Nothing matches: only the chosen row is left, and the Agents heading goes.
        assertEquals(
            listOf("#Humans", "human:artin"),
            render(raftMultiChoiceListEntries(items, pinned = setOf("human:artin"), needle = "zzz", matchedValues = null, groups = groups)),
        )
    }

    @Test
    fun callerMatchesReplaceTheOwnMatchInTheirOrder() {
        assertEquals(
            listOf("#Humans", "human:designer", "human:artin"),
            render(
                raftMultiChoiceListEntries(
                    items,
                    pinned = emptySet(),
                    needle = "x",
                    matchedValues = listOf("human:designer", "human:artin"),
                    groups = groups,
                )
            ),
        )
    }
}
