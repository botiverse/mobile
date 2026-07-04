package build.raft.app

import build.raft.app.components.SlockDisabledActionBackground
import build.raft.app.components.SlockDisabledActionContent
import build.raft.app.components.SlockDisabledDestructiveActionBackground
import build.raft.app.components.slockDisabledActionBackgroundFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SlockDisabledActionVisualTest {
    @Test
    fun disabledPrimaryActionUsesComposerStyleEmptySurfaceNotActiveAlpha() {
        assertEquals(SlockDisabledActionBackground, slockDisabledActionBackgroundFor(SlockColor.AccentPink))
        assertFalse(slockDisabledActionBackgroundFor(SlockColor.AccentPink) == SlockColor.AccentPink.copy(alpha = 0.50f))
        assertFalse(slockDisabledActionBackgroundFor(SlockColor.AccentPink) == SlockColor.EmptyStateGray)
        assertEquals(SlockColor.SurfaceWhite, SlockDisabledActionContent)
    }

    @Test
    fun disabledDestructiveActionKeepsDestructiveTintWithoutActiveAlpha() {
        assertEquals(SlockDisabledDestructiveActionBackground, slockDisabledActionBackgroundFor(SlockColor.AccentRed))
        assertFalse(slockDisabledActionBackgroundFor(SlockColor.AccentRed) == SlockColor.AccentRed.copy(alpha = 0.50f))
        assertFalse(slockDisabledActionBackgroundFor(SlockColor.AccentRed) == SlockColor.EmptyStateGray)
    }
}
