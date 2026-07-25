package build.raft.app.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun loadingChipDisablesPlainAndAnchoredClickDispatch() {
        assertEquals(
            SlockMetaChipClickDispatch.Disabled,
            resolveSlockMetaChipClickDispatch(
                hasPlainClick = true,
                hasAnchoredClick = true,
                loading = true
            )
        )
        assertFalse(slockChipInteractionEnabled(enabled = true, loading = true))
        assertTrue(slockChipInteractionEnabled(enabled = true, loading = false))
        assertFalse(slockChipInteractionEnabled(enabled = false, loading = false))
    }
}
