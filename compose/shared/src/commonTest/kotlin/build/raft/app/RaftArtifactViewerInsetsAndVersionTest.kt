package build.raft.app

import build.raft.webcontainer.WebContainerAndroidInsetMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Device check 2026-10-29 (Android 12, 3-button navigation): F2 keyboard, F3 navigation bar, F4 header version. */
class RaftArtifactViewerInsetsAndVersionTest {
    private val origin = "https://raft-app-staging.botiverse.dev"
    private val shortId = "DxmdF3qjNnubL1EmCq7wdg"

    private fun stayAt(url: String) = raftArtifactViewerNavigation(url, origin, shortId) as RaftArtifactViewerNavigation.Stay

    @Test
    fun theHeaderFollowsTheVersionInThePageAddress() {
        // Opened from a card on v1; the page loads at the latest (no version in the address).
        assertEquals(1, raftArtifactViewerShownVersion(addressed = null, latestVersion = null, headerVersion = 1))
        val latest = stayAt("$origin/artifacts/$shortId?embed=raft-settings-v1&shell=host")
        assertEquals(1, raftArtifactViewerShownVersion(latest, latestVersion = 1, headerVersion = 1))
        // A version picked in the page's ⋯: the address says /v/<n>.
        val picked = stayAt("$origin/artifacts/$shortId/v/3?embed=raft-settings-v1&shell=host")
        assertEquals(3, raftArtifactViewerShownVersion(picked, latestVersion = 4, headerVersion = 1))
        // Back on the latest after it was read again (v4 published while open).
        assertEquals(4, raftArtifactViewerShownVersion(latest, latestVersion = 4, headerVersion = 1))
    }

    @Test
    fun aPinnedVersionWinsAndPickingOneDropsShown() {
        val embed = "embed=raft-settings-v1&shell=host"
        // Pinned: the address version, whatever else is there.
        val pinned = stayAt("$origin/artifacts/$shortId/v/2?$embed&shown=4")
        assertEquals(2, raftArtifactViewerShownVersion(pinned, latestVersion = 4, headerVersion = 4))
        // From shown=4, a version picked navigates to /v/2 without the query: the header says 2.
        val shown4 = stayAt("$origin/artifacts/$shortId?$embed&shown=4")
        val picked = stayAt("$origin/artifacts/$shortId/v/2")
        assertEquals(RaftArtifactViewerNavigation.Stay(2), picked)
        assertEquals(4, raftArtifactViewerShownVersion(shown4, latestVersion = 4, headerVersion = 4))
        assertEquals(2, raftArtifactViewerShownVersion(picked, latestVersion = 4, headerVersion = 4))
        // Back to the latest without `shown`: the latest from the summary (read again).
        val latest = stayAt("$origin/artifacts/$shortId?$embed")
        assertTrue(raftArtifactViewerRereadsLatest(picked, latest))
        assertEquals(5, raftArtifactViewerShownVersion(latest, latestVersion = 5, headerVersion = 4))
    }

    @Test
    fun aMalformedShownIsIgnored() {
        for (bad in listOf("shown=", "shown=abc", "shown=0", "shown=-3", "shown=3.5", "shown", "xshown=4", "shown_x=4")) {
            val stay = stayAt("$origin/artifacts/$shortId?embed=raft-settings-v1&$bad")
            assertEquals(null, stay.shown, bad)
            assertEquals(6, raftArtifactViewerShownVersion(stay, latestVersion = 6, headerVersion = 1), bad)
        }
        // A `shown` in the fragment is not the address's query.
        assertEquals(null, stayAt("$origin/artifacts/$shortId#shown=4").shown)
    }

    @Test
    fun androidPadsTheWebViewAboveTheNavigationBarAndTheKeyboard() {
        // 3-button navigation bar 48 dp at density 1.5 = 72 px (the device's y 1208–1280).
        assertEquals(72, raftArtifactViewerWebBottomInsetPx(imeBottomPx = 0, navigationBarBottomPx = 72, hostPadsWebView = true))
        // Keyboard up: its inset already includes the navigation bar; the page ends above the keyboard.
        assertEquals(560, raftArtifactViewerWebBottomInsetPx(imeBottomPx = 560, navigationBarBottomPx = 72, hostPadsWebView = true))
        // Gesture navigation, no keyboard.
        assertEquals(24, raftArtifactViewerWebBottomInsetPx(imeBottomPx = 0, navigationBarBottomPx = 24, hostPadsWebView = true))
        assertEquals(0, raftArtifactViewerWebBottomInsetPx(imeBottomPx = -1, navigationBarBottomPx = 0, hostPadsWebView = true))
    }

    @Test
    fun androidInsetPolicySelectsNativeModesByChromiumMajorVersion() {
        assertEquals(WebContainerAndroidInsetMode.HostHandled, raftAndroidWebViewInsetPolicy(null))
        assertEquals(WebContainerAndroidInsetMode.HostHandled, raftAndroidWebViewInsetPolicy(0))
        assertEquals(WebContainerAndroidInsetMode.HostHandled, raftAndroidWebViewInsetPolicy(114))
        assertEquals(WebContainerAndroidInsetMode.HostHandled, raftAndroidWebViewInsetPolicy(138))
        assertEquals(WebContainerAndroidInsetMode.NativeImeHostNavBar, raftAndroidWebViewInsetPolicy(139))
        assertEquals(WebContainerAndroidInsetMode.NativeImeHostNavBar, raftAndroidWebViewInsetPolicy(143))
        assertEquals(WebContainerAndroidInsetMode.NativeImeAndBottomSystemBars, raftAndroidWebViewInsetPolicy(144))
        assertEquals(WebContainerAndroidInsetMode.NativeImeAndBottomSystemBars, raftAndroidWebViewInsetPolicy(150))
    }

    @Test
    fun androidWebBottomInsetRespectsNativeWebViewInsetModes() {
        // M139-M143: static navigation bar only; IME never resizes the Android WebView View.
        assertEquals(
            72,
            raftArtifactViewerWebBottomInsetPx(
                imeBottomPx = 560,
                navigationBarBottomPx = 72,
                hostPadsWebView = true,
                androidInsetMode = WebContainerAndroidInsetMode.NativeImeHostNavBar,
            ),
        )
        assertEquals(
            0,
            raftArtifactViewerWebBottomInsetPx(
                imeBottomPx = 560,
                navigationBarBottomPx = -4,
                hostPadsWebView = true,
                androidInsetMode = WebContainerAndroidInsetMode.NativeImeHostNavBar,
            ),
        )
        // M144+: Chromium handles both IME and bottom system bars natively.
        assertEquals(
            0,
            raftArtifactViewerWebBottomInsetPx(
                imeBottomPx = 560,
                navigationBarBottomPx = 72,
                hostPadsWebView = true,
                androidInsetMode = WebContainerAndroidInsetMode.NativeImeAndBottomSystemBars,
            ),
        )
        // iOS / Desktop never apply host bottom padding regardless of inset mode.
        assertEquals(
            0,
            raftArtifactViewerWebBottomInsetPx(
                imeBottomPx = 560,
                navigationBarBottomPx = 72,
                hostPadsWebView = false,
                androidInsetMode = WebContainerAndroidInsetMode.HostHandled,
            ),
        )
    }

    @Test
    fun webViewBackgroundArgbPersistsAfterPaintOnlyWhenRequested() {
        val darkCanvas = 0xFF1B1B19.toInt()
        assertEquals(darkCanvas, raftWebViewBackgroundArgb(darkCanvas, painted = false))
        assertEquals(RAFT_WEB_VIEW_DEFAULT_BACKGROUND_ARGB, raftWebViewBackgroundArgb(darkCanvas, painted = true))
        assertEquals(
            darkCanvas,
            raftWebViewBackgroundArgb(darkCanvas, painted = true, persistAfterPaint = true),
        )
        assertEquals(
            RAFT_WEB_VIEW_DEFAULT_BACKGROUND_ARGB,
            raftWebViewBackgroundArgb(0, painted = true, persistAfterPaint = true),
        )
    }
}
