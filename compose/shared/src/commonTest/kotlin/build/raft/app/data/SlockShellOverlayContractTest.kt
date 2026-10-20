package build.raft.app.data

import build.raft.app.optBoolean
import build.raft.app.optDouble
import build.raft.app.optJsonArray
import build.raft.app.optJsonObject
import build.raft.app.optString
import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * task #180: contract for shell-rendered overlays. Argus asked for these before any rendering work,
 * so the lifecycle rules (request / result / cancel / stale id / Back) are pinned independently of
 * the composition.
 */
class SlockShellOverlayContractTest {
    private val items =
        listOf(
            SlockShellOverlayItem("thread-focus-mode", "Focus mode"),
            SlockShellOverlayItem("thread-toggle-follow", "Unfollow Thread", enabled = false),
        )

    @Test
    fun aRequestCarriesAWindowSpaceAnchorAndItsItems() {
        val request =
            SlockShellOverlayRequest(
                requestId = "req-1",
                pageKey = "compose_thread|th-1",
                anchor = Offset(1904f, 82f),
                items = items,
            )

        val json = request.toJson()

        assertEquals("request", json.optString(SHELL_OVERLAY_MESSAGE_TYPE))
        assertEquals("req-1", json.optString(SHELL_OVERLAY_REQUEST_ID))
        assertEquals("compose_thread|th-1", json.optString(SHELL_OVERLAY_PAGE_KEY))
        // The anchor must survive verbatim: the shell draws in window space and must not re-derive it.
        assertEquals(1904.0, json.optDouble(SHELL_OVERLAY_ANCHOR_X), 0.001)
        assertEquals(82.0, json.optDouble(SHELL_OVERLAY_ANCHOR_Y), 0.001)
        val rows = json.optJsonArray(SHELL_OVERLAY_ITEMS)
        assertEquals(2, rows?.size)
        assertFalse(rows?.optJsonObject(1)?.optBoolean("enabled") ?: true)
    }

    @Test
    fun aResultNamesTheChosenItem() {
        val result = SlockShellOverlayResult(requestId = "req-1", selectedId = "thread-focus-mode")
        val json = result.toJson()

        assertEquals("req-1", json.optString(SHELL_OVERLAY_REQUEST_ID))
        assertEquals("thread-focus-mode", json.optString(SHELL_OVERLAY_SELECTED_ID))
        assertFalse(json.optBoolean(SHELL_OVERLAY_DISMISSED))
    }

    @Test
    fun aDismissedResultIsDistinguishableFromASelection() {
        // Back while the menu is open dismisses it. The shell owns that Back press, which is also
        // what stops Back from closing the whole Activity.
        val dismissed = SlockShellOverlayResult(requestId = "req-1", dismissed = true).toJson()

        assertTrue(dismissed.optBoolean(SHELL_OVERLAY_DISMISSED))
        assertTrue(dismissed.optString(SHELL_OVERLAY_SELECTED_ID).isEmpty())
    }

    @Test
    fun aPageOnlyActsOnTheResultItIssued() {
        val stale = SlockShellOverlayResult(requestId = "req-old", selectedId = "thread-focus-mode").toJson()
        val current = SlockShellOverlayResult(requestId = "req-new", selectedId = "thread-focus-mode").toJson()

        assertFalse(
            slockShouldApplyShellOverlayResult(currentRequestId = "req-new", incoming = stale),
            "a result for a previous request must never mutate the current surface"
        )
        assertTrue(slockShouldApplyShellOverlayResult(currentRequestId = "req-new", incoming = current))
    }

    @Test
    fun aPageWithNoOpenMenuIgnoresAnyResult() {
        val incoming = SlockShellOverlayResult(requestId = "req-1", selectedId = "x").toJson()

        assertFalse(slockShouldApplyShellOverlayResult(currentRequestId = null, incoming = incoming))
        assertFalse(slockShouldApplyShellOverlayResult(currentRequestId = "", incoming = incoming))
        assertFalse(slockShouldApplyShellOverlayResult(currentRequestId = "req-1", incoming = null))
    }

    @Test
    fun theShellDropsCancelsThatDoNotMatchWhatItIsShowing() {
        // A page closing an OLD menu must not dismiss a newer one the shell is now displaying.
        assertTrue(slockShellShouldDropOverlayRequest(activeRequestId = "req-new", incomingRequestId = "req-old"))
        assertFalse(slockShellShouldDropOverlayRequest(activeRequestId = "req-new", incomingRequestId = "req-new"))
        // Nothing open: a stray cancel is harmless, not something to process as a close.
        assertFalse(slockShellShouldDropOverlayRequest(activeRequestId = null, incomingRequestId = "req-old"))
    }

    @Test
    fun cancellingIsItsOwnMessageType() {
        val cancel = cancelSlockShellOverlayJson("req-1")

        assertEquals("cancel", cancel.optString(SHELL_OVERLAY_MESSAGE_TYPE))
        assertEquals("req-1", cancel.shellOverlayRequestIdOrNull())
    }

    @Test
    fun aMessageWithoutARequestIdIsNotTrusted() {
        val noId = buildJsonObject { put("selectedId", "x") }

        assertNull(noId.shellOverlayRequestIdOrNull())
        assertFalse(slockShouldApplyShellOverlayResult(currentRequestId = "req-1", incoming = noId))
    }
}
