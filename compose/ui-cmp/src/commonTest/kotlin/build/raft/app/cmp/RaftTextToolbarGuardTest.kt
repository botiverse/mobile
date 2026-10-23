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
    fun aMenuWithNoAutofillItemsIsNeverForwarded() {
        val platform = FakePlatformToolbar()
        val guard = RaftTextToolbarGuard(platform)

        guard.showMenu(rect, null, null, null, null, null)

        assertEquals(0, platform.shows)
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
    fun withoutTheGuardTheStaleRequestSurvivesTheFieldsHide() {
        // Documents the CMP behaviour the guard works around.
        val platform = FakePlatformToolbar()

        platform.showMenu(rect, null, null, null, null)
        platform.hideLikeBasicTextField()

        assertEquals(0, platform.hides)
        assertEquals(true, platform.pendingRequest)
    }

    @Test
    fun statusFollowsThePlatformWhenNothingIsRequested() {
        val platform = FakePlatformToolbar()
        val guard = RaftTextToolbarGuard(platform)
        platform.presented = true

        assertEquals(TextToolbarStatus.Shown, guard.status)
        guard.hide()
        assertEquals(TextToolbarStatus.Hidden, guard.status)
        assertEquals(1, platform.hides)
    }
}
