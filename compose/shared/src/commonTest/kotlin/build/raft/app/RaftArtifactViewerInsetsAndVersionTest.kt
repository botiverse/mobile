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
    fun onTheLatestTheHeaderFollowsTheShownVersionThePageAdds() {
        val embed = "embed=raft-settings-v1&shell=host"
        // First open: no `shown`, the latest from the summary.
        val opened = stayAt("$origin/artifacts/$shortId?$embed")
        assertEquals(null, opened.shown)
        assertEquals(3, raftArtifactViewerShownVersion(opened, latestVersion = 3, headerVersion = 3))
        // View latest on the latest, read again in place (slock #12043): `shown=<n>` added, replaced.
        val shown3 = stayAt("$origin/artifacts/$shortId?$embed&shown=3")
        assertEquals(RaftArtifactViewerNavigation.Stay(null, 3), shown3)
        assertEquals(3, raftArtifactViewerShownVersion(shown3, latestVersion = 2, headerVersion = 2))
        // Again after v4 was published: `shown` changes 3 -> 4, still this Artifact, no summary re-read needed.
        val shown4 = stayAt("$origin/artifacts/$shortId?shown=4&$embed")
        assertEquals(4, raftArtifactViewerShownVersion(shown4, latestVersion = 3, headerVersion = 3))
        assertFalse(raftArtifactViewerRereadsLatest(shown3, shown4))
        // Under a server path too.
        assertEquals(RaftArtifactViewerNavigation.Stay(null, 5), raftArtifactViewerNavigation("$origin/s/acme/artifacts/$shortId?$embed&shown=5", origin, shortId))
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
