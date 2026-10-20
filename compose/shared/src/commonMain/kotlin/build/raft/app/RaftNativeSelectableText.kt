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
 */
@Composable
expect fun RaftNativeSelectableText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
)
