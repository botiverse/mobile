package build.raft.designsystem

import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftMobileNavDefaults
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The phone tab bar over the bottom safe area (raft-ui 0.102.21 mobile-nav.mjs:11-12: the 52px bar,
 * then `pb-[max(8px,min(env(safe-area-inset-bottom,0px),34px))]`).
 */
class RaftMobileNavBarHeightTest {
    @Test
    fun theBarIsAsTallAsTheWebsOverAnIPhonesHomeIndicator() {
        assertEquals(60.dp, RaftMobileNavDefaults.height(0.dp), "nothing under it: the least 8dp padding")
        assertEquals(86.dp, RaftMobileNavDefaults.height(34.dp), "a 34pt home indicator: 52 + 34, as on the web")
    }

    @Test
    fun theTabsClearAndroidsThreeButtonNavigationBar() {
        // The app draws under Android's 48dp button bar, where raft-ui's 34px cap would put the
        // tabs 14dp under its buttons.
        assertEquals(52.dp + 48.dp, RaftMobileNavDefaults.height(48.dp))
    }
}
