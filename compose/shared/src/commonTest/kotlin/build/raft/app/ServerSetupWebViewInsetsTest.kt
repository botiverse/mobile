package build.raft.app

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Device check 2026-11-01 (Android 12, 3-button navigation): the setup WebView drew its header
 * under the status bar and its Next button under the navigation bar. The web surface pads with
 * `env(safe-area-inset-*)`, which Android WebView leaves at 0, so the Android host pads it.
 */
class ServerSetupWebViewInsetsTest {
    @Test
    fun androidKeepsTheSetupPageBetweenTheSystemBars() {
        assertEquals(24.dp, serverSetupWebViewTopInset(statusBarTop = 24.dp, hostPadsWebView = true))
        assertEquals(126, raftArtifactViewerWebBottomInsetPx(imeBottomPx = 0, navigationBarBottomPx = 126, hostPadsWebView = true))
        // Keyboard up: the IME inset already covers the navigation bar.
        assertEquals(800, raftArtifactViewerWebBottomInsetPx(imeBottomPx = 800, navigationBarBottomPx = 126, hostPadsWebView = true))
    }

    @Test
    fun iosLeavesTheSafeAreaToWkWebView() {
        assertEquals(0.dp, serverSetupWebViewTopInset(statusBarTop = 59.dp, hostPadsWebView = false))
        assertEquals(0, raftArtifactViewerWebBottomInsetPx(imeBottomPx = 300, navigationBarBottomPx = 34, hostPadsWebView = false))
    }
}
