package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.webcontainer.WebJsDialogKind
import build.raft.webcontainer.WebJsDialogRequest

internal const val RAFT_WEB_JS_DIALOG_TAG = "raft_web_js_dialog"
internal const val RAFT_WEB_JS_DIALOG_MESSAGE_TAG = "raft_web_js_dialog_message"
internal const val RAFT_WEB_JS_DIALOG_INPUT_TAG = "raft_web_js_dialog_input"
internal const val RAFT_WEB_JS_DIALOG_OK_TAG = "raft_web_js_dialog_ok"
internal const val RAFT_WEB_JS_DIALOG_CANCEL_TAG = "raft_web_js_dialog_cancel"

/**
 * Compose UI state holder for web page JavaScript modals (`alert`, `confirm`, `prompt`).
 *
 * Enforces the foreground-only rule: while [visible] is false (panel collapsed, hidden, or
 * covered by another modal), incoming requests are immediately cancelled without showing UI, and
 * transitioning [visible] from `true` to `false` immediately cancels any open dialog so WebKit /
 * Chromium never blocks waiting on a hidden page.
 */
internal class RaftWebJsDialogState(
    initialVisible: Boolean = true,
) {
    private var visible: Boolean = initialVisible
    var activeRequest: WebJsDialogRequest? by mutableStateOf(null)
        private set

    fun onVisibilityChanged(nextVisible: Boolean) {
        visible = nextVisible
        if (!nextVisible) {
            dismissActive()
        }
    }

    fun show(request: WebJsDialogRequest) {
        if (!visible || request.isSettled) {
            request.cancel()
            return
        }
        val previous = activeRequest
        activeRequest = request
        request.addOnSettledListener {
            if (activeRequest === request) {
                activeRequest = null
            }
        }
        if (previous !== request) {
            previous?.cancel()
        }
    }

    fun confirmActive(promptValue: String? = null) {
        val current = activeRequest ?: return
        activeRequest = null
        current.confirm(promptValue)
    }

    fun dismissActive() {
        val current = activeRequest ?: return
        activeRequest = null
        current.cancel()
    }
}

/**
 * Presents the active [WebJsDialogRequest] using Raft Design System's [RaftDialog] (rendered in
 * [build.raft.designsystem.components.RaftWindowDialog] above both Android `WebView` and iOS
 * `WKWebView`), guaranteeing single-shot settlement across confirm, cancel, backdrop tap, back
 * press, and composable disposal.
 */
@Composable
internal fun RaftWebJsDialogHost(
    state: RaftWebJsDialogState,
) {
    DisposableEffect(state) {
        onDispose { state.dismissActive() }
    }
    val request = state.activeRequest ?: return
    if (request.isSettled) return

    val hostTitle =
        remember(request.id, request.originUrl) {
            raftMobileWebPanelDisplayHost(request.originUrl)
        }.ifBlank { stringResource(Res.string.in_app_browser_title) }
    var promptText by remember(request.id) { mutableStateOf(request.defaultPromptText) }
    val colors = RaftTheme.colors

    RaftDialog(
        onDismissRequest = { state.dismissActive() },
        title = hostTitle,
        modifier = Modifier.testTag(RAFT_WEB_JS_DIALOG_TAG),
        size = RaftDialogSize.Sm,
        dismissOnClickOutside = true,
        showClose = false,
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            if (request.kind != WebJsDialogKind.Alert) {
                RaftButton(
                    text = stringResource(Res.string.common_cancel),
                    onClick = { state.dismissActive() },
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg,
                    modifier = Modifier.testTag(RAFT_WEB_JS_DIALOG_CANCEL_TAG),
                )
            }
            RaftButton(
                text = stringResource(Res.string.common_ok),
                onClick = {
                    if (request.kind == WebJsDialogKind.Prompt) {
                        state.confirmActive(promptText)
                    } else {
                        state.confirmActive()
                    }
                },
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg,
                modifier = Modifier.testTag(RAFT_WEB_JS_DIALOG_OK_TAG),
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (request.message.isNotEmpty()) {
                RaftText(
                    text = request.message,
                    style = RaftTheme.typography.body,
                    color = colors.foreground,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                            .testTag(RAFT_WEB_JS_DIALOG_MESSAGE_TAG),
                )
            }
            if (request.kind == WebJsDialogKind.Prompt) {
                RaftTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    placeholder = stringResource(Res.string.mobile_web_panel_js_prompt_placeholder),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions =
                        KeyboardActions(
                            onDone = { state.confirmActive(promptText) },
                        ),
                    modifier = Modifier.fillMaxWidth().testTag(RAFT_WEB_JS_DIALOG_INPUT_TAG),
                )
            }
        }
    }
}
