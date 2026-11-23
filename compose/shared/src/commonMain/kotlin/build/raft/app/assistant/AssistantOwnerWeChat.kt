package build.raft.app.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import build.raft.app.RaftTraceIdNote
import build.raft.app.data.RaftAssistantDmCardsApi
import build.raft.app.data.ScreenError
import build.raft.app.data.WeChatBinding
import build.raft.app.data.WeChatDisconnectResult
import build.raft.app.data.WeChatStatus
import build.raft.app.data.WeChatStatusResult
import build.raft.app.model.ServerId
import build.raft.app.resources.*
import build.raft.app.secondFactorRecoveryLocalTime
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDescriptionList
import build.raft.designsystem.components.RaftDescriptionListDirection
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.components.RaftStatusVariant
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// The owner's WeChat row of their assistant's profile, and what it opens (web
// assistant/AssistantOwnerSection.tsx `assistant-settings-wechat`, WeChatConnectDialog.tsx,
// WeChatConnectionView.tsx, WeChatDisconnectConfirm.tsx, wechatClient.ts). Contract:
// `GET servers/:id/assistant/wechat/status` and `DELETE .../wechat/binding?bindingId=`
// (botiverse/slock packages/server/src/routes/assistantWeChat.ts; RaftAssistantDmCardsApi).
//
// Connecting is offered where web offers it (wechatConnectHere.ts): a window wide enough for the
// non-phone layout (tablet, unfolded foldable, desktop; [threadAssistantPanelWide], from the app's
// wide breakpoint, where web's is its `md`) shows
// the QR code flow (AssistantWeChatConnect*.kt), carrying the attempt's binding cookie by hand
// since the app keeps no cookies. At phone width the QR code would have to be scanned with WeChat
// on this very phone (web `phone`): the dialog says to connect from a computer, as web's phone
// dialog does. Disconnecting works everywhere.

/**
 * The person's WeChat connection in one server for the owner's section (web `useWeChatStatus`,
 * `refreshWeChatStatus`, `disconnectWeChat`). [status] is null until the first answer. Reads
 * are single flight and a failed one keeps what was known; a disconnect is single flight and
 * runs in [scope], so closing the dialog does not cancel one the server may already have done.
 * Main-thread confined.
 */
internal class AssistantWeChatStore(
    private val api: RaftAssistantDmCardsApi,
    private val serverId: String,
    private val scope: CoroutineScope,
) {
    private val mutableStatus = MutableStateFlow<WeChatStatusResult?>(null)
    val status: StateFlow<WeChatStatusResult?> = mutableStatus.asStateFlow()

    private val mutableDisconnecting = MutableStateFlow(false)
    val disconnecting: StateFlow<Boolean> = mutableDisconnecting.asStateFlow()

    private var read: Deferred<Unit>? = null
    private var disconnect: Deferred<WeChatDisconnectResult>? = null

    /** Reads the status again (web: each time the section opens); joins a read on its way. */
    suspend fun refresh() {
        val running = read?.takeIf { it.isActive }
        if (running != null) return running.await()
        val next =
            scope.async {
                val result = api.weChatStatus(ServerId(serverId))
                mutableStatus.update { known ->
                    // Web `keepOnError`: a failed refresh keeps a known status.
                    if (result is WeChatStatusResult.Error && known is WeChatStatusResult.Ready) known else result
                }
            }
        read = next
        next.await()
    }

    /**
     * Disconnects [bindingId] (only that one: a newer connection stays). A second confirm while
     * one is on its way waits for the same answer. Gone already: the binding is cleared; replaced
     * by a newer one: the status is read again.
     */
    suspend fun disconnect(bindingId: String): WeChatDisconnectResult {
        val running = disconnect?.takeIf { it.isActive }
        if (running != null) return running.await()
        mutableDisconnecting.value = true
        val next =
            scope.async {
                try {
                    val result = api.disconnectWeChat(ServerId(serverId), bindingId)
                    when (result) {
                        WeChatDisconnectResult.Disconnected -> clearBinding()
                        is WeChatDisconnectResult.AlreadyEnded -> if (result.bindingChanged) scope.launch { refresh() } else clearBinding()
                        is WeChatDisconnectResult.Failed -> Unit
                    }
                    result
                } finally {
                    mutableDisconnecting.value = false
                }
            }
        disconnect = next
        return next.await()
    }

    private fun clearBinding() {
        mutableStatus.update { known ->
            val status = (known as? WeChatStatusResult.Ready)?.status ?: WeChatStatus.NotHere
            WeChatStatusResult.Ready(status.copy(binding = null))
        }
    }
}

/** The status the row and the dialog read: null until known, and while it could not be read. */
internal fun WeChatStatusResult?.readyStatus(): WeChatStatus? = (this as? WeChatStatusResult.Ready)?.status

/** Web `hasWeChat`: the row shows while it may be connected here or a connection exists. */
internal fun WeChatStatus?.offersWeChatRow(): Boolean = this != null && (available || binding != null)

/** Web `binding.state === "reconnect_required"`: WeChat refused its token. */
internal val WeChatBinding.stoppedWorking: Boolean get() = state == "reconnect_required"

/**
 * Connect WeChat / WeChat (web `WeChatConnectDialog`), centered: the connection while one exists or
 * right after connecting ([WeChatConnectionBody]); else, in a window wide enough for the non-phone
 * layout ([wide], [threadAssistantPanelWide]) with [connect] available, the connect flow
 * ([AssistantWeChatConnectFlowDialog]); else where to connect instead (web's phone dialog: the
 * intro, the info banner, one pink Close). Closing stops the flow (its polls and cookie end).
 */
@Composable
internal fun AssistantWeChatDialog(
    name: String,
    status: WeChatStatus,
    disconnecting: Boolean,
    onDisconnect: suspend (bindingId: String) -> WeChatDisconnectResult,
    onToastSuccess: (String) -> Unit,
    onToastInfo: (String) -> Unit,
    onClose: () -> Unit,
    connect: AssistantWeChatConnectStore? = null,
    wide: Boolean = threadAssistantPanelWide(),
) {
    val step = connect?.step?.collectAsState()?.value ?: WeChatConnectStep.Disclosure
    // Reconnect from the connection view goes back into the flow.
    var reconnecting by remember { mutableStateOf(false) }
    val binding = status.binding
    val justConnected = step is WeChatConnectStep.Connected
    val canConnect = wide && connect != null
    val close = {
        connect?.stop()
        onClose()
    }
    if (justConnected || (binding != null && !reconnecting)) {
        WeChatConnectionDialog(
            name = name,
            binding = binding,
            available = status.available,
            justConnected = justConnected,
            disconnecting = disconnecting,
            onDisconnect = onDisconnect,
            onReconnect =
                if (canConnect && status.available) {
                    {
                        reconnecting = true
                        connect?.start()
                    }
                } else {
                    null
                },
            onToastSuccess = onToastSuccess,
            onToastInfo = onToastInfo,
            onClose = close,
        )
        return
    }
    if (connect == null || !canConnect) {
        RaftDialog(
            onDismissRequest = close,
            title = stringResource(Res.string.assistant_wechat_connect_title),
            modifier = Modifier.testTag("wechat-dialog"),
            description = stringResource(Res.string.assistant_wechat_intro, name),
            closeLabel = stringResource(Res.string.common_close),
            footer = {
                RaftButton(
                    text = stringResource(Res.string.common_close),
                    onClick = close,
                    modifier = Modifier.testTag("wechat-close"),
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg,
                )
            },
        ) {
            WeChatPhoneBlockedBody()
        }
        return
    }
    AssistantWeChatConnectFlowDialog(name = name, step = step, connect = connect, onClose = close)
}

/** Web `WeChatConnectionView` in its dialog: the body, one pink Done, and the disconnect confirm. */
@Composable
private fun WeChatConnectionDialog(
    name: String,
    binding: WeChatBinding?,
    available: Boolean,
    justConnected: Boolean,
    disconnecting: Boolean,
    onDisconnect: suspend (bindingId: String) -> WeChatDisconnectResult,
    onReconnect: (() -> Unit)?,
    onToastSuccess: (String) -> Unit,
    onToastInfo: (String) -> Unit,
    onClose: () -> Unit,
) {
    var confirming by remember(binding?.bindingId) { mutableStateOf(false) }
    var failure by remember(binding?.bindingId) { mutableStateOf<ScreenError?>(null) }
    val scope = rememberCoroutineScope()
    val disconnectedText = stringResource(Res.string.assistant_wechat_disconnected)
    val endedText = stringResource(Res.string.assistant_wechat_notice_ended_body)
    RaftDialog(
        onDismissRequest = onClose,
        title = stringResource(Res.string.assistant_wechat_connection_title),
        modifier = Modifier.testTag("wechat-dialog"),
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            RaftButton(
                text = stringResource(Res.string.common_done),
                onClick = onClose,
                modifier = Modifier.testTag("wechat-done"),
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg,
            )
        },
    ) {
        WeChatConnectionBody(
            name = name,
            binding = binding,
            available = available,
            justConnected = justConnected,
            onReconnect = onReconnect,
            onDisconnect = {
                failure = null
                confirming = true
            },
        )
    }
    if (confirming && binding != null) {
        WeChatDisconnectConfirm(
            name = name,
            busy = disconnecting,
            failure = failure,
            onDismiss = { if (!disconnecting) confirming = false },
            onConfirm = {
                if (!disconnecting) {
                    failure = null
                    scope.launch {
                        // The connection this view shows: a newer one made meanwhile stays.
                        when (val result = onDisconnect(binding.bindingId)) {
                            WeChatDisconnectResult.Disconnected -> {
                                confirming = false
                                onToastSuccess(disconnectedText)
                                onClose()
                            }
                            is WeChatDisconnectResult.AlreadyEnded -> {
                                confirming = false
                                onToastInfo(endedText)
                            }
                            // Said in the dialog, which stays open to try again (web DS Feedback).
                            is WeChatDisconnectResult.Failed -> failure = result.error
                        }
                    }
                }
            },
        )
    }
}

/**
 * Web `WeChatConnectionView`'s body: right after connecting, a success banner (once); a stopped
 * connection's banner, with Reconnect where connecting works here ([onReconnect], a wide window),
 * else where to reconnect (a computer, as web's phone view); the state and since when, what it
 * does, and the Disconnect row. [binding] is null right after connecting when the status could
 * not be read again.
 */
@Composable
internal fun WeChatConnectionBody(
    name: String,
    binding: WeChatBinding?,
    available: Boolean,
    onDisconnect: () -> Unit,
    justConnected: Boolean = false,
    onReconnect: (() -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    val stopped = binding?.stoppedWorking == true
    Column(Modifier.fillMaxWidth().testTag("wechat-connection"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (justConnected) {
            RaftBanner(
                title = stringResource(Res.string.assistant_wechat_connected_now),
                description = stringResource(Res.string.assistant_wechat_try_it, name),
                status = RaftBannerStatus.Success,
                size = RaftBannerSize.Sm,
                modifier = Modifier.testTag("wechat-connected-banner"),
            )
        } else if (stopped) {
            RaftBanner(
                title = stringResource(Res.string.assistant_wechat_reconnect_title),
                description =
                    stringResource(
                        if (available && onReconnect == null) Res.string.assistant_wechat_reconnect_body_phone else Res.string.assistant_wechat_reconnect_body,
                        name,
                    ),
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm,
                modifier = Modifier.testTag("wechat-reconnect-banner"),
                action =
                    if (onReconnect != null) {
                        {
                            RaftButton(
                                text = stringResource(Res.string.assistant_wechat_reconnect),
                                onClick = onReconnect,
                                modifier = Modifier.testTag("wechat-reconnect"),
                                variant = RaftButtonVariant.Default,
                                size = RaftButtonSize.Md,
                            )
                        }
                    } else {
                        null
                    },
            )
        }
        if (binding != null) {
            val since = binding.connectedAt.takeIf(String::isNotBlank)?.let { secondFactorRecoveryLocalTime(it) }
            val stateLabel = stringResource(if (stopped) Res.string.assistant_wechat_status_stopped else Res.string.assistant_wechat_status_active)
            RaftDescriptionList(direction = RaftDescriptionListDirection.Horizontal) {
                Item(stringResource(Res.string.assistant_wechat_status_label)) {
                    Row(Modifier.testTag("wechat-connection-state"), verticalAlignment = Alignment.CenterVertically) {
                        RaftStatusDot(variant = if (stopped) RaftStatusVariant.Warning else RaftStatusVariant.Success)
                        RaftText(
                            stateLabel,
                            Modifier.padding(start = 8.dp),
                            style = RaftTheme.typography.callout,
                            color = colors.foreground,
                        )
                    }
                }
                if (since != null) Item(stringResource(Res.string.assistant_wechat_since_label), since, mono = true)
            }
        }
        RaftText(
            stringResource(Res.string.assistant_wechat_connected_body, name),
            style = RaftTheme.typography.subhead,
            color = colors.foregroundMuted,
        )
        if (binding != null) {
            RaftListRowGroup(Modifier.fillMaxWidth()) {
                RaftListRow(
                    title = stringResource(Res.string.assistant_wechat_disconnect_row_title),
                    modifier = Modifier.testTag("wechat-disconnect-row"),
                    variant = RaftListRowVariant.Plain,
                    description = stringResource(Res.string.assistant_wechat_disconnect_row_body, name),
                    wrapDescription = true,
                    trailing = {
                        RaftButton(
                            text = stringResource(Res.string.assistant_wechat_disconnect),
                            onClick = onDisconnect,
                            modifier = Modifier.testTag("wechat-disconnect"),
                            variant = RaftButtonVariant.Default,
                            size = RaftButtonSize.Md,
                        )
                    },
                )
            }
        }
    }
}

/**
 * Disconnect WeChat? (web `WeChatDisconnectConfirm`): orange, since scanning again connects it
 * back. A failure is said here, with its trace ID, and the dialog stays to try again.
 */
@Composable
internal fun WeChatDisconnectConfirm(
    name: String,
    busy: Boolean,
    failure: ScreenError?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    RaftDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.assistant_wechat_disconnect_title),
        modifier = Modifier.testTag("wechat-disconnect-confirm"),
        size = RaftDialogSize.Sm,
        dismissOnClickOutside = !busy,
        footer = {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
                enabled = !busy,
            )
            RaftButton(
                text = stringResource(Res.string.assistant_wechat_disconnect),
                onClick = onConfirm,
                modifier = Modifier.testTag("wechat-disconnect-confirm-action"),
                variant = RaftButtonVariant.Warning,
                size = RaftButtonSize.Lg,
                loading = busy,
                loadingLabel = stringResource(Res.string.assistant_wechat_disconnecting),
            )
        },
    ) {
        WeChatDisconnectConfirmBody(name, failure)
    }
}

/** The confirm's body: a failed disconnect (with its trace ID) over what disconnecting does. */
@Composable
internal fun WeChatDisconnectConfirmBody(name: String, failure: ScreenError?) {
    if (failure != null) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RaftBanner(
                description = stringResource(Res.string.assistant_wechat_disconnect_failed),
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm,
                modifier = Modifier.testTag("wechat-disconnect-error"),
            )
            RaftTraceIdNote(failure.traceId)
        }
    }
    RaftText(
        stringResource(Res.string.assistant_wechat_disconnect_body, name),
        style = RaftTheme.typography.subhead,
        color = RaftTheme.colors.foregroundMuted,
    )
}

/** Web `Blocked` with `block="phone"`: where to connect instead (the dialog's intro is its description). */
@Composable
internal fun WeChatPhoneBlockedBody() {
    RaftBanner(
        description = stringResource(Res.string.assistant_wechat_phone_blocked),
        status = RaftBannerStatus.Info,
        size = RaftBannerSize.Sm,
        modifier = Modifier.testTag("wechat-phone-blocked"),
    )
}
