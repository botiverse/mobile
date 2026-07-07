package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlockKuiklyPageRenderViewTest {

    @Test
    fun childRootSizeChangeIsIgnoredUntilChildIsAttached() {
        assertFalse(
            shouldNotifySlockKuiklyChildRootSizeChange(
                childAttached = false,
                previousWidthPx = null,
                previousHeightPx = null,
                nextWidthPx = 1080,
                nextHeightPx = 2215
            )
        )
    }

    @Test
    fun childRootSizeChangeRequiresPositiveSize() {
        assertFalse(
            shouldNotifySlockKuiklyChildRootSizeChange(
                childAttached = true,
                previousWidthPx = 1080,
                previousHeightPx = 2215,
                nextWidthPx = 1080,
                nextHeightPx = 0
            )
        )
    }

    @Test
    fun childRootSizeChangeIsDeduplicated() {
        assertFalse(
            shouldNotifySlockKuiklyChildRootSizeChange(
                childAttached = true,
                previousWidthPx = 1080,
                previousHeightPx = 2215,
                nextWidthPx = 1080,
                nextHeightPx = 2215
            )
        )
    }

    @Test
    fun attachedChildRootSizeChangeNotifiesWhenHostSizeDiffers() {
        assertTrue(
            shouldNotifySlockKuiklyChildRootSizeChange(
                childAttached = true,
                previousWidthPx = 1080,
                previousHeightPx = 2215,
                nextWidthPx = 1080,
                nextHeightPx = 2460
            )
        )
    }

    @Test
    fun rootViewSizeEventDataUsesDpValues() {
        assertEquals(
            mapOf("width" to 360f, "height" to 820f),
            slockKuiklyRootViewSizeDidChangedData(
                widthPx = 1080,
                heightPx = 2460,
                density = 3f
            )
        )
    }
}
