package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadMessageFocusHighlightTest {
    @Test
    fun focusedMessageHighlightUsesVisibleYellowSearchMarker() {
        assertEquals(
            SlockColor.BrandYellow.copy(alpha = 0.4f),
            threadMessageFocusHighlightColor(1f)
        )
        assertEquals(
            SlockColor.BrandYellow.copy(alpha = 0.2f),
            threadMessageFocusHighlightColor(0.5f)
        )
        assertEquals(
            SlockColor.SurfaceWhite,
            threadMessageFocusHighlightColor(0f)
        )
    }

    @Test
    fun focusedMessageHighlightClampsAnimationAlpha() {
        assertEquals(
            SlockColor.BrandYellow.copy(alpha = 0.4f),
            threadMessageFocusHighlightColor(2f)
        )
        assertEquals(
            SlockColor.SurfaceWhite,
            threadMessageFocusHighlightColor(-1f)
        )
    }
}
