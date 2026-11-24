package build.raft.app.cmp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/**
 * Wraps the platform [TextToolbar] so a text field's "hide the menu" always reaches it.
 *
 * Why (feedback 321b5e14, artin's "Select All" pill over the empty composer): on iOS, CMP 1.12
 * text fields use this legacy toolbar (the new context menu is off there). Tapping the caret of an
 * empty, focused field asks for the Cursor menu with no items (no text to select, and no Paste
 * when the clipboard has no text). UIKit presents nothing, so the toolbar keeps reporting
 * [TextToolbarStatus.Hidden], but CMP's input view remembers the request and replays it on every
 * text-field geometry change. Typing then asks to hide the menu, and BasicTextField only calls
 * [TextToolbar.hide] when the status is Shown, so the request is never cleared. The first
 * characters (Pinyin marked text, dictation) make Select All available, and the next geometry
 * change (the candidate bar changing the keyboard height, the composer growing) replays the request
 * and UIKit shows a lone "Select All".
 *
 * Second case (task #348, the Paste menu now and then at the top-left corner of the screen): while
 * a field is not laid out or is being re-attached (keyboard opening, composer growing, switching
 * conversations), or when the input view replays a remembered request on a geometry change, the
 * rect CMP passes is empty: [Rect.Zero], or a zero-size point once a window offset is added. UIKit
 * anchors the menu to that point, i.e. the top-left corner.
 *
 * This guard
 * 1. never forwards a menu with no items, hiding instead;
 * 2. reports Shown while a menu it forwarded has not been hidden, so the field's hide reaches the
 *    platform toolbar;
 * 3. never forwards an unusable rect ([isUsableMenuRect]: zero width and zero height, or not
 *    finite). It uses the last usable rect it forwarded instead; with none, it hides. A caret rect
 *    (zero width, non-zero height) is usable. [hide] forgets the last rect, so a later request
 *    never reuses the position of an old field or an old keyboard height.
 *
 * Installed on iOS only (RaftCmpIos). Android text fields use the new text context menu
 * (LocalTextContextMenuToolbarProvider), which anchors to the field's own layout and never calls
 * [LocalTextToolbar], so this guard would not see those menus.
 */
class RaftTextToolbarGuard(private val platform: TextToolbar) : TextToolbar {
    private var requested = false
    private var lastUsableRect: Rect? = null

    override val status: TextToolbarStatus
        get() = if (requested) TextToolbarStatus.Shown else platform.status

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        if (onCopyRequested == null && onPasteRequested == null && onCutRequested == null && onSelectAllRequested == null) {
            hide()
            return
        }
        val anchor = anchorFor(rect) ?: return hide()
        requested = true
        platform.showMenu(anchor, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
    }

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
        onAutofillRequested: (() -> Unit)?,
    ) {
        if (
            onCopyRequested == null && onPasteRequested == null && onCutRequested == null &&
            onSelectAllRequested == null && onAutofillRequested == null
        ) {
            hide()
            return
        }
        val anchor = anchorFor(rect) ?: return hide()
        requested = true
        platform.showMenu(anchor, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested, onAutofillRequested)
    }

    override fun hide() {
        requested = false
        lastUsableRect = null
        platform.hide()
    }

    /** The rect to show the menu at: [rect] when usable, else the last usable one, else null. */
    private fun anchorFor(rect: Rect): Rect? {
        if (isUsableMenuRect(rect)) {
            lastUsableRect = rect
            return rect
        }
        return lastUsableRect
    }
}

/**
 * Whether [rect] can anchor a text menu. A rect with neither width nor height (Rect.Zero, or a
 * zero-size point from an unplaced field) cannot; a caret rect (zero width, real height) can.
 */
internal fun isUsableMenuRect(rect: Rect): Boolean =
    rect.isFinite && (rect.width > 0f || rect.height > 0f)

/** Provides [RaftTextToolbarGuard] around the current [LocalTextToolbar] to [content]. */
@Composable
fun ProvideRaftTextToolbarGuard(content: @Composable () -> Unit) {
    val platform = LocalTextToolbar.current
    val guard = remember(platform) { RaftTextToolbarGuard(platform) }
    CompositionLocalProvider(LocalTextToolbar provides guard, content = content)
}
