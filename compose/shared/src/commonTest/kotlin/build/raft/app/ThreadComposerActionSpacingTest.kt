package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadComposerActionSpacingTest {
    @Test
    fun leftComposerActionsMatchWebMobileVisibleGap() {
        assertEquals(8f, threadComposerVisibleLeftActionGapDp())
    }

    @Test
    fun composerToolsAndSendUseRaftIconSmButtons() {
        // raft-ui `ComposerIconButton` / `ComposerSubmit size="icon-sm"`: 32dp box, 18dp glyph.
        assertEquals(32f, threadComposerIconButtonSizeDp())
        assertEquals(18f, threadComposerIconSizeDp())
    }
}
