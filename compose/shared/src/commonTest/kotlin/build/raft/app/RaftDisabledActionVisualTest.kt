package build.raft.app

import build.raft.app.components.RaftDisabledActionBackground
import build.raft.app.components.RaftDisabledActionContent
import build.raft.app.components.RaftDisabledDestructiveActionBackground
import build.raft.app.components.raftDisabledActionBackgroundFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RaftDisabledActionVisualTest {
    @Test
    fun disabledPrimaryActionUsesComposerStyleEmptySurfaceNotActiveAlpha() {
        assertEquals(RaftDisabledActionBackground, raftDisabledActionBackgroundFor(RaftColor.AccentPink))
        assertFalse(raftDisabledActionBackgroundFor(RaftColor.AccentPink) == RaftColor.AccentPink.copy(alpha = 0.50f))
        assertFalse(raftDisabledActionBackgroundFor(RaftColor.AccentPink) == RaftColor.EmptyStateGray)
        assertEquals(RaftColor.SurfaceWhite, RaftDisabledActionContent)
    }

    @Test
    fun disabledDestructiveActionKeepsDestructiveTintWithoutActiveAlpha() {
        assertEquals(RaftDisabledDestructiveActionBackground, raftDisabledActionBackgroundFor(RaftColor.AccentRed))
        assertFalse(raftDisabledActionBackgroundFor(RaftColor.AccentRed) == RaftColor.AccentRed.copy(alpha = 0.50f))
        assertFalse(raftDisabledActionBackgroundFor(RaftColor.AccentRed) == RaftColor.EmptyStateGray)
    }
}
