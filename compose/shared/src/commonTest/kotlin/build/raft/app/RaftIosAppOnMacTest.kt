package build.raft.app

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.Density
import build.raft.app.compat.ui.raftImeBottomPx
import build.raft.app.components.RaftPullToRefreshStyle
import build.raft.designsystem.theme.RaftScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The iPad app on a Mac takes the desktop app's values; iPad and iPhone keep the iOS ones. */
class RaftIosAppOnMacTest {
    @Test
    fun aMacHasNoPullToRefresh() {
        assertEquals(RaftPullToRefreshStyle.None, raftIosPullToRefreshStyle(onMac = true))
        assertEquals(RaftPullToRefreshStyle.Cupertino, raftIosPullToRefreshStyle(onMac = false))
    }

    @Test
    fun enterSendsByDefaultOnAMac() {
        assertTrue(raftIosComposerEnterSendsByDefault(onMac = true))
        assertFalse(raftIosComposerEnterSendsByDefault(onMac = false))
    }

    @Test
    fun aMacIgnoresAHiddenStatusBar() {
        assertFalse(raftHostStatusBarHidden(reported = true, onMac = true))
        assertFalse(raftHostStatusBarHidden(reported = false, onMac = true))
        assertTrue(raftHostStatusBarHidden(reported = true, onMac = false))
        assertFalse(raftHostStatusBarHidden(reported = false, onMac = false))
    }

    @Test
    fun aMacKeyboardFrameDoesNotLiftTheComposer() {
        val reported = WindowInsets(bottom = 140)
        assertEquals(0, raftImeBottomPx(reported, raftIosAppOnMacImeInsets(), Density(2f)))
    }

    @Test
    fun aMacPageUsesMacPoints() {
        val ipad = Density(2f, fontScale = 1.1f)
        assertSame(ipad, raftIosAppOnMacDensity(ipad, onMac = false))
        val mac = raftIosAppOnMacDensity(ipad, onMac = true)
        assertEquals(2f / 0.77f, mac.density, 1e-4f)
        assertEquals(1.1f, mac.fontScale)
    }

    @Test
    fun theWideBreakpointFallsAt744MacPoints() {
        // A window 744 Mac points wide is 744 / 0.77 = 966.2 iPad points (1932.5 px at 2x).
        val mac = raftIosAppOnMacDensity(Density(2f), onMac = true)
        assertEquals(RaftScale.Wide, RaftScale.of(with(mac) { 1933f.toDp() }))
        assertEquals(RaftScale.Compact, RaftScale.of(with(mac) { 1930f.toDp() }))
    }
}
