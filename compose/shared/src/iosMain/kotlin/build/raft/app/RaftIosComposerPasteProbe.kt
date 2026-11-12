package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * Test harness for paste in the composer's editor (iosAppTests RaftIosComposerPasteTests): a text
 * field that takes the focus, optionally with the composer's clipboard
 * ([rememberThreadComposerClipboard]) and its focus report to the host's image paste
 * ([threadComposerImagePasteFocus], which hooks the editor view in RaftIosImagePaste.swift).
 */
fun raftComposerPasteProbeViewController(composerClipboard: Boolean, reportsFocus: Boolean): UIViewController =
    ComposeUIViewController {
        val focusRequester = remember { FocusRequester() }
        val owner = remember { Any() }
        val clipboard = if (composerClipboard) rememberThreadComposerClipboard() else LocalClipboard.current
        DisposableEffect(owner) {
            onDispose { ThreadComposerImagePasteRequests.editorFocusChanged(owner, false) }
        }
        CompositionLocalProvider(LocalClipboard provides clipboard) {
            Box(Modifier.fillMaxSize().padding(top = 160.dp, start = 16.dp, end = 16.dp)) {
                BasicTextField(
                    state = rememberTextFieldState(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .focusRequester(focusRequester)
                        .then(if (reportsFocus) Modifier.threadComposerImagePasteFocus(owner) else Modifier),
                )
            }
        }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
