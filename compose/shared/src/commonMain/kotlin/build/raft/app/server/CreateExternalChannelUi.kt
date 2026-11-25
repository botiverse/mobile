package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftDialogSheet

internal const val CreateExternalChannelReviewTag = "create-channel-external-review"
internal const val CreateExternalChannelCreatedTag = "create-channel-external-created"

/**
 * Web Create Channel's External review (`step === "invite"`): Invite people outside {server}'s
 * review for the channel about to be made. Its button reads Create and Invite until the channel
 * exists, then Invite; Back returns to step 2 until then. Skip (the sheet's close) leaves: before
 * the create nothing was made, after it onto the channel. A send that failed after the create says
 * the channel was made above the refusal, as a warning, and only once it failed
 * ([CreateExternalChannelState.showCreatedNotice]).
 */
@Composable
internal fun SharedCreateExternalChannelReview(
    state: CreateExternalChannelState,
    serverName: String,
    onCommit: () -> Unit,
    onBack: () -> Unit,
    onLeave: () -> Unit,
    onUpgrade: () -> Unit,
) {
    val invite = state.invite
    val created = state.created
    RaftDesignSystemTheme {
        RaftDialogSheet(
            onDismissRequest = { if (!invite.busy) onLeave() },
            title = stringResource(Res.string.channel_external_invite_title, serverName),
            commitLabel =
                when {
                    invite.busy -> stringResource(Res.string.channel_external_invite_sending)
                    created != null -> stringResource(Res.string.channel_external_invite_commit)
                    else -> stringResource(Res.string.channel_create_external_submit)
                },
            onCommit = onCommit,
            commitEnabled = invite.canSend,
            commitLoading = invite.busy,
            closeLabel = stringResource(Res.string.common_close),
            cancelLabel = stringResource(Res.string.channel_create_external_skip),
            closeEnabled = !invite.busy,
            onBack = if (state.canReturnToStep2) onBack else null,
            backLabel = stringResource(Res.string.channel_external_invite_back),
            modifier = Modifier.testTag(CreateExternalChannelReviewTag),
        ) {
            SharedCreateExternalChannelReviewBody(state, serverName, onUpgrade)
        }
    }
}

/** The review's content, for [SharedCreateExternalChannelReview] and the screenshot tests' window-free surface. */
@Composable
internal fun SharedCreateExternalChannelReviewBody(
    state: CreateExternalChannelState,
    serverName: String,
    onUpgrade: () -> Unit,
) {
    val invite = state.invite
    val created = state.created
    Column(Modifier.fillMaxSize()) {
        if (created != null && state.showCreatedNotice) {
            RaftBanner(
                description = stringResource(Res.string.channel_create_external_created, created.channel.name.removePrefix("#")),
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp).testTag(CreateExternalChannelCreatedTag),
            )
        }
        // A new channel is created private: no "becomes private" line, no slots before it exists.
        ExternalInviteSheetBody(
            dialog = invite,
            serverName = serverName,
            willBecomePrivate = false,
            joint = false,
            channelSlotsLeft = null,
            onTextChange = {},
            onUpgrade = onUpgrade,
        )
    }
}
