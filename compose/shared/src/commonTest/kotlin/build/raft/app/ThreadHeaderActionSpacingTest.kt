package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadHeaderActionSpacingTest {
    @Test
    fun rightHeaderActionsMatchWebMobileVisibleGap() {
        assertEquals(6f, threadHeaderVisibleActionGapDp())
    }
}
