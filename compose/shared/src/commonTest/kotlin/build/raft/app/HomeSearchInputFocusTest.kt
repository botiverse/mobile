package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeSearchInputFocusTest {
    @Test
    fun leavingTheSearchTabReleasesAFocusedField() {
        assertTrue(homeSearchInputReleasesOnInactive(inputActive = false, fieldFocused = true))
        assertFalse(homeSearchInputReleasesOnInactive(inputActive = false, fieldFocused = false))
        assertFalse(homeSearchInputReleasesOnInactive(inputActive = true, fieldFocused = true))
    }

    @Test
    fun dismissingTheImeClearsTheFieldFocus() {
        assertTrue(homeSearchInputReleasesOnImeHidden(imeWasVisible = true, imeVisible = false, fieldFocused = true))
        // The keyboard opening, or closing for another field, leaves the search field alone.
        assertFalse(homeSearchInputReleasesOnImeHidden(imeWasVisible = false, imeVisible = true, fieldFocused = true))
        assertFalse(homeSearchInputReleasesOnImeHidden(imeWasVisible = true, imeVisible = false, fieldFocused = false))
        assertFalse(homeSearchInputReleasesOnImeHidden(imeWasVisible = false, imeVisible = false, fieldFocused = true))
    }
}
