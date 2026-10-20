package build.raft.app.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftHostVisibilityTest {
    @AfterTest
    fun tearDown() {
        clearRaftHostVisibilityForTests()
    }

    @Test
    fun unknownShellDefaultsVisible() {
        assertTrue(isRaftHostVisible("shell-a"))
    }

    @Test
    fun hiddenBeforeThreadCompositionIsReplayable() {
        updateRaftHostVisibility("shell-a", visible = false)

        assertFalse(isRaftHostVisible("shell-a"))
    }

    @Test
    fun rapidHiddenVisibleTransitionReturnsCurrentState() {
        updateRaftHostVisibility("shell-a", visible = false)
        updateRaftHostVisibility("shell-a", visible = true)

        assertTrue(isRaftHostVisible("shell-a"))
    }

    @Test
    fun lateOldShellEventDoesNotAffectCurrentShell() {
        updateRaftHostVisibility("shell-current", visible = true)
        updateRaftHostVisibility("shell-old", visible = false)

        assertTrue(isRaftHostVisible("shell-current"))
        assertFalse(isRaftHostVisible("shell-old"))
    }

    @Test
    fun blankShellUsesLegacyScope() {
        updateRaftHostVisibility("", visible = false)

        assertFalse(isRaftHostVisible(RaftShellStoreScope.Legacy.shellInstanceId))
    }
}
