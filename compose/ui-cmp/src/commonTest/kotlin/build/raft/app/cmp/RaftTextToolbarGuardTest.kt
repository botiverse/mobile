package build.raft.app.cmp

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftTextToolbarGuardTest {
    /**
     * Like CMP's iOS toolbar: it remembers every show request, but reports Shown only once UIKit
     * actually presented a menu, which never happens for a request UIKit had no items for.
     */
    private class FakePlatformToolbar : TextToolbar {
        var shows = 0
        var hides = 0
        var pendingRequest = false
        var presented = false
        var lastRect: Rect? = null

        override val status: TextToolbarStatus
            get() = if (presented) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) {
            shows++
            lastRect = rect
            pendingRequest = true
        }

        override fun hide() {
            hides++
            pendingRequest = false
            presented = false
        }
    }

    /** What BasicTextField's toolbar handler does when the field wants the menu gone. */
    private fun TextToolbar.hideLikeBasicTextField() {
        if (status == TextToolbarStatus.Shown) hide()
    }

    private val rect = Rect(0f, 0f, 10f, 10f)

    @Test
    fun aMenuWithNoItemsIsNeverForwardedAndClearsAnEarlierRequest() {
        val platform = FakePlatformToolbar()
        val guard = RaftTextToolbarGuard(platform)

        guard.showMenu(rect, null, null, null, null)

        assertEquals(0, platform.shows)
        assertEquals(false, platform.pendingRequest)
        assertEquals(TextToolbarStatus.Hidden, guard.status)
    }

    @Test
    fun theFieldsHideReachesThePlatformWhenUIKitNeverPresentedTheMenu() {
        val platform = FakePlatformToolbar()
        val guard = RaftTextToolbarGuard(platform)

        // The caret menu of an empty field with text on the clipboard: Paste only. UIKit has not
        // (yet) reported the menu as presented.
        guard.showMenu(rect, null, {}, null, null)
        assertEquals(1, platform.shows)
        assertEquals(TextToolbarStatus.Shown, guard.status)

        // Typing starts: the field hides the toolbar only when it reports Shown.
        guard.hideLikeBasicTextField()

        assertEquals(1, platform.hides)
        assertEquals(false, platform.pendingRequest)
        assertEquals(TextToolbarStatus.Hidden, guard.status)
    }

    @Test
    fun aZeroRectReusesTheLastUsableRect() {
        val platform = FakePlatformToolbar()
        val guard = RaftTextToolbarGuard(platform)
        val usable = Rect(40f, 300f, 120f, 340f)

        guard.showMenu(usable, null, {}, null, null)
        // The field is re-attached (keyboard opening) and the request is replayed with Rect.Zero.
        guard.showMenu(Rect.Zero, null, {}, null, null)

        assertEquals(2, platform.shows)
        assertEquals(usable, platform.lastRect)
        assertEquals(TextToolbarStatus.Shown, guard.status)
    }

    @Test
    fun aZeroRectWithNoUsableRectYetIsNotShown() {
        val platform = FakePlatformToolbar()
        val guard = RaftTextToolbarGuard(platform)

        guard.showMenu(Rect.Zero, null, {}, null, null)
        // A zero-size point away from the origin (Rect.Zero plus a window offset) is not usable either.
        guard.showMenu(Rect(16f, 80f, 16f, 80f), null, {}, null, null)

        assertEquals(0, platform.shows)
        assertEquals(false, platform.pendingRequest)
        assertEquals(TextToolbarStatus.Hidden, guard.status)
    }

    @Test
    fun hideForgetsTheLastUsableRect() {
        val platform = FakePlatformToolbar()
        val guard = RaftTextToolbarGuard(platform)

        guard.showMenu(Rect(40f, 300f, 120f, 340f), null, {}, null, null)
        guard.hide()
        guard.showMenu(Rect.Zero, null, {}, null, null)

        assertEquals(1, platform.shows)
        assertEquals(false, platform.pendingRequest)
        assertEquals(TextToolbarStatus.Hidden, guard.status)
    }

    @Test
    fun aCaretRectWithZeroWidthIsForwarded() {
        val platform = FakePlatformToolbar()
        val guard = RaftTextToolbarGuard(platform)
        val caret = Rect(0f, 0f, 0f, 20f)

        guard.showMenu(caret, null, {}, null, null)

        assertEquals(1, platform.shows)
        assertEquals(caret, platform.lastRect)
    }
}
