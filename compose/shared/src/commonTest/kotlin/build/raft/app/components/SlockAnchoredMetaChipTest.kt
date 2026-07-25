package build.raft.app.components

import kotlin.test.Test
import kotlin.test.assertEquals

class SlockAnchoredMetaChipTest {
    @Test
    fun navigationChipDispatchDoesNotRequireLivePopoverAnchor() {
        assertEquals(
            SlockMetaChipClickDispatch.Plain,
            resolveSlockMetaChipClickDispatch(
                hasPlainClick = true,
                hasAnchoredClick = false
            )
        )
    }

    @Test
    fun positionedMenuChipKeepsAnchorRequiredDispatch() {
        assertEquals(
            SlockMetaChipClickDispatch.Anchored,
            resolveSlockMetaChipClickDispatch(
                hasPlainClick = false,
                hasAnchoredClick = true
            )
        )
    }
}
