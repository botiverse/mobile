package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadHeaderActionSpacingTest {
    @Test
    fun rightHeaderActionsMatchWebMobileVisibleGap() {
        assertEquals(6f, threadHeaderVisibleActionGapDp())
    }

    @Test
    fun leftHeaderSlotsUseHalfTheCanonicalPageHeaderGap() {
        assertEquals(12f, SlockTopPageHeaderContentSpacing.value)
        assertEquals(SlockTopPageHeaderContentSpacing.value / 2f, threadHeaderContentSpacingDp())
    }

    @Test
    fun channelTitleGetsAQuietlyLargerGap() {
        assertEquals(8f, threadHeaderChannelTitleGapDp())
    }
}
