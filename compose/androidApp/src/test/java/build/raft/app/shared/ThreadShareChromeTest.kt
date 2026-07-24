package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadShareChromeTest {
    @Test
    fun framedImageAddsOnlyTheHeaderAndKeepsAllCapturedContentPixels() {
        assertEquals(96, threadShareChromeHeaderHeight(width = 1_080))
        assertEquals(2_496, threadShareChromeOutputHeight(width = 1_080, contentHeight = 2_400))

        assertEquals(72, threadShareChromeHeaderHeight(width = 320))
        assertEquals(673, threadShareChromeOutputHeight(width = 320, contentHeight = 601))
        assertEquals(86, threadShareChromeHeaderHeight(width = 720))
        assertEquals(18f, threadShareChromeCaptionTextSize(width = 320), 0.01f)
        assertEquals(24.48f, threadShareChromeCaptionTextSize(width = 720), 0.01f)
        assertEquals(26f, threadShareChromeCaptionTextSize(width = 1_080), 0.01f)
    }
}
