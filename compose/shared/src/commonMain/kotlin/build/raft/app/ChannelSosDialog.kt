package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ChannelSosState
import build.raft.app.data.ChannelSosIntent
import build.raft.app.data.ChannelSosPhase
import build.raft.app.data.ChannelSosStore
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftTextarea
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import com.tencent.tmm.kmmresource.compose.stringResource

/**
 * Web `SOSDialog`: confirm stopping every agent in the channel, then offer
 * "Keep Stopped" or "Resume All" with guidance the agents get as their resume
 * prompt. A resume that misses agents keeps the dialog (and the text) open.
 */
@Composable
internal fun ChannelSosDialog(
    channelName: String,
    store: ChannelSosStore,
    onClose: () -> Unit
) {
    val state by store.state.collectAsState()
    LaunchedEffect(state.closed) {
        if (state.closed) onClose()
    }
    val busy = state.stopping || state.resuming
    RaftDesignSystemTheme {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    RaftDialog(
        onDismissRequest = { if (!busy) onClose() },
        title =
            stringResource(
                if (state.phase == ChannelSosPhase.Confirm) MR.strings.channel_sos_stop_all_agents else MR.strings.channel_sos_agents_stopped
            ),
        size = RaftDialogSize.Sm,
        dismissOnClickOutside = !busy,
        closeLabel = stringResource(MR.strings.common_close),
        footer = { ChannelSosDialogFooter(state, busy, store, onClose) },
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md)) {
            if (state.phase == ChannelSosPhase.Confirm) {
                RaftText(
                    text = stringResource(MR.strings.channel_sos_confirm_warning, channelName),
                    style = typography.subhead,
                    color = colors.foregroundMuted
                )
            } else {
                RaftBanner(
                    description = stringResource(MR.strings.channel_sos_all_stopped),
                    status = RaftBannerStatus.Success,
                    size = RaftBannerSize.Sm
                )
                RaftText(
                    text = stringResource(MR.strings.channel_sos_guidance_hint),
                    style = typography.subhead,
                    color = colors.foregroundMuted
                )
                state.shortfall?.let { shortfall ->
                    val lines =
                        buildList {
                            if (shortfall.starting > 0) add(channelSosStillStartingText(shortfall.starting))
                            if (shortfall.failed > 0) add(channelSosResumeFailedText(shortfall.failed))
                        }
                    RaftBanner(
                        description = lines.joinToString("\n"),
                        status = RaftBannerStatus.Warning,
                        size = RaftBannerSize.Sm
                    )
                }
                RaftTextarea(
                    value = state.guidance,
                    onValueChange = { store.dispatch(ChannelSosIntent.UpdateGuidance(it)) },
                    placeholder = stringResource(MR.strings.channel_sos_guidance_placeholder),
                    enabled = !state.resuming,
                    minHeight = 104.dp,
                )
            }
            if (state.errorCode != null) {
                RaftBanner(
                    description =
                        stringResource(
                            if (state.phase == ChannelSosPhase.Confirm) MR.strings.channel_sos_stop_failed else MR.strings.channel_sos_resume_error
                        ),
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Sm
                )
            }
        }
    }
    }
}

@Composable
private fun RowScope.ChannelSosDialogFooter(
    state: ChannelSosState,
    busy: Boolean,
    store: ChannelSosStore,
    onClose: () -> Unit,
) {
            if (state.phase == ChannelSosPhase.Confirm) {
                RaftButton(
                    text = stringResource(MR.strings.common_cancel),
                    onClick = onClose,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg,
                    enabled = !busy
                )
                RaftButton(
                    text = stringResource(MR.strings.channel_sos_stop_action),
                    onClick = { store.dispatch(ChannelSosIntent.Stop) },
                    variant = RaftButtonVariant.Warning,
                    size = RaftButtonSize.Lg,
                    loading = state.stopping,
                    loadingLabel = stringResource(MR.strings.channel_sos_stopping)
                )
            } else {
                RaftButton(
                    text = stringResource(MR.strings.channel_sos_keep_stopped),
                    onClick = onClose,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg,
                    enabled = !state.resuming
                )
                RaftButton(
                    text = stringResource(MR.strings.channel_sos_resume_all),
                    onClick = { store.dispatch(ChannelSosIntent.Resume) },
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg,
                    enabled = state.canResume,
                    loading = state.resuming,
                    loadingLabel = stringResource(MR.strings.channel_sos_resuming)
                )
            }
}

@Composable
private fun channelSosStillStartingText(count: Int): String =
    if (count == 1) {
        stringResource(MR.strings.channel_sos_resume_still_starting_one)
    } else {
        stringResource(MR.strings.channel_sos_resume_still_starting_other, count)
    }

@Composable
private fun channelSosResumeFailedText(count: Int): String =
    if (count == 1) {
        stringResource(MR.strings.channel_sos_resume_failed_one)
    } else {
        stringResource(MR.strings.channel_sos_resume_failed_other, count)
    }
