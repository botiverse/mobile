package build.raft.app.compat.pager

import androidx.lifecycle.Lifecycle
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * artin (Compose 版, wide layout): switching to another app blanked the right detail pane and
 * re-ran the "resume" refresh on focus regain. Compose Desktop puts an unfocused visible window at
 * STARTED (focused RESUMED, minimized CREATED), and the pager host treated anything below RESUMED
 * as the page disappearing. Desktop now shows pages from STARTED; phones keep RESUMED.
 */
class RaftPagerHostLifecycleVisibleTest {
    private val desktop = Lifecycle.State.STARTED
    private val phone = Lifecycle.State.RESUMED

    @Test
    fun desktopUnfocusedWindowKeepsPagesShown() {
        // Focused, then the user clicks another app: still shown, so no disappear / reappear.
        assertTrue(raftPagerHostLifecycleVisible(Lifecycle.State.RESUMED, visibleFrom = desktop))
        assertTrue(raftPagerHostLifecycleVisible(Lifecycle.State.STARTED, visibleFrom = desktop))
    }

    @Test
    fun desktopMinimizedOrHiddenWindowIsBackground() {
        // Minimized (and Cmd-H, which the desktop host maps to CREATED) is the background.
        assertFalse(raftPagerHostLifecycleVisible(Lifecycle.State.CREATED, visibleFrom = desktop))
        assertFalse(raftPagerHostLifecycleVisible(Lifecycle.State.DESTROYED, visibleFrom = desktop))
    }

    @Test
    fun phonesStillDisappearOnPause() {
        assertTrue(raftPagerHostLifecycleVisible(Lifecycle.State.RESUMED, visibleFrom = phone))
        assertFalse(raftPagerHostLifecycleVisible(Lifecycle.State.STARTED, visibleFrom = phone))
        assertFalse(raftPagerHostLifecycleVisible(Lifecycle.State.CREATED, visibleFrom = phone))
    }

    @Test
    fun androidUnitTestHostUsesPhoneThreshold() {
        // The default comes from the platform; the shared unit tests run on the Android actual.
        assertFalse(raftPagerHostLifecycleVisible(Lifecycle.State.STARTED))
        assertTrue(raftPagerHostLifecycleVisible(Lifecycle.State.RESUMED))
    }
}
