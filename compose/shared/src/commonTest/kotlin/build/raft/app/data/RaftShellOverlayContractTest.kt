package build.raft.app.data

import build.raft.app.optBoolean
import build.raft.app.optString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task #180: contract for shell-rendered overlays. Argus asked for these before any rendering work,
 * so the lifecycle rules (request / result / cancel / stale id / Back) are pinned independently of
 * the composition.
 */
class RaftShellOverlayContractTest {
    @Test
    fun aResultNamesTheChosenItem() {
        val result = RaftShellOverlayResult(requestId = "req-1", selectedId = "thread-focus-mode")
        val json = result.toJson()

        assertEquals("req-1", json.optString(SHELL_OVERLAY_REQUEST_ID))
        assertEquals("thread-focus-mode", json.optString(SHELL_OVERLAY_SELECTED_ID))
        assertFalse(json.optBoolean(SHELL_OVERLAY_DISMISSED))
    }

    @Test
    fun aDismissedResultIsDistinguishableFromASelection() {
        // Back while the menu is open dismisses it. The shell owns that Back press, which is also
        // what stops Back from closing the whole Activity.
        val dismissed = RaftShellOverlayResult(requestId = "req-1", dismissed = true).toJson()

        assertTrue(dismissed.optBoolean(SHELL_OVERLAY_DISMISSED))
        assertTrue(dismissed.optString(SHELL_OVERLAY_SELECTED_ID).isEmpty())
    }

    @Test
    fun aPageOnlyActsOnTheResultItIssued() {
        val stale = RaftShellOverlayResult(requestId = "req-old", selectedId = "thread-focus-mode").toJson()
        val current = RaftShellOverlayResult(requestId = "req-new", selectedId = "thread-focus-mode").toJson()

        assertFalse(
            raftShouldApplyShellOverlayResult(currentRequestId = "req-new", incoming = stale),
            "a result for a previous request must never mutate the current surface"
        )
        assertTrue(raftShouldApplyShellOverlayResult(currentRequestId = "req-new", incoming = current))
    }

    @Test
    fun theShellDropsCancelsThatDoNotMatchWhatItIsShowing() {
        // A page closing an OLD menu must not dismiss a newer one the shell is now displaying.
        assertTrue(raftShellShouldDropOverlayRequest(activeRequestId = "req-new", incomingRequestId = "req-old"))
        assertFalse(raftShellShouldDropOverlayRequest(activeRequestId = "req-new", incomingRequestId = "req-new"))
        // Nothing open: a stray cancel is harmless, not something to process as a close.
        assertFalse(raftShellShouldDropOverlayRequest(activeRequestId = null, incomingRequestId = "req-old"))
    }

    @Test
    fun requestRoundTripsIconsAndDividers() {
        val request =
            RaftShellOverlayRequest(
                requestId = "req-menu",
                pageKey = "thread-panel",
                anchor = androidx.compose.ui.geometry.Offset(12f, 34f),
                items =
                    listOf(
                        RaftShellOverlayItem(id = "follow", label = "Unfollow thread", iconName = "UnfollowThread"),
                        RaftShellOverlayItem(id = "search", label = "Search in thread", iconName = "Search", dividerBefore = true),
                    ),
            )
        assertEquals(request, request.toJson().toRaftShellOverlayRequestOrNull())
    }
}
