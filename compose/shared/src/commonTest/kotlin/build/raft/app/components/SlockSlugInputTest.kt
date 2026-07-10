package build.raft.app.components

import build.raft.app.SlockColor
import kotlin.test.Test
import kotlin.test.assertEquals

class SlockSlugInputTest {
    @Test
    fun prefixBackgroundMatchesReactSoftSignalToken() {
        assertEquals(SlockColor.BrandYellow.copy(alpha = 0.30f), SlockSlugInputPrefixBackground)
    }

    @Test
    fun disabledPrefixBackgroundMatchesReactControlOpacityRule() {
        // react: tile bg-soft-signal/30 x control opacity-60 = BrandYellow @ 0.18
        assertEquals(SlockColor.BrandYellow.copy(alpha = 0.18f), SlockSlugInputDisabledPrefixBackground)
    }
}
