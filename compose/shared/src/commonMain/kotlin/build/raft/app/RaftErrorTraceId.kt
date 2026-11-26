package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.MobileUiErrorCode
import build.raft.app.data.MobileUiErrorSurface
import build.raft.app.data.MobileUiErrorTrace
import build.raft.app.network.raftValidTraceIdOrNull
import build.raft.designsystem.components.LocalRaftCopyHandler
import build.raft.designsystem.components.LocalRaftToastHostState
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftToastIntent
import build.raft.designsystem.components.rememberRaftCopyAction
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/**
 * Inline form of the trace id under an error on a page or card (the web `TraceIdNote` on paper):
 * "Trace ID a1b2c3d4…" in the hint footnote and a ghost copy icon button that copies the whole id
 * through the app's copy action (host pasteboard + "Copied"). Draws nothing without a [traceId].
 */
@Composable
internal fun RaftTraceIdNote(
    traceId: String?,
    modifier: Modifier = Modifier,
    uiErrorSurface: MobileUiErrorSurface = MobileUiErrorSurface.ScreenError,
    uiErrorRoute: String? = null,
    uiErrorCode: MobileUiErrorCode = MobileUiErrorCode.Unknown,
) {
    val id = raftValidTraceIdOrNull(traceId) ?: return
    LaunchedEffect(id, uiErrorSurface, uiErrorRoute, uiErrorCode) {
        MobileUiErrorTrace.record(
            surface = uiErrorSurface,
            route = uiErrorRoute,
            errorCode = uiErrorCode,
            requestTraceId = id,
        )
    }
    val copy = rememberRaftCopyAction()
    Row(
        modifier.testTag(RaftTraceIdNoteTestTag),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftText(
            stringResource(Res.string.error_trace_id_label),
            style = RaftTheme.typography.footnote,
            color = RaftTheme.colors.foregroundHint,
        )
        RaftText(
            raftShortTraceId(id),
            style = RaftTheme.typography.monoMeta,
            color = RaftTheme.colors.foregroundHint,
        )
        RaftIconButton(
            onClick = { copy(id) },
            tooltip = stringResource(Res.string.error_trace_id_copy),
            variant = RaftButtonVariant.Ghost,
            size = RaftIconButtonSize.Md,
        ) {
            RaftSlotIcon(RaftLucideIconName.Copy)
        }
    }
}

internal const val RaftTraceIdNoteTestTag: String = "trace-id-note"

/**
 * Posts an error toast through [LocalRaftToastHostState] carrying [traceId] with a Copy action
 * when a valid `X-Trace-Id` is present, matching [build.raft.app.base.BridgeModule.errorToast]
 * for Compose surfaces that post directly to [LocalRaftToastHostState].
 */
@Composable
internal fun rememberRaftErrorToast(): (title: String, traceId: String?, intent: RaftToastIntent) -> Unit {
    val toastHost = LocalRaftToastHostState.current
    val hasCopyHandler = LocalRaftCopyHandler.current != null
    val copy = rememberRaftCopyAction()
    val traceIdFormat = stringResource(Res.string.error_trace_id_value, "%s")
    val copyLabel = stringResource(Res.string.common_copy)
    val copiedToast = stringResource(Res.string.error_trace_id_copied)
    return remember(toastHost, hasCopyHandler, copy, traceIdFormat, copyLabel, copiedToast) {
        { title, rawTraceId, intent ->
            val id = raftValidTraceIdOrNull(rawTraceId)
            if (intent == RaftToastIntent.Error) {
                MobileUiErrorTrace.record(
                    surface = MobileUiErrorSurface.Toast,
                    route = null,
                    errorCode = MobileUiErrorCode.Unknown,
                    requestTraceId = id,
                )
            }
            toastHost?.show(
                title = title,
                footnote = id?.let { traceIdFormat.replace("%s", raftShortTraceId(it)) },
                intent = intent,
                actionLabel = if (id != null) copyLabel else null,
                onAction =
                    id?.let { validId ->
                        {
                            copy(validId)
                            // LocalRaftCopyHandler (RaftProvideDesignSystemCopy) already posts a brief
                            // "Copied" toast; only show a fallback confirmation when no host handler is provided.
                            if (!hasCopyHandler) {
                                toastHost.show(copiedToast, durationMillis = 1_500L)
                            }
                        }
                    },
            )
        }
    }
}
