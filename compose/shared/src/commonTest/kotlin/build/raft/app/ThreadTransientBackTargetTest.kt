package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadTransientBackTargetTest {
    @Test
    fun shareSelectionConsumesBackBeforeInlineSearch() {
        assertEquals(
            ThreadTransientBackTarget.ShareSelection,
            threadTransientBackTarget(
                activeModal = null,
                shareSelectionActive = true,
                inlineSearchVisible = true
            )
        )
    }

    @Test
    fun inlineSearchConsumesBackWhenSelectionIsInactive() {
        assertEquals(
            ThreadTransientBackTarget.InlineSearch,
            threadTransientBackTarget(
                activeModal = null,
                shareSelectionActive = false,
                inlineSearchVisible = true
            )
        )
    }
}
