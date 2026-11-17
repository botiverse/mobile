package build.raft.app.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import build.raft.app.RaftTraceIdNote
import build.raft.app.data.RaftAssistantWeChatConnectApi
import build.raft.app.resources.*
import build.raft.app.secondFactorStepUpLocalClock
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlin.time.Instant
import org.jetbrains.compose.resources.StringResource

// Assistant Settings › Connect WeChat at a non-phone width (web WeChatConnectDialog.tsx, botiverse/
// slock staging 34511fe7): what connecting means first, then the QR code with its live state until
// WeChat answers for good, WeChat's verification code when it asks for one, and Get new QR code
// when the code expired or failed. Its state is [AssistantWeChatConnectStore]'s.

/** Web `FAILURE_IDS`. */
private val WeChatConnectFailure.message: StringResource
    get() =
        when (this) {
            WeChatConnectFailure.Expired -> Res.string.assistant_wechat_failed_expired
            WeChatConnectFailure.Used -> Res.string.assistant_wechat_failed_used
            WeChatConnectFailure.Blocked -> Res.string.assistant_wechat_failed_blocked
            WeChatConnectFailure.AlreadyBound -> Res.string.assistant_wechat_failed_already_bound
            WeChatConnectFailure.InUse -> Res.string.assistant_wechat_failed_in_use
            WeChatConnectFailure.Unbound -> Res.string.assistant_wechat_failed_unbound
            WeChatConnectFailure.RateLimited -> Res.string.assistant_wechat_failed_rate_limited
            WeChatConnectFailure.Upstream -> Res.string.assistant_wechat_failed_upstream
            WeChatConnectFailure.Unavailable -> Res.string.assistant_wechat_failed_unavailable
            WeChatConnectFailure.Other -> Res.string.assistant_wechat_failed_other
        }

/** The flow's store for one open dialog: its polls end when the dialog leaves composition. */
@Composable
internal fun rememberAssistantWeChatConnectStore(
    api: RaftAssistantWeChatConnectApi?,
    serverId: String,
    onStatusChanged: suspend () -> Unit,
): AssistantWeChatConnectStore? {
    val scope = rememberCoroutineScope()
    val statusChanged by rememberUpdatedState(onStatusChanged)
    val store = remember(api, serverId, scope) { api?.let { AssistantWeChatConnectStore(it, serverId, scope, onStatusChanged = { statusChanged() }) } }
    // Leaving the screen: the poll is abandoned and the attempt's cookie dropped.
    DisposableEffect(store) { onDispose { store?.stop() } }
    return store
}

/**
 * The connect flow's dialog. Cancel, the close button, a tap outside and Back all close it (asking
 * first while a verification code is typed, web `confirmDiscard`); closing stops the flow.
 */
@Composable
internal fun AssistantWeChatConnectFlowDialog(
    name: String,
    step: WeChatConnectStep,
    connect: AssistantWeChatConnectStore,
    onClose: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var confirmDiscard by remember { mutableStateOf(false) }
    val dirty = step is WeChatConnectStep.Verify && code.isNotBlank()
    val requestClose: () -> Unit = {
        if (dirty) {
            confirmDiscard = true
        } else {
            onClose()
        }
    }
    val submit: () -> Unit = {
        if (step is WeChatConnectStep.Verify && !step.checking) connect.submitCode(code)
    }
    RaftDialog(
        onDismissRequest = requestClose,
        title = stringResource(Res.string.assistant_wechat_connect_title),
        modifier = Modifier.testTag("wechat-dialog"),
        description = weChatConnectDescription(name, step),
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            WeChatConnectFooter(
                step = step,
                code = code,
                onCancel = requestClose,
                onClose = onClose,
                onShowCode = connect::start,
                onVerify = submit,
                onNewCode = {
                    code = ""
                    connect.start()
                },
            )
        },
    ) {
        WeChatConnectStepBody(name = name, step = step, code = code, onCodeChange = { code = it }, onSubmitCode = submit)
    }
    if (confirmDiscard) {
        AssistantInstructionsDiscardDialog(
            onKeepEditing = { confirmDiscard = false },
            onDiscard = {
                confirmDiscard = false
                onClose()
            },
        )
    }
}

/** The dialog's description per step (web `DialogDescription`); none for a failure. */
@Composable
internal fun weChatConnectDescription(name: String, step: WeChatConnectStep): String? =
    when (step) {
        WeChatConnectStep.Disclosure -> stringResource(Res.string.assistant_wechat_intro, name)
        WeChatConnectStep.Starting, is WeChatConnectStep.Qr -> stringResource(Res.string.assistant_wechat_scan_instruction)
        is WeChatConnectStep.Verify -> stringResource(Res.string.assistant_wechat_verify_body)
        is WeChatConnectStep.Failed, WeChatConnectStep.Connected -> null
    }

/**
 * The footer (web `DialogActions`): Cancel and the step's one pink commit (Show QR code, Verify,
 * Get new QR code); only a pink Close once WeChat is no longer offered here.
 */
@Composable
internal fun WeChatConnectFooter(
    step: WeChatConnectStep,
    code: String,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onShowCode: () -> Unit,
    onVerify: () -> Unit,
    onNewCode: () -> Unit,
) {
    if (step is WeChatConnectStep.Failed && step.reason == WeChatConnectFailure.Unavailable) {
        RaftButton(
            text = stringResource(Res.string.common_close),
            onClick = onClose,
            modifier = Modifier.testTag("wechat-close"),
            variant = RaftButtonVariant.Primary,
            size = RaftButtonSize.Lg,
        )
        return
    }
    RaftButton(
        text = stringResource(Res.string.common_cancel),
        onClick = onCancel,
        modifier = Modifier.testTag("wechat-cancel"),
        variant = RaftButtonVariant.Ghost,
        size = RaftButtonSize.Lg,
    )
    when (step) {
        WeChatConnectStep.Disclosure ->
            RaftButton(
                text = stringResource(Res.string.assistant_wechat_show_code),
                onClick = onShowCode,
                modifier = Modifier.testTag("wechat-commit"),
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg,
            )
        is WeChatConnectStep.Verify ->
            RaftButton(
                text = stringResource(Res.string.assistant_wechat_verify),
                onClick = onVerify,
                modifier = Modifier.testTag("wechat-commit"),
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg,
                enabled = code.isNotBlank(),
                loading = step.checking,
                loadingLabel = stringResource(Res.string.assistant_wechat_verifying),
            )
        is WeChatConnectStep.Failed ->
            RaftButton(
                text = stringResource(Res.string.assistant_wechat_new_code),
                onClick = onNewCode,
                modifier = Modifier.testTag("wechat-commit"),
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg,
            )
        WeChatConnectStep.Starting, is WeChatConnectStep.Qr, WeChatConnectStep.Connected -> Unit
    }
}

/** The step's body (web `ConnectStepBody`). [clockOf] turns `expiresAt` into the local `HH:mm`. */
@Composable
internal fun WeChatConnectStepBody(
    name: String,
    step: WeChatConnectStep,
    code: String,
    onCodeChange: (String) -> Unit,
    onSubmitCode: () -> Unit,
    clockOf: (String) -> String = ::weChatExpiryClock,
    /** Web `autoFocus` on the code field; off for still captures (no caret). */
    autoFocus: Boolean = true,
) {
    Column(
        Modifier.fillMaxWidth().testTag("wechat-step"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (step) {
            WeChatConnectStep.Disclosure -> WeChatDisclosure(name)
            WeChatConnectStep.Starting -> WeChatQrStep(name, content = null, line = null, clockOf = clockOf)
            is WeChatConnectStep.Qr -> WeChatQrStep(name, content = step.attempt.qrContent, line = step, clockOf = clockOf)
            is WeChatConnectStep.Verify -> WeChatVerifyCode(step, code, onCodeChange, onSubmitCode, autoFocus)
            is WeChatConnectStep.Failed -> {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RaftBanner(
                        description = stringResource(step.reason.message),
                        status = RaftBannerStatus.Warning,
                        size = RaftBannerSize.Sm,
                        modifier = Modifier.testTag("wechat-failed"),
                    )
                    RaftTraceIdNote(step.error?.traceId)
                }
            }
            WeChatConnectStep.Connected -> Unit
        }
    }
}

/** Web `formatClock(expiresAt)`: `HH:mm` in this device's zone; the raw value if unreadable. */
internal fun weChatExpiryClock(expiresAt: String): String =
    runCatching { secondFactorStepUpLocalClock(Instant.parse(expiresAt).toEpochMilliseconds()) }.getOrDefault(expiresAt)

@Composable
private fun WeChatDisclosure(name: String) {
    val colors = RaftTheme.colors
    // Hand-rolled as on the web: raft-ui has no text list. Dialog body text (subhead).
    Column(Modifier.fillMaxWidth().testTag("wechat-disclosure"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            stringResource(Res.string.assistant_wechat_disclosure_tencent),
            stringResource(Res.string.assistant_wechat_disclosure_replaces),
            stringResource(Res.string.assistant_wechat_disclosure_window, name),
        ).forEach { item ->
            Row(Modifier.fillMaxWidth()) {
                RaftText("•", Modifier.padding(start = 6.dp, end = 8.dp), style = RaftTheme.typography.subhead, color = colors.foregroundMuted)
                RaftText(item, Modifier.weight(1f), style = RaftTheme.typography.subhead, color = colors.foregroundMuted)
            }
        }
    }
    RaftBanner(
        title = stringResource(Res.string.assistant_wechat_privacy_title),
        description = stringResource(Res.string.assistant_wechat_privacy_body, name),
        status = RaftBannerStatus.Warning,
        size = RaftBannerSize.Sm,
    )
}

@Composable
private fun WeChatQrStep(name: String, content: String?, line: WeChatConnectStep.Qr?, clockOf: (String) -> String) {
    val colors = RaftTheme.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        WeChatQrCode(content = content, label = stringResource(Res.string.assistant_wechat_qr_label))
        // Hand-rolled as on the web: a centered live line under the code.
        Row(
            Modifier.fillMaxWidth().testTag("wechat-qr-status"),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (line == null) RaftSpinner()
            val text: AnnotatedString =
                when {
                    line == null -> AnnotatedString(stringResource(Res.string.assistant_wechat_getting_code))
                    line.scanned -> AnnotatedString(stringResource(Res.string.assistant_wechat_scanned))
                    else -> {
                        // A time in a sentence is still the mono face (DS Typography › Usage).
                        val time = clockOf(line.attempt.expiresAt)
                        val sentence = stringResource(Res.string.assistant_wechat_waiting, time)
                        val at = sentence.indexOf(time)
                        val mono = RaftTheme.typography.mono
                        buildAnnotatedString {
                            append(sentence)
                            if (at >= 0) addStyle(SpanStyle(fontFamily = mono), at, at + time.length)
                        }
                    }
                }
            RaftText(text, style = RaftTheme.typography.footnote, color = colors.foregroundMuted, textAlign = TextAlign.Center)
        }
    }
    RaftBanner(
        description = stringResource(Res.string.assistant_wechat_privacy_reminder, name),
        status = RaftBannerStatus.Warning,
        size = RaftBannerSize.Sm,
    )
}

@Composable
private fun WeChatVerifyCode(
    step: WeChatConnectStep.Verify,
    code: String,
    onCodeChange: (String) -> Unit,
    onSubmitCode: () -> Unit,
    autoFocus: Boolean,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focus.requestFocus() } }
    RaftField(
        label = stringResource(Res.string.assistant_wechat_verify_label),
        modifier = Modifier.fillMaxWidth(),
        error =
            when (step.error) {
                WeChatVerifyError.Wrong -> stringResource(Res.string.assistant_wechat_verify_wrong)
                WeChatVerifyError.Malformed -> stringResource(Res.string.assistant_wechat_verify_malformed)
                null -> null
            },
    ) {
        RaftTextField(
            value = code,
            // Web `maxLength={16}`: WeChat's code is at most 16 letters and digits.
            onValueChange = { onCodeChange(it.take(WECHAT_VERIFY_CODE_MAX_LENGTH)) },
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("wechat-verify-code"),
            readOnly = step.checking,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions = KeyboardActions(onDone = { onSubmitCode() }),
        )
    }
}

private const val WECHAT_VERIFY_CODE_MAX_LENGTH = 16
