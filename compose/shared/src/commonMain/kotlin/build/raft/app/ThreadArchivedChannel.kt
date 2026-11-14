package build.raft.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant

internal object ThreadArchivedChannelTestTags {
    const val HeaderBadge = "thread-archived-badge"
    const val Notice = "thread-archived-notice"
    const val UnarchiveAction = "thread-unarchive-action"
}

/**
 * Unarchive from an archived channel's banner (web ChatPanel archived banner): offered only to a
 * viewer who may archive this channel. [onUnarchive] takes what to run once the server said yes
 * (the page reloads itself).
 */
internal class ThreadPageArchivedChannel(
    val canUnarchive: Boolean,
    val unarchiving: Boolean,
    val onUnarchive: (onUnarchived: () -> Unit) -> Unit,
)

/**
 * The page shows an archived channel, or a reply thread in one: web ChatPanel `isArchived` puts
 * the archived banner where the composer (or the Join button) would be, for members and
 * non-members alike. The server refuses every write there (`channel_archived`), a thread's reply
 * included, so a reply thread in an archived channel gets the same banner.
 */
internal fun threadFooterShowsArchived(payload: RaftThreadScreenPayload): Boolean =
    payload.channelArchived &&
        (payload.surfaceKind == RaftThreadSurfaceKind.Channel || payload.surfaceKind == RaftThreadSurfaceKind.ReplyThread)

/** Web ChatPanel header: a channel's own page carries the Archived badge beside its name. */
internal fun threadHeaderShowsArchivedBadge(payload: RaftThreadScreenPayload, directoryArchived: Boolean): Boolean =
    payload.surfaceKind == RaftThreadSurfaceKind.Channel && (payload.channelArchived || directoryArchived)

/**
 * Who may Unarchive: the channel's own `archiveChannels` capability when the load carried it
 * (web `effectiveChannelCapabilities.archiveChannels`), else the server role, as Channel settings
 * decides without capabilities.
 */
internal fun threadArchivedChannelCanUnarchive(payload: RaftThreadScreenPayload, roleMayArchive: Boolean): Boolean =
    payload.channelCanUnarchive ?: roleMayArchive

/** Web ChatPanel `message.chatPanel.archived`: the muted soft badge after the channel name. */
@Composable
internal fun ThreadArchivedChannelBadge(modifier: Modifier = Modifier) {
    RaftBadge(
        text = stringResource(Res.string.channel_settings_status_archived),
        appearance = RaftBadgeAppearance.Soft,
        variant = RaftBadgeVariant.Muted,
        modifier = modifier.testTag(ThreadArchivedChannelTestTags.HeaderBadge),
    )
}

/**
 * Web ChatPanel archived banner: "This channel is archived." and, for a viewer who may archive the
 * channel, Unarchive. Also an archived Lounge's banner (its own test tags).
 */
@Composable
internal fun ThreadArchivedChannelNotice(
    canUnarchive: Boolean,
    unarchiving: Boolean,
    onUnarchive: () -> Unit,
    modifier: Modifier = Modifier,
    noticeTag: String = ThreadArchivedChannelTestTags.Notice,
    unarchiveTag: String = ThreadArchivedChannelTestTags.UnarchiveAction,
) {
    RaftBanner(
        description = stringResource(Res.string.thread_archived_notice),
        status = RaftBannerStatus.Warning,
        modifier = modifier.fillMaxWidth().testTag(noticeTag),
        action =
            if (canUnarchive) {
                {
                    RaftButton(
                        text = stringResource(Res.string.thread_archived_unarchive),
                        onClick = onUnarchive,
                        variant = RaftButtonVariant.Default,
                        loading = unarchiving,
                        modifier = Modifier.testTag(unarchiveTag),
                    )
                }
            } else {
                null
            },
    )
}
