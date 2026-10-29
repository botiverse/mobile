package build.raft.designsystem

import build.raft.designsystem.components.RaftSideAction
import build.raft.designsystem.components.RaftSideActionHost
import build.raft.designsystem.components.RaftSideActionHostColumn
import build.raft.designsystem.components.RaftSideActionHostPresenter
import build.raft.designsystem.components.raftSideActionClick
import build.raft.designsystem.components.raftSideActionHostColumn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The side action column's hand-off to a host that draws it with system bar buttons. */
class RaftSideActionHostTest {
    private fun action(
        id: String,
        symbol: String? = "circle",
        priority: Int = 0,
        keepVisible: Boolean = false,
        enabled: Boolean = true,
        attention: Boolean = false,
        onClick: () -> Unit = {},
    ) = RaftSideAction(
        id = id,
        label = id.replaceFirstChar { it.uppercase() },
        onClick = onClick,
        enabled = enabled,
        attention = attention,
        attentionDescription = if (attention) "new" else null,
        keepVisible = keepVisible,
        priority = priority,
        systemSymbol = symbol,
        icon = { _ -> },
    )

    private val back = action("back", "chevron.backward", keepVisible = true)

    @Test
    fun everySymbolPresentHandsOff() {
        val column =
            raftSideActionHostColumn(
                primary = listOf(back),
                actions = listOf(action("search", "magnifyingglass"), action("settings", "gearshape", attention = true)),
                overflowLabel = "More Actions",
            )
        assertEquals(
            RaftSideActionHostColumn(
                primary = listOf(RaftSideActionHostColumn.Item("back", "Back", "chevron.backward", true, false, null)),
                actions =
                    listOf(
                        RaftSideActionHostColumn.Item("search", "Search", "magnifyingglass", true, false, null),
                        RaftSideActionHostColumn.Item("settings", "Settings", "gearshape", true, true, "new"),
                    ),
                visibleActionIds = listOf("search", "settings"),
                overflowActionIds = emptyList(),
                overflowLabel = "More Actions",
            ),
            column,
        )
    }

    @Test
    fun oneMissingSymbolKeepsTheWholeColumnOurs() {
        val actions = listOf(action("search", "magnifyingglass"), action("focus", symbol = null))
        assertNull(raftSideActionHostColumn(listOf(back), actions, "More"))
        assertNull(raftSideActionHostColumn(listOf(back.copy(systemSymbol = " ")), emptyList(), "More"), "blank is missing")
        assertNull(raftSideActionHostColumn(listOf(back.copy(systemSymbol = null)), listOf(action("search")), "More"))
        assertNotNull(raftSideActionHostColumn(emptyList(), emptyList(), "More"), "an empty column hands off too")
    }

    @Test
    fun theCapAndPrioritiesSplitButtonsFromTheOverflowInListedOrder() {
        val actions =
            listOf(
                action("a", priority = -1),
                action("b"),
                action("c", priority = 5),
                action("d", keepVisible = true),
                action("e"),
            )
        val pushed = raftSideActionHostColumn(listOf(back), actions, "More")!!
        assertEquals(listOf("a", "b", "c", "d", "e"), pushed.actions.map { it.id }, "every action, listed order")
        assertEquals(listOf("c", "d"), pushed.visibleActionIds)
        assertEquals(listOf("a", "b", "e"), pushed.overflowActionIds)
        // A top-level page (no Back) follows the same cap.
        val topLevel = raftSideActionHostColumn(emptyList(), actions.take(3), "More")!!
        assertEquals(listOf("b", "c"), topLevel.visibleActionIds)
        assertEquals(listOf("a"), topLevel.overflowActionIds)
        // Within the cap nothing overflows (no ⋯).
        val two = raftSideActionHostColumn(listOf(back), actions.take(2), "More")!!
        assertEquals(listOf("a", "b"), two.visibleActionIds)
        assertEquals(emptyList(), two.overflowActionIds)
        val one = raftSideActionHostColumn(emptyList(), listOf(action("bell", "bell")), "More")!!
        assertEquals(listOf("bell"), one.visibleActionIds)
    }

    private class RecordingHost(var accepts: Boolean = true) : RaftSideActionHost {
        val presented = mutableListOf<RaftSideActionHostColumn>()
        var clears = 0
        var onAction: ((String) -> Unit)? = null

        override fun present(column: RaftSideActionHostColumn, onAction: (id: String) -> Unit): Boolean {
            presented += column
            this.onAction = onAction
            return accepts
        }

        override fun clear() {
            clears++
        }
    }

    @Test
    fun equalContentIsPresentedOnceWhateverItsLambdas() {
        val host = RecordingHost()
        val presenter = RaftSideActionHostPresenter(host) {}
        fun column(onClick: () -> Unit) = raftSideActionHostColumn(listOf(back), listOf(action("search", onClick = onClick)), "More")
        presenter.update(column {})
        presenter.update(column { println() })
        presenter.update(column {})
        assertEquals(1, host.presented.size, "a recomposition with fresh lambdas is not a change")
        assertTrue(presenter.handsOff(column {}))
        presenter.update(raftSideActionHostColumn(listOf(back), listOf(action("search", enabled = false)), "More"))
        assertEquals(2, host.presented.size, "a changed field is")
        assertEquals(0, host.clears)
    }

    @Test
    fun stoppingTheHandOffOrLeavingClearsTheHostOnce() {
        val host = RecordingHost()
        val presenter = RaftSideActionHostPresenter(host) {}
        val column = raftSideActionHostColumn(listOf(back), emptyList(), "More")
        presenter.update(column)
        presenter.update(null)
        presenter.update(null)
        assertEquals(1, host.clears)
        presenter.update(column)
        assertEquals(2, host.presented.size, "handed off again after a clear")
        presenter.onForgotten()
        presenter.onAbandoned()
        assertEquals(2, host.clears)
        RaftSideActionHostPresenter(host) {}.onForgotten()
        assertEquals(2, host.clears, "nothing presented, nothing to clear")
    }

    @Test
    fun aDeclinedColumnIsDrawnByUsUntilItChanges() {
        val host = RecordingHost(accepts = false)
        val presenter = RaftSideActionHostPresenter(host) {}
        val column = raftSideActionHostColumn(listOf(back), emptyList(), "More")
        assertTrue(presenter.handsOff(column), "optimistic until the host answers")
        presenter.update(column)
        assertFalse(presenter.handsOff(column))
        presenter.update(column)
        assertEquals(1, host.presented.size, "not asked again for the same content")
        host.accepts = true
        val changed = raftSideActionHostColumn(listOf(back), listOf(action("search")), "More")
        presenter.update(changed)
        assertTrue(presenter.handsOff(changed))
        assertFalse(presenter.handsOff(null))
    }

    @Test
    fun aTapRunsTheMatchingEnabledAction() {
        var ran = ""
        val actions =
            listOf(
                action("search") { ran += "search" },
                action("settings", enabled = false) { ran += "settings" },
            )
        val host = RecordingHost()
        val presenter = RaftSideActionHostPresenter(host) { id -> actions.raftSideActionClick(id)?.invoke() }
        presenter.update(raftSideActionHostColumn(emptyList(), actions, "More"))
        host.onAction!!("search")
        host.onAction!!("settings")
        host.onAction!!("unknown")
        assertEquals("search", ran)
    }
}
