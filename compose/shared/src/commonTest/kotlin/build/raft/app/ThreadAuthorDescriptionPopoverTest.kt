package build.raft.app

import com.tencent.kuikly.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThreadAuthorDescriptionPopoverTest {
    @Test
    fun emptyDescriptionReturnsMinimumDimensions() {
        val dimensions = threadAuthorDescriptionPopoverDimensions("")
        assertEquals(ThreadAuthorDescriptionPopoverMinWidthDp.dp, dimensions.width)
        assertEquals(36.dp, dimensions.height)
    }

    @Test
    fun blankDescriptionReturnsMinimumDimensions() {
        val dimensions = threadAuthorDescriptionPopoverDimensions("   \n\t  ")
        assertEquals(ThreadAuthorDescriptionPopoverMinWidthDp.dp, dimensions.width)
        assertEquals(36.dp, dimensions.height)
    }

    @Test
    fun shortSingleLineDescriptionComputesCompactWidth() {
        val shortText = "Assistant"
        val dimensions = threadAuthorDescriptionPopoverDimensions(shortText)
        assertTrue(dimensions.width < ThreadAuthorDescriptionPopoverMaxWidthDp.dp + SlockShadow.BrutalSmall)
        assertTrue(dimensions.width >= ThreadAuthorDescriptionPopoverMinWidthDp.dp)
        assertTrue(dimensions.height < 60.dp)
    }

    @Test
    fun multiLineDescriptionUsesMaxWidthAndIncreasesHeight() {
        val multiLineText =
            "This is a longer agent description that explains all responsibilities and capabilities in detail across multiple lines."
        val dimensions = threadAuthorDescriptionPopoverDimensions(multiLineText)
        assertEquals(ThreadAuthorDescriptionPopoverMaxWidthDp.dp + SlockShadow.BrutalSmall, dimensions.width)
        assertTrue(dimensions.height > 40.dp)
    }

    @Test
    fun extremelyLongDescriptionClampsHeightAtMaximum() {
        val hugeText = "A".repeat(2000)
        val dimensions = threadAuthorDescriptionPopoverDimensions(hugeText)
        assertEquals(ThreadAuthorDescriptionPopoverMaxWidthDp.dp + SlockShadow.BrutalSmall, dimensions.width)
        assertEquals(320.dp, dimensions.height)
    }

    @Test
    fun customMaxWidthIsRespected() {
        val customMaxWidth = 200.dp
        val text = "A".repeat(100)
        val dimensions = threadAuthorDescriptionPopoverDimensions(text, maxWidth = customMaxWidth)
        assertEquals(customMaxWidth + SlockShadow.BrutalSmall, dimensions.width)
    }
}
