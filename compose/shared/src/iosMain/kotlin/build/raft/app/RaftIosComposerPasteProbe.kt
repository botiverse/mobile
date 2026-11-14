package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIPasteboard
import platform.UIKit.UIViewController

/**
 * Test harness for paste in the composer's editor (iosAppTests RaftIosComposerPasteTests): a text
 * field that takes the focus, optionally with the composer's clipboard
 * ([rememberThreadComposerClipboard]) and its focus report to the host's image paste
 * ([threadComposerImagePasteFocus], which hooks the editor view in RaftIosImagePaste.swift).
 */
fun raftComposerPasteProbeViewController(composerClipboard: Boolean, reportsFocus: Boolean, initialText: String): UIViewController =
    ComposeUIViewController {
        val focusRequester = remember { FocusRequester() }
        val owner = remember { Any() }
        val clipboard = if (composerClipboard) rememberThreadComposerClipboard() else LocalClipboard.current
        DisposableEffect(owner) {
            onDispose { ThreadComposerImagePasteRequests.editorFocusChanged(owner, false) }
        }
        val toolbar = LocalTextToolbar.current
        val fieldState = rememberTextFieldState(initialText)
        SideEffect {
            RaftComposerPasteProbe.toolbar = toolbar
            RaftComposerPasteProbe.fieldHasText = { fieldState.text.isNotEmpty() }
        }
        CompositionLocalProvider(LocalClipboard provides clipboard) {
            Box(Modifier.fillMaxSize().padding(top = 160.dp, start = 16.dp, end = 16.dp)) {
                BasicTextField(
                    state = fieldState,
                    // A change restarts the field's input session (a new editor view, same focus).
                    keyboardOptions = KeyboardOptions(
                        imeAction = if (RaftComposerPasteProbe.inputRestarts % 2 == 0) ImeAction.Default else ImeAction.Send,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .onGloballyPositioned { RaftComposerPasteProbe.fieldBounds = it.boundsInRoot() }
                        .focusRequester(focusRequester)
                        .then(if (reportsFocus) Modifier.threadComposerImagePasteFocus(owner) else Modifier),
                )
            }
        }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }

private object RaftComposerPasteProbe {
    var inputRestarts by mutableIntStateOf(0)
    var toolbar: TextToolbar? = null
    var fieldBounds: Rect? = null
    var fieldHasText: () -> Boolean = { false }
}

/**
 * Shows the probe field's edit menu as BasicTextField does on iOS after a long press at the caret
 * (CMP 1.12, old context menu: [TextToolbar.showMenu] with the items it can offer). Paste is
 * offered only with text on the clipboard (CMP decides it from `hasStrings`); Select All only with
 * text in the field. False when the probe has not been laid out yet.
 */
fun raftComposerPasteProbeShowEditMenu(): Boolean {
    val toolbar = RaftComposerPasteProbe.toolbar ?: return false
    val bounds = RaftComposerPasteProbe.fieldBounds ?: return false
    val hasText = UIPasteboard.generalPasteboard.hasStrings
    toolbar.showMenu(
        rect = Rect(bounds.left, bounds.top, bounds.left + 2f, bounds.bottom),
        onCopyRequested = null,
        onPasteRequested = if (hasText) ({}) else null,
        onCutRequested = null,
        onSelectAllRequested = if (RaftComposerPasteProbe.fieldHasText()) ({}) else null,
    )
    return true
}

/** Host image pastes delivered to a focused composer so far ([ThreadComposerImagePasteRequests.count]). */
fun raftComposerPasteProbeImagePasteCount(): Int = ThreadComposerImagePasteRequests.count

/**
 * Restarts the focused probe field's input session without a focus change, as the composer's does
 * when its keyboard options change: Compose replaces the editor view (a new first responder) and
 * the composer keeps its focus, so the host's focus report does not run again.
 */
fun raftComposerPasteProbeRestartInputSession() {
    RaftComposerPasteProbe.inputRestarts++
}
