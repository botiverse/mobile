package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant

/**
 * Web `ArchivedNameCollisionBanner.tsx` (slock #12614): the warning for a channel name an
 * archived channel holds. Open Channel goes to the archived channel (read-only); Unarchive
 * restores it, offered only to whoever may unarchive it; Change Name clears the name for
 * another. [showHint] adds the line saying what to do (Create Channel; Create Joint Channel
 * leaves it out, as on the web).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArchivedNameCollisionBanner(
    archivedChannelName: String,
    canUnarchive: Boolean,
    unarchiving: Boolean,
    onUnarchive: () -> Unit,
    onOpen: () -> Unit,
    onChangeName: () -> Unit,
    modifier: Modifier = Modifier,
    showHint: Boolean = true,
) {
    val held = stringResource(Res.string.joint_create_archived_name_held, archivedChannelName)
    val hint =
        if (canUnarchive) {
            stringResource(Res.string.channel_create_archived_can_manage)
        } else {
            stringResource(Res.string.channel_create_archived_cannot_manage)
        }
    RaftBanner(
        title = if (showHint) held else null,
        description = AnnotatedString(if (showHint) hint else held),
        status = RaftBannerStatus.Warning,
        size = RaftBannerSize.Sm,
        modifier = modifier,
    ) {
        // Up to three buttons: they wrap onto a second line, aligned to the end, rather than
        // run past the banner on a phone (web `Banner` actions wrap the same way). The banner's
        // own action row does not wrap, so they ride in its content slot.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (canUnarchive) {
                RaftButton(
                    text =
                        if (unarchiving) {
                            stringResource(Res.string.channel_create_unarchiving)
                        } else {
                            stringResource(Res.string.joint_create_unarchive)
                        },
                    onClick = onUnarchive,
                    enabled = !unarchiving,
                )
            }
            // Web: outline when it is the only way forward, ghost next to Unarchive.
            RaftButton(
                text = stringResource(Res.string.channel_create_open_archived),
                onClick = onOpen,
                enabled = !unarchiving,
                variant = if (canUnarchive) RaftButtonVariant.Ghost else RaftButtonVariant.Default,
            )
            RaftButton(
                text = stringResource(Res.string.joint_create_change_name),
                onClick = onChangeName,
                variant = RaftButtonVariant.Ghost,
            )
        }
    }
}
