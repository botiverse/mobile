package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadComposerActionSpacingTest {
    @Test
    fun embeddedComposerButtonsSitAsFarApartAsFromTheFrameEdge() {
        // raft-ui `ComposerActions` / `ComposerMeta` `gap-embedded-inset` (raft-design #810).
        assertEquals(6f, threadComposerEmbeddedInsetDp())
        assertEquals(threadComposerEmbeddedInsetDp(), threadComposerVisibleLeftActionGapDp())
        assertEquals(threadComposerEmbeddedInsetDp(), threadComposerVisibleRightActionGapDp())
    }

    @Test
    fun composerToolsAndSendUseRaftIconSmButtons() {
        // raft-ui `ComposerIconButton` / `ComposerSubmit size="icon-sm"`: 32dp box, 18dp glyph.
        assertEquals(32f, threadComposerIconButtonSizeDp())
        assertEquals(18f, threadComposerIconSizeDp())
    }

    @Test
    fun composerCardPaddingFollowsRaftUi059() {
        // raft-ui 0.59 `Composer`: px-3 pt-3, pb = embedded inset - 1px; `ComposerInput` px-0.5.
        assertEquals(listOf(12f, 12f, 5f), threadComposerCardPaddingDp())
        assertEquals(2f, threadComposerInputPaddingHorizontalDp())
    }
}
