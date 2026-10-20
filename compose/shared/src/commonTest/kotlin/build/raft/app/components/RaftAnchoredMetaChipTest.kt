package build.raft.app.components

import kotlin.test.Test
import kotlin.test.assertEquals

class RaftAnchoredMetaChipTest {
    @Test
    fun navigationChipDispatchDoesNotRequireLivePopoverAnchor() {
        assertEquals(
            RaftMetaChipClickDispatch.Plain,
            resolveRaftMetaChipClickDispatch(
                hasPlainClick = true,
                hasAnchoredClick = false
            )
        )
    }

    @Test
    fun positionedMenuChipKeepsAnchorRequiredDispatch() {
        assertEquals(
            RaftMetaChipClickDispatch.Anchored,
            resolveRaftMetaChipClickDispatch(
                hasPlainClick = false,
                hasAnchoredClick = true
            )
        )
    }

    @Test
    fun plainClickTakesPriorityWhenBothDispatchesExist() {
        assertEquals(
            RaftMetaChipClickDispatch.Plain,
            resolveRaftMetaChipClickDispatch(
                hasPlainClick = true,
                hasAnchoredClick = true
            )
        )
    }
}
