package build.raft.app.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlockHostVisibilityTest {
    @AfterTest
    fun tearDown() {
        clearSlockHostVisibilityForTests()
    }

    @Test
    fun unknownShellDefaultsVisible() {
        assertTrue(isSlockHostVisible("shell-a"))
    }

    @Test
    fun hiddenBeforeThreadCompositionIsReplayable() {
        updateSlockHostVisibility("shell-a", visible = false)

        assertFalse(isSlockHostVisible("shell-a"))
    }

    @Test
    fun rapidHiddenVisibleTransitionReturnsCurrentState() {
        updateSlockHostVisibility("shell-a", visible = false)
        updateSlockHostVisibility("shell-a", visible = true)

        assertTrue(isSlockHostVisible("shell-a"))
    }

    @Test
    fun lateOldShellEventDoesNotAffectCurrentShell() {
        updateSlockHostVisibility("shell-current", visible = true)
        updateSlockHostVisibility("shell-old", visible = false)

        assertTrue(isSlockHostVisible("shell-current"))
        assertFalse(isSlockHostVisible("shell-old"))
    }

    @Test
    fun blankShellUsesLegacyScope() {
        updateSlockHostVisibility("", visible = false)

        assertFalse(isSlockHostVisible(SlockShellStoreScope.Legacy.shellInstanceId))
    }
}
