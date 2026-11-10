package build.raft.designsystem

import build.raft.designsystem.components.raftLabelsGoAbove
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** raft-ui 0.75 `labelsGoAbove`: a two-column label/value list stacks on its content, not on a breakpoint. */
class RaftLabelValueFitTest {
    // A phone's list: 358 wide, an 88 label column, 16 after it, so a 254 value column.
    private fun goAbove(vararg values: Int, width: Int = 358, labelColumn: Int = 88) =
        raftLabelsGoAbove(width = width, labelColumn = labelColumn, gap = 16, minValueColumn = 160, valueWidths = values.toList())

    @Test
    fun shortValuesStayBesideTheirLabelsOnAPhone() {
        assertFalse(goAbove(90, 120, 254))
    }

    @Test
    fun aValueThatFitsOnlyALineOfItsOwnStacksTheWholeList() {
        assertTrue(goAbove(90, 255))
        assertTrue(goAbove(90, 358))
    }

    @Test
    fun aParagraphTooLongForItsOwnLineDoesNotDecide() {
        assertFalse(goAbove(90, 359))
        assertFalse(goAbove(90, 1200))
    }

    @Test
    fun aValueColumnUnder160Stacks() {
        // 160 + 16 + 160 = 336: at 335 the value column is 159.
        assertFalse(goAbove(40, width = 336, labelColumn = 160))
        assertTrue(goAbove(40, width = 335, labelColumn = 160))
    }

    // A chip row: far too long for one line (doesn't decide), but its widest chip only fits a line of its own.
    private fun chipsGoAbove(widestChip: Int) =
        raftLabelsGoAbove(width = 358, labelColumn = 88, gap = 16, minValueColumn = 160, valueWidths = listOf(90, 900), valueMinWidths = listOf(90, widestChip))

    @Test
    fun aChipWiderThanTheValueColumnStacksTheList() {
        assertFalse(chipsGoAbove(254), "every chip fits beside its label")
        assertTrue(chipsGoAbove(255), "the widest chip only fits a line of its own")
        assertTrue(chipsGoAbove(358))
    }

    @Test
    fun aPieceWiderThanTheWholeListDoesNotDecide() {
        assertFalse(chipsGoAbove(359))
    }
}
