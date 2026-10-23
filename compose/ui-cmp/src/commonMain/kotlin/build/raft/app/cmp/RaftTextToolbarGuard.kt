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
 * This guard (1) never forwards a menu with no items, hiding instead, and (2) reports Shown while
 * a menu it forwarded has not been hidden, so the field's hide reaches the platform toolbar.
 */
class RaftTextToolbarGuard(private val platform: TextToolbar) : TextToolbar {
    private var requested = false

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
        requested = true
        platform.showMenu(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
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
        requested = true
        platform.showMenu(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested, onAutofillRequested)
    }

    override fun hide() {
        requested = false
        platform.hide()
    }
}

/** Provides [RaftTextToolbarGuard] around the current [LocalTextToolbar] to [content]. */
@Composable
fun ProvideRaftTextToolbarGuard(content: @Composable () -> Unit) {
    val platform = LocalTextToolbar.current
    val guard = remember(platform) { RaftTextToolbarGuard(platform) }
    CompositionLocalProvider(LocalTextToolbar provides guard, content = content)
}
