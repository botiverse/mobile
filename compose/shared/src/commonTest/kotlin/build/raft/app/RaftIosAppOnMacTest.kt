package build.raft.app

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.Density
import build.raft.app.compat.ui.raftImeBottomPx
import build.raft.app.components.RaftPullToRefreshStyle
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
        assertTrue(raftIosComposerEnterSendsByDefault(onMac = true, hardwareKeyboard = false))
        assertFalse(raftIosComposerEnterSendsByDefault(onMac = false, hardwareKeyboard = false))
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
}
