package build.raft.app.components

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftSlugInputTest {
    @Test
    fun disabledPrefixBackgroundMatchesReactControlOpacityRule() {
        // react: tile bg-palette-yellow/30 x control opacity-60, flattened over
        // white = opaque #FFF7DD (group-opacity composite, not a live alpha)
        assertEquals(Color(0xFFFFF7DD), RaftSlugInputDisabledPrefixBackground)
    }
}
