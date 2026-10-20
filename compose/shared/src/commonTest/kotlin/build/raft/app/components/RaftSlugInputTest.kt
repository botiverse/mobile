package build.raft.app.components

import build.raft.app.RaftColor
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftSlugInputTest {
    @Test
    fun prefixBackgroundMatchesReactSoftSignalToken() {
        assertEquals(RaftColor.BrandYellow.copy(alpha = 0.30f), RaftSlugInputPrefixBackground)
    }

    @Test
    fun disabledPrefixBackgroundMatchesReactControlOpacityRule() {
        // react: tile bg-palette-yellow/30 x control opacity-60, flattened over
        // white = opaque #FFF7DD (group-opacity composite, not a live alpha)
        assertEquals(Color(0xFFFFF7DD), RaftSlugInputDisabledPrefixBackground)
    }
}
