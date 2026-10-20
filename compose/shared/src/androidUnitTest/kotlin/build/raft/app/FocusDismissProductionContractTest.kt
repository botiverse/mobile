package build.raft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FocusDismissProductionContractTest {
    @Test
    fun focusOwnerRegistryDoesNotArmOutsideDismissUntilAFieldOwnsFocus() {
        val registry = RaftInputFocusOwnerRegistry()
        val composerOwner = Any()
        val unrelatedOwner = Any()

        assertFalse(registry.hasActiveOwner)
        registry.setFocused(composerOwner, true)
        assertTrue(registry.hasActiveOwner)
        registry.setFocused(composerOwner, true)
        assertTrue(registry.hasActiveOwner, "Repeated focus must not create duplicate ownership")
        registry.setFocused(unrelatedOwner, true)
        registry.setFocused(composerOwner, false)
        assertTrue(registry.hasActiveOwner, "Another active owner must keep the shared host armed")
        registry.setFocused(unrelatedOwner, false)
        assertFalse(registry.hasActiveOwner)
    }

    @Test
    fun focusOwnerRegistryOnlyProtectsDownsInsideAnActiveInput() {
        val registry = RaftInputFocusOwnerRegistry()
        val activeOwner = Any()
        val inactiveOwner = Any()

        registry.setFocused(activeOwner, true) { Rect(100f, 200f, 300f, 400f) }
        assertFalse(registry.shouldClearFocus(Offset(200f, 300f)))
        assertFalse(registry.shouldClearFocus(Offset(100f, 200f)), "Input edges belong to the owner")
        assertTrue(registry.shouldClearFocus(Offset(99f, 300f)))
        assertTrue(registry.shouldClearFocus(Offset(600f, 700f)), "Inactive input bounds must not suppress dismissal")

        registry.setFocused(inactiveOwner, true) { Rect(500f, 600f, 700f, 800f) }
        assertFalse(registry.shouldClearFocus(Offset(600f, 700f)), "A down inside any active owner must preserve focus")
        assertTrue(registry.shouldClearFocus(Offset(900f, 900f)), "A down outside every active owner must dismiss")

        registry.remove(activeOwner)
        assertTrue(registry.hasActiveOwner)
        assertTrue(registry.shouldClearFocus(Offset(200f, 300f)), "Disposed owner bounds must stop shielding that region")

        registry.setFocused(inactiveOwner, false)
        assertFalse(registry.hasActiveOwner)
        assertFalse(registry.shouldClearFocus(Offset(200f, 300f)), "No active owner means there is nothing to dismiss")

        registry.setFocused(activeOwner, true) { null }
        assertFalse(registry.shouldClearFocus(Offset(900f, 900f)), "Missing or detached bounds must fail closed")
    }
}
