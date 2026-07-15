package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadComposerActionSpacingTest {
    @Test
    fun leftComposerActionsMatchWebMobileVisibleGap() {
        assertEquals(8f, threadComposerVisibleLeftActionGapDp())
    }
}
