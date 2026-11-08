package build.raft.designsystem

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import build.raft.designsystem.components.raftTooltipBelowStartPosition
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftMessageReactionTooltipPlacementTest {
    private val window = IntSize(400, 800)
    private val popup = IntSize(120, 40)
    private val chip = IntRect(left = 20, top = 300, right = 80, bottom = 328)

    @Test
    fun hoverOpensBelowTheChip() {
        assertEquals(IntOffset(20, 332), raftTooltipBelowStartPosition(chip, window, popup, spacing = 4))
    }

    @Test
    fun longPressOpensAboveTheChipOutFromUnderTheFinger() {
        assertEquals(IntOffset(20, 256), raftTooltipBelowStartPosition(chip, window, popup, spacing = 4, aboveFirst = true))
    }

    @Test
    fun longPressNearTheTopFallsBackBelow() {
        val nearTop = IntRect(left = 20, top = 10, right = 80, bottom = 38)
        assertEquals(IntOffset(20, 42), raftTooltipBelowStartPosition(nearTop, window, popup, spacing = 4, aboveFirst = true))
    }
}
