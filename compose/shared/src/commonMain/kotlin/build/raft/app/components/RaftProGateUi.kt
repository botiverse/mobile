package build.raft.app.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.RaftDialogFooter
import build.raft.app.RaftUnifiedDialog
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import com.tencent.tmm.kmmresource.compose.stringResource

/**
 * The neutral "Pro" tag web puts next to a Pro feature's title (a DS outline
 * badge; pink stays for the one action and unread counts). Shown to every
 * plan, so the entry reads the same before and after an upgrade.
 */
@Composable
internal fun RaftProBadge(modifier: Modifier = Modifier) {
    RaftBadge(
        text = stringResource(MR.strings.raft_pro_badge),
        modifier = modifier,
        appearance = RaftBadgeAppearance.Outline
    )
}

/**
 * The upgrade prompt a plan without the feature gets instead of the feature
 * (web `AgentMigrationUpgradeDialog`, a ConfirmDialog with an accent
 * "Upgrade" that opens Settings > Billing). [title]/[description] name the
 * feature; the action and dismissal are shared.
 */
@Composable
internal fun RaftProUpgradeDialog(
    title: String,
    description: String,
    onUpgrade: () -> Unit,
    onDismiss: () -> Unit
) {
    RaftUnifiedDialog(
        title = title,
        onDismiss = onDismiss,
        maxWidth = 448.dp,
        titleMaxLines = 2
    ) {
        RaftText(
            text = description,
            style = RaftTheme.typography.subhead,
            color = RaftTheme.colors.foregroundMuted
        )
        RaftDialogFooter {
            RaftButton(
                text = stringResource(MR.strings.common_cancel),
                onClick = onDismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg
            )
            RaftButton(
                text = stringResource(MR.strings.raft_pro_upgrade),
                onClick = onUpgrade,
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg
            )
        }
    }
}
