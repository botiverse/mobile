package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle

/**
 * Incident task #924: read-only text rendered by the PLATFORM text view so the system selection
 * menu owns copy/select and exposes platform actions (Android PROCESS_TEXT items such as
 * Translate; iOS Look Up / Translate / Share). Compose `SelectionContainer { Text }` only offers
 * the Compose toolbar and suppresses those, so it must not be used for the Select Text dialog.
 *
 * - Android: `AndroidView` of a `TextView` with `setTextIsSelectable(true)`; the [modifier]'s
 *   `testTag` is exposed as a resource id (`testTagsAsResourceId`) and the view also carries the
 *   native `R.id.thread_message_selection_text` id for UIAutomator.
 * - iOS: `UIKitView` of a non-editable, selectable, non-scrolling `UITextView` sized to its
 *   intrinsic height for the offered width.
 *
 * Height wraps the text (safe inside a `LazyColumn` item); width fills the incoming constraints.
 * [selection], when given, reads what the reader has selected in the platform view at the moment
 * it is asked (the Select Text sheet's Copy and Quote).
 */
@Composable
expect fun RaftNativeSelectableText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    selection: RaftNativeTextSelection? = null,
)

/**
 * The live selection of a [RaftNativeSelectableText]: the platform view registers its reader
 * while it is shown. [selectedText] is "" when nothing is selected or the view is gone.
 */
class RaftNativeTextSelection {
    internal var reader: (() -> String)? = null

    fun selectedText(): String = reader?.invoke().orEmpty()
}

/** The [start, end) slice of [text] a platform selection covers, or "" for none / a caret. */
internal fun raftNativeSelectedSlice(text: CharSequence, start: Int, end: Int): String {
    val from = minOf(start, end).coerceIn(0, text.length)
    val to = maxOf(start, end).coerceIn(0, text.length)
    return if (to > from) text.subSequence(from, to).toString() else ""
}
