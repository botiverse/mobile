package build.raft.app.components

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RaftBrutalInputFieldFrameMinHeightTest {
    @Test
    fun singleLineKeepsTheDefaultExactly() {
        // maxLines = 1 must not change the shipping 42dp look, whatever the line height.
        assertEquals(42.dp, raftBrutalInputFieldFrameMinHeight(42.dp, 1, 18.dp, 8.dp, 2.dp))
        assertEquals(42.dp, raftBrutalInputFieldFrameMinHeight(42.dp, 0, 18.dp, 8.dp, 2.dp))
    }

    @Test
    fun twoLinesGrowToFitBothLines() {
        // The create-channel description field: 2 × 18 + 2 × 8 + 2 × 2 = 56dp, not 42dp.
        assertEquals(56.dp, raftBrutalInputFieldFrameMinHeight(42.dp, 2, 18.dp, 8.dp, 2.dp))
        assertEquals(74.dp, raftBrutalInputFieldFrameMinHeight(42.dp, 3, 18.dp, 8.dp, 2.dp))
    }

    @Test
    fun anExplicitLargerMinHeightStillWins() {
        // connected-apps passes fieldMinHeight = 78dp with maxLines = 3 (needs 74): keep 78.
        assertEquals(78.dp, raftBrutalInputFieldFrameMinHeight(78.dp, 3, 18.dp, 8.dp, 2.dp))
    }

    @Test
    fun mutationControl_the42dpDefaultCannotHoldTwoLines() {
        // 42 − 16 − 4 = 22dp of content is less than one and a half 18dp lines; if someone
        // "fixes" this by returning fieldMinHeight for maxLines == 2, this test goes red.
        val content = 42.dp - 8.dp * 2 - 2.dp * 2
        assertTrue(content < 18.dp * 2, "expected the old frame to be too small for two lines")
        assertTrue(raftBrutalInputFieldFrameMinHeight(42.dp, 2, 18.dp, 8.dp, 2.dp) > 42.dp)
    }
}
