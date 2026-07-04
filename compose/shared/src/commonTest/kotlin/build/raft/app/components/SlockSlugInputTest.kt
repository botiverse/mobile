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
    fun disabledPrefixBackgroundKeepsEnabledTokenWithReducedOpacity() {
        assertEquals(SlockSlugInputPrefixBackground.copy(alpha = 0.55f), SlockSlugInputDisabledPrefixBackground)
    }
}
