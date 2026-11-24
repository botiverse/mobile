package build.raft.designsystem.components

import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * [Popup] whose content is cut off from a [androidx.compose.foundation.text.selection.SelectionContainer]
 * around its anchor (a message list). A popup is its own window on desktop and Android but inherits
 * the composition locals, the selection registrar among them: a press on text inside it (a preview
 * card's Open link) started a selection measured against the parent window's layout and threw
 * "layouts are not part of the same hierarchy" (Compose macOS 1.29.0 crash). [DisableSelection]
 * gives the content no registrar, so text in a popup is never part of the page's selection.
 */
@Composable
fun RaftSelectionSafePopup(
    popupPositionProvider: PopupPositionProvider,
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit,
) {
    Popup(
        popupPositionProvider = popupPositionProvider,
        onDismissRequest = onDismissRequest,
        properties = properties,
    ) {
        DisableSelection(content)
    }
}
