package build.raft.app

import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View

/**
 * Keeps a floating text toolbar (the Cut / Copy / Paste / Select all bar) where it is while it
 * closes, instead of letting it jump to the window's top-left corner.
 *
 * Why (artin 2026-11-03, composer, 1.19.1 on HUAWEI): tapping to cancel a selection made the bar
 * flash at the top-left of the screen for ~4 frames, with "Paste / Select all" on it, before it
 * faded out. Compose's text field (foundation 1.12, new text context menu, the Android default)
 * stops having an anchor the moment the selection collapses (`derivedVisibleContentBounds` goes
 * null) and then reports `Rect.Zero` for it (TextFieldCoreModifier: `?: Rect.Zero`). Its toolbar
 * provider re-reads the anchor and the menu as soon as that state changes and pushes both to the
 * open ActionMode (`invalidateContentRect` / `invalidate`), while the request to close it arrives
 * a frame later. In between, the platform lays the bar out at the zero rect (the window origin)
 * and rebuilds it for a caret (Paste / Select all).
 *
 * A zero-size rect is never a real anchor (a caret or a selection always has a height), so this
 * callback answers it with the last real anchor, and leaves the menu as it was while there is
 * none: the bar stays put, unchanged, and closes there. Everything else (items, smart actions,
 * clicks) goes to Compose's callback untouched.
 */
class RaftFloatingToolbarAnchorGuard internal constructor(
    private val delegate: ActionMode.Callback2,
) : ActionMode.Callback2() {
    private val lastAnchor = Rect()
    private var hasAnchor = false
    private val probe = Rect()
    private var originatingView: View? = null

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean =
        delegate.onCreateActionMode(mode, menu)

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        // The platform re-reads the menu and then the anchor. With no anchor the bar is about to
        // close: keep the items it shows rather than flash the caret's set.
        if (hasAnchor) {
            probe.setEmpty()
            delegate.onGetContentRect(mode, originatingView, probe)
            if (!isAnchor(probe)) return false
        }
        return delegate.onPrepareActionMode(mode, menu)
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean =
        delegate.onActionItemClicked(mode, item)

    override fun onDestroyActionMode(mode: ActionMode) {
        delegate.onDestroyActionMode(mode)
    }

    override fun onGetContentRect(mode: ActionMode, view: View?, outRect: Rect) {
        originatingView = view
        delegate.onGetContentRect(mode, view, outRect)
        if (isAnchor(outRect)) {
            lastAnchor.set(outRect)
            hasAnchor = true
        } else if (hasAnchor) {
            outRect.set(lastAnchor)
        }
    }

    companion object {
        /** A caret or a selection has a size; Compose's "no anchor" is a 0x0 rect. */
        fun isAnchor(rect: Rect): Boolean = rect.width() != 0 || rect.height() != 0

        /** Wraps floating text toolbars' callbacks; anything else is passed on as it is. */
        fun wrap(callback: ActionMode.Callback, type: Int): ActionMode.Callback =
            if (type == ActionMode.TYPE_FLOATING && callback is ActionMode.Callback2 && callback !is RaftFloatingToolbarAnchorGuard) {
                RaftFloatingToolbarAnchorGuard(callback)
            } else {
                callback
            }
    }
}
