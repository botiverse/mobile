package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtifactCardExcerptWrapLogicTest {
    @Test
    fun exclusionMatchesSixDpInsetAndEightDpStartFourDpBottomMargins() {
        // 173px wide cover (at 1x), 12px excerpt padding -> 149px inner width.
        // 62x22 badge at 6px top/right inset leaves 173 - 6 - 62 - 8 - 12 = 85px beside width
        // and 6 + 22 + 4 - 12 = 20px exclusion height in word coordinates.
        val exclusion =
            computeExcerptCornerExclusion(
                frameWidth = 173,
                padPx = 12,
                cornerInsetPx = 6,
                gapStartPx = 8,
                gapBottomPx = 4,
                minBesideWidthPx = 24,
                cornerWidth = 62,
                cornerHeight = 22,
            )
        assertEquals(85, exclusion.besideWidth)
        assertEquals(20, exclusion.exclusionHeight)
    }

    @Test
    fun exclusionDropsBesideWidthToZeroWhenRemainingSliverIsBelowMinimum() {
        // Wide multi-badge group (139px on a 173px phone cover) leaves only 8px (< 24px floor),
        // so words drop cleanly below the badge group instead of squeezing into a 1-char chimney.
        val exclusion =
            computeExcerptCornerExclusion(
                frameWidth = 173,
                padPx = 12,
                cornerInsetPx = 6,
                gapStartPx = 8,
                gapBottomPx = 4,
                minBesideWidthPx = 24,
                cornerWidth = 139,
                cornerHeight = 22,
            )
        assertEquals(0, exclusion.besideWidth)
        assertEquals(20, exclusion.exclusionHeight)
    }

    @Test
    fun zeroSizedCornerLeavesFullWidthWithZeroExclusion() {
        val exclusion =
            computeExcerptCornerExclusion(
                frameWidth = 173,
                padPx = 12,
                cornerInsetPx = 6,
                gapStartPx = 8,
                gapBottomPx = 4,
                minBesideWidthPx = 24,
                cornerWidth = 0,
                cornerHeight = 0,
            )
        assertEquals(149, exclusion.besideWidth)
        assertEquals(0, exclusion.exclusionHeight)
    }

    @Test
    fun cleanWrapBoundaryAcceptsWholeWordsHyphensAndCjkAndRejectsMidWordCuts() {
        // Whole remaining string fits.
        assertTrue(isCleanExcerptWrapBoundary("Hello world", lineEnd = 11, visibleEnd = 11))
        // Clean break after or before space.
        assertTrue(isCleanExcerptWrapBoundary("Everything we need", lineEnd = 14, visibleEnd = 13))
        assertTrue(isCleanExcerptWrapBoundary("Everything we need", lineEnd = 13, visibleEnd = 13))
        // Clean break at newline.
        assertTrue(isCleanExcerptWrapBoundary("Line one\nLine two", lineEnd = 9, visibleEnd = 8))
        assertTrue(isCleanExcerptWrapBoundary("Line one\nLine two", lineEnd = 8, visibleEnd = 8))
        // Clean hyphen break.
        assertTrue(isCleanExcerptWrapBoundary("cross-platform sync", lineEnd = 6, visibleEnd = 6))
        // Mid-word cut on Latin word or inline code identifier -> rejected so word drops whole below badge.
        assertFalse(isCleanExcerptWrapBoundary("Everything we need", lineEnd = 5, visibleEnd = 5))
        assertFalse(isCleanExcerptWrapBoundary("artifact_badge_wrap", lineEnd = 9, visibleEnd = 9))
        assertFalse(isCleanExcerptWrapBoundary("Everything", lineEnd = 0, visibleEnd = 0))
        // CJK allows breaks between ideographs, but rejects breaking right before closing punctuation.
        assertTrue(isCleanExcerptWrapBoundary("本周决定：摘要封面保留比例", lineEnd = 5, visibleEnd = 5))
        assertFalse(isCleanExcerptWrapBoundary("本周决定：摘要封面", lineEnd = 4, visibleEnd = 4))
    }

    @Test
    fun advancePastWrappedLineNormalizesAndroidAndSkiaNewlinesAndPreservesBlankLines() {
        // Soft wrap: Android includes trailing space, Skia stops before it -> both land on "need".
        assertEquals(14, advancePastExcerptWrappedLine("Everything we need", lineEnd = 14))
        assertEquals(14, advancePastExcerptWrappedLine("Everything we need", lineEnd = 13))
        // Explicit single newline: Android includes '\n', Skia stops before '\n' -> both land on 'N'.
        assertEquals(9, advancePastExcerptWrappedLine("Line one\nNext", lineEnd = 9))
        assertEquals(9, advancePastExcerptWrappedLine("Line one\nNext", lineEnd = 8))
        // Double newline (\n\n): both land on the second '\n' so the blank paragraph gap survives.
        assertEquals(9, advancePastExcerptWrappedLine("Line one\n\nNext", lineEnd = 9))
        assertEquals(9, advancePastExcerptWrappedLine("Line one\n\nNext", lineEnd = 8))
        // Trailing spaces before newline do not create an extra empty line.
        assertEquals(11, advancePastExcerptWrappedLine("Line one  \nNext", lineEnd = 8))
    }
}
