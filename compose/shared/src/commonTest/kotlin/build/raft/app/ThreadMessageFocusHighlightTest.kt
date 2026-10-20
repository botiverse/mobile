package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadMessageFocusHighlightTest {
    @Test
    fun focusedMessageHighlightUsesBlueFocusMarker() {
        assertEquals(
            RaftColor.AccentCyan.copy(alpha = 0.12f),
            threadMessageFocusHighlightColor(1f)
        )
        assertEquals(
            RaftColor.AccentCyan.copy(alpha = 0.06f),
            threadMessageFocusHighlightColor(0.5f)
        )
        assertEquals(
            RaftColor.Canvas,
            threadMessageFocusHighlightColor(0f)
        )
    }

    @Test
    fun focusedMessageHighlightClampsAnimationAlpha() {
        assertEquals(
            RaftColor.AccentCyan.copy(alpha = 0.12f),
            threadMessageFocusHighlightColor(2f)
        )
        assertEquals(
            RaftColor.Canvas,
            threadMessageFocusHighlightColor(-1f)
        )
    }
}
