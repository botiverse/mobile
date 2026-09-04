package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task #270: the compact member row's two line boxes and their gap must follow the system
 * font-size scale, because the render layer scales the ink but not a constant lineHeight.
 */
class SlockModalCompactMemberRowTextMetricsTest {
    private fun assertClose(expected: Float, actual: Float) = assertEquals(expected, actual, 0.001f)

    @Test
    fun unscaledFontKeepsTheShippedGeometryExactly() {
        // 1.0x must be pixel-identical to before the change: 18 / 16 / 2.
        val m = slockModalCompactMemberRowTextMetrics(1f)
        assertEquals(18f, m.titleLineHeightSp)
        assertEquals(16f, m.subtitleLineHeightSp)
        assertEquals(2f, m.lineSpacingDp)
    }

    @Test
    fun doubledFontDoublesAllThreeQuantities() {
        // HuaTuo's acceptance: at fontScale 2.0 on a 1.5x device the row (8 + 36 + 4 + 32 + 8 = 88dp)
        // must read 132px, not the unscaled 78px and not a double-scaled 234px.
        val m = slockModalCompactMemberRowTextMetrics(2f)
        assertEquals(36f, m.titleLineHeightSp)
        assertEquals(32f, m.subtitleLineHeightSp)
        assertEquals(4f, m.lineSpacingDp)
        assertEquals(88f, 8f + m.titleLineHeightSp + m.lineSpacingDp + m.subtitleLineHeightSp + 8f)
    }

    @Test
    fun intermediateScaleIsLinear() {
        val m = slockModalCompactMemberRowTextMetrics(1.3f)
        assertClose(23.4f, m.titleLineHeightSp)
        assertClose(20.8f, m.subtitleLineHeightSp)
        assertClose(2.6f, m.lineSpacingDp)
    }

    @Test
    fun unknownScaleFallsBackToUnscaledInsteadOfCollapsing() {
        // The host reports -1 / 0 before the configuration arrives; a 0x row would be invisible.
        assertEquals(slockModalCompactMemberRowTextMetrics(1f), slockModalCompactMemberRowTextMetrics(0f))
        assertEquals(slockModalCompactMemberRowTextMetrics(1f), slockModalCompactMemberRowTextMetrics(-1f))
    }
}
