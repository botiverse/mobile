package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadTransientBackTargetTest {
    @Test
    fun modalKeepsBackOwnershipAboveThreadTransientState() {
        assertNull(
            threadTransientBackTarget(
                activeModal = "forwardTargets",
                shareSelectionActive = true,
                inlineSearchVisible = true
            )
        )
    }

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

    @Test
    fun noTransientStateLeavesPageBackUnhandled() {
        assertNull(
            threadTransientBackTarget(
                activeModal = null,
                shareSelectionActive = false,
                inlineSearchVisible = false
            )
        )
    }
}
