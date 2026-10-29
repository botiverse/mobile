package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun fromAPinnedVersionToTheLatestTheLatestIsReadAgain() {
        val pinned = stayAt("$origin/artifacts/$shortId/v/1?embed=raft-settings-v1&shell=host")
        val latest = stayAt("$origin/artifacts/$shortId?embed=raft-settings-v1&shell=host")
        val other = stayAt("$origin/artifacts/$shortId/v/2?embed=raft-settings-v1&shell=host")
        assertTrue(raftArtifactViewerRereadsLatest(pinned, latest), "View latest / Back to Latest on a pinned version")
        // The first address (the page loading at the latest) is already read on open.
        assertFalse(raftArtifactViewerRereadsLatest(null, latest))
        assertFalse(raftArtifactViewerRereadsLatest(latest, latest))
        assertFalse(raftArtifactViewerRereadsLatest(pinned, other))
        assertFalse(raftArtifactViewerRereadsLatest(latest, other))
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
    fun whereTheWebViewInsetsItselfNothingIsAdded() {
        // iOS: WKWebView applies the safe area and the keyboard itself (no double inset).
        assertEquals(0, raftArtifactViewerWebBottomInsetPx(imeBottomPx = 336, navigationBarBottomPx = 34, hostPadsWebView = false))
    }
}
