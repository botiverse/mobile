package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import build.raft.app.model.RaftSlackSyncProblem
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import org.jetbrains.compose.resources.StringResource

/** The web's `data-testid`s of the badge (features/channel/ChannelSlackSyncBadge.tsx). */
internal object RaftSlackSyncBadgeTestTags {
    const val Paused = "channel-slack-sync-paused"
    const val Waiting = "channel-slack-sync-waiting"
    const val Stalled = "channel-slack-sync-stalled"
}

internal fun RaftSlackSyncProblem.badgeTestTag(): String =
    when (this) {
        RaftSlackSyncProblem.Paused -> RaftSlackSyncBadgeTestTags.Paused
        RaftSlackSyncProblem.Waiting -> RaftSlackSyncBadgeTestTags.Waiting
        RaftSlackSyncProblem.Stalled -> RaftSlackSyncBadgeTestTags.Stalled
    }

/** The badge's label (web `message.chatPanel.slackSyncPaused` / `slackSyncWaiting` / `slackSyncStalled`). */
internal fun RaftSlackSyncProblem.badgeLabel(): StringResource =
    when (this) {
        RaftSlackSyncProblem.Paused -> Res.string.channel_slack_sync_paused
        RaftSlackSyncProblem.Waiting -> Res.string.channel_slack_sync_waiting
        RaftSlackSyncProblem.Stalled -> Res.string.channel_slack_sync_stalled
    }

/**
 * A Slack channel whose sync stopped (botiverse/slock e60211734
 * packages/web/src/features/channel/ChannelSlackSyncBadge.tsx): the soft warning Badge, Sync paused,
 * Sync waiting or Sync blocked, after the channel's name in its header and, since #16042, on its
 * sidebar row, so a stopped sync is seen without opening the channel (raft-design channel.md). Nothing
 * while it syncs ([problem] null). The web also gives the badge a tooltip with the reason; the app
 * has no hover, so it shows the label alone.
 */
@Composable
internal fun RaftSlackSyncBadge(problem: RaftSlackSyncProblem?, modifier: Modifier = Modifier) {
    if (problem == null) return
    RaftBadge(
        text = stringResource(problem.badgeLabel()),
        appearance = RaftBadgeAppearance.Soft,
        variant = RaftBadgeVariant.Warning,
        modifier = modifier.testTag(problem.badgeTestTag()),
    )
}
