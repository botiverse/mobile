package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadHeaderActionSpacingTest {
    @Test
    fun rightHeaderActionsMatchRaftPhonePanelHeadGap() {
        // raft-ui `panelHeadActions` on a phone: `gap-0.5` between 44dp PanelActions.
        assertEquals(2f, threadHeaderVisibleActionGapDp())
    }

    @Test
    fun headerActionsAre44dpPanelActionsWith22dpGlyphs() {
        assertEquals(44f, threadHeaderActionSizeDp())
        assertEquals(22f, threadHeaderActionIconSizeDp())
        // Back and the last trailing action sit 3dp from the edge, so both glyph boxes are 14dp in.
        assertEquals(14f, threadHeaderEdgeInsetDp() + (threadHeaderActionSizeDp() - threadHeaderActionIconSizeDp()) / 2f)
    }

    @Test
    fun leftHeaderSlotsUseHalfTheCanonicalPageHeaderGap() {
        assertEquals(12f, RaftTopPageHeaderContentSpacing.value)
        assertEquals(RaftTopPageHeaderContentSpacing.value / 2f, threadHeaderContentSpacingDp())
    }

    @Test
    fun channelTitleGetsAQuietlyLargerGap() {
        assertEquals(8f, threadHeaderChannelTitleGapDp())
    }
}
