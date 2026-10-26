package com.tencent.kuiklybase.markdown.compose.elements

import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TestFlight crash cluster C (iOS 1.15.5 11505003, Hands 0cf2da4d):
 * `IllegalArgumentException: Can't represent a width of 1216 and height of 262285 in
 * Constraints` from TextMeasurePolicy measuring the inline markdown image placeholder
 * (`![alt](url)`). Each frame the text lays the placeholder out (sp -> px), measures the Image
 * into it, and the Image's height is fed back into the placeholder. Before the fix the feedback
 * went px -> dp -> `dp.value.sp`, so with a font scale of 1.3 the 180 px placeholder was
 * 180 * 1.3^n px after n frames and passed the Constraints limit in about 30 frames.
 */
class MarkdownInlineImagePlaceholderTest {
    @Test
    fun placeholderStaysPutUnderFontScaleWhileTheImageFillsIt() {
        listOf(
            LinearFontScaleDensity(3f, 1.3f), // iOS: sp scale linearly
            LinearFontScaleDensity(3f, 0.85f),
            LinearFontScaleDensity(2f, 3.1f),
            Density(3f, 1.3f), // platform default (non-linear on Android)
        ).forEach { density ->
            val (widthPx, heightPx) = runFrames(density, configuredWidthPx = 1216f, configuredHeightPx = 180f)
            assertTrue(heightPx in 178..181, "height drifted to $heightPx px at $density")
            assertTrue(widthPx in 1215..1216, "width drifted to $widthPx px at $density")
            Constraints(maxWidth = widthPx, maxHeight = heightPx)
        }
    }

    @Test
    fun loadedImageShorterThanThePlaceholderNarrowsIt() {
        val density = LinearFontScaleDensity(3f, 1.3f)
        var heightPx = 180f
        heightPx = nextMarkdownInlineImagePlaceholderHeightPx(heightPx, measuredPx = 96)
        assertEquals(96f, heightPx)
        val laidOut = with(density) { markdownInlineImagePlaceholder(1216f, heightPx, density).height.toPx() }
        assertEquals(96f, laidOut, 0.01f)
    }

    @Test
    fun placeholderSidesAlwaysFitConstraints() {
        val density = LinearFontScaleDensity(3f, 1.3f)
        val placeholder = markdownInlineImagePlaceholder(widthPx = 1216f, heightPx = 262_285f, density = density)
        val widthPx = with(density) { floor(placeholder.width.toPx()).toInt() }
        val heightPx = with(density) { floor(placeholder.height.toPx()).toInt() }
        assertTrue(heightPx <= MarkdownInlineImagePlaceholderMaxPx.toInt())
        Constraints(maxWidth = widthPx, maxHeight = heightPx)
        Constraints(maxWidth = MarkdownInlineImagePlaceholderMaxPx.toInt(), maxHeight = MarkdownInlineImagePlaceholderMaxPx.toInt())
        assertEquals(
            MarkdownInlineImagePlaceholderMaxPx,
            nextMarkdownInlineImagePlaceholderHeightPx(180f, measuredPx = 262_285),
        )
    }

    /** The text's layout / measure / feedback loop, with the Image filling its placeholder. */
    private fun runFrames(density: Density, configuredWidthPx: Float, configuredHeightPx: Float): Pair<Int, Int> {
        var heightPx = configuredHeightPx
        var rect = 0 to 0
        repeat(120) {
            val placeholder = markdownInlineImagePlaceholder(configuredWidthPx, heightPx, density)
            rect =
                with(density) {
                    floor(placeholder.width.toPx()).toInt() to floor(placeholder.height.toPx()).toInt()
                }
            // TextMeasurePolicy measures the inline content with these Constraints.
            Constraints(maxWidth = rect.first, maxHeight = rect.second)
            heightPx = nextMarkdownInlineImagePlaceholderHeightPx(heightPx, measuredPx = rect.second)
        }
        return rect
    }
}

private class LinearFontScaleDensity(override val density: Float, override val fontScale: Float) : Density {
    override fun Dp.toSp(): TextUnit = (value / fontScale).sp

    override fun TextUnit.toDp(): Dp = (value * fontScale).dp

    override fun toString(): String = "LinearFontScaleDensity($density, $fontScale)"
}
