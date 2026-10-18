package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadMessageFocusHighlightTest {
    @Test
    fun focusedMessageHighlightUsesBlueFocusMarker() {
        assertEquals(
            SlockColor.AccentCyan.copy(alpha = 0.12f),
            threadMessageFocusHighlightColor(1f)
        )
        assertEquals(
            SlockColor.AccentCyan.copy(alpha = 0.06f),
            threadMessageFocusHighlightColor(0.5f)
        )
        assertEquals(
            SlockColor.Panel,
            threadMessageFocusHighlightColor(0f)
        )
    }

    @Test
    fun focusedMessageHighlightClampsAnimationAlpha() {
        assertEquals(
            SlockColor.AccentCyan.copy(alpha = 0.12f),
            threadMessageFocusHighlightColor(2f)
        )
        assertEquals(
            SlockColor.Panel,
            threadMessageFocusHighlightColor(-1f)
        )
    }
}
