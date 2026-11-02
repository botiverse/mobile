package build.raft.app.components

import build.raft.app.resources.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import build.raft.app.RaftContentLucideIcon
import build.raft.app.RaftDialogFooter
import build.raft.app.RaftLucideIconName
import build.raft.app.settingsBillingLeavesRaft
import build.raft.app.settingsBillingOffersUpgrade
import build.raft.app.RaftUnifiedDialog
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/**
 * The neutral "Pro" tag web puts next to a Pro feature's title (a DS outline
 * badge; pink stays for the one action and unread counts). Shown to every
 * plan, so the entry reads the same before and after an upgrade.
 */
@Composable
internal fun RaftProBadge(modifier: Modifier = Modifier) {
    RaftBadge(
        text = stringResource(Res.string.raft_pro_badge),
        modifier = modifier,
        appearance = RaftBadgeAppearance.Outline
    )
}

/**
 * The upgrade prompt a plan without the feature gets instead of the feature
 * (web `AgentMigrationUpgradeDialog`, a ConfirmDialog with an accent
 * "Upgrade" that opens Settings > Billing). [title]/[description] name the
 * feature; the action and dismissal are shared. Where the app offers no upgrade
 * ([offersUpgrade] false: iOS view-only Billing, App Store guideline 3.1.1) it is the
 * message only, with OK.
 */
@Composable
internal fun RaftProUpgradeDialog(
    title: String,
    description: String,
    onUpgrade: () -> Unit,
    onDismiss: () -> Unit,
    offersUpgrade: Boolean = settingsBillingOffersUpgrade()
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
            if (offersUpgrade) {
                RaftButton(
                    text = stringResource(Res.string.common_cancel),
                    onClick = onDismiss,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg
                )
                RaftButton(
                    text = stringResource(Res.string.raft_pro_upgrade),
                    onClick = onUpgrade,
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg,
                    // raft-ui 0.26 (#758): where Billing opens the browser, leaving Raft: ↗.
                    trailingIcon = raftBillingLeavesRaftTrailingIcon()
                )
            } else {
                RaftButton(
                    text = stringResource(Res.string.common_ok),
                    onClick = onDismiss,
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg
                )
            }
        }
    }
}

/** The trailing ↗ of a button that opens Billing, where Billing leaves Raft (iOS); null elsewhere. */
internal fun raftBillingLeavesRaftTrailingIcon(
    leavesRaft: Boolean = settingsBillingLeavesRaft(),
): (@Composable () -> Unit)? =
    if (leavesRaft) {
        { RaftContentLucideIcon(RaftLucideIconName.ArrowUpRight, size = 18.dp) }
    } else {
        null
    }

/** The title and description of a Pro gate prompt. */
internal data class RaftProGateCopy(val title: StringResource, val description: StringResource)

/**
 * Agent migration's Pro gate. Where the app offers no upgrade (iOS view-only Billing) the copy is
 * a plain statement ("Migrating agents needs a Pro plan"), not an "Upgrade to Pro" call to action
 * that App Review could read as leading to an external purchase (guideline 3.1.1).
 */
internal fun raftProMigrationGateCopy(offersUpgrade: Boolean = settingsBillingOffersUpgrade()): RaftProGateCopy =
    if (offersUpgrade) {
        RaftProGateCopy(Res.string.raft_pro_migration_title, Res.string.raft_pro_migration_description)
    } else {
        RaftProGateCopy(Res.string.raft_pro_migration_title_neutral, Res.string.raft_pro_migration_description_neutral)
    }

/** The Pro server theme gate's description (`%1$s`: the server name); neutral like [raftProMigrationGateCopy]. */
internal fun raftProThemeGateDescription(offersUpgrade: Boolean = settingsBillingOffersUpgrade()): StringResource =
    if (offersUpgrade) {
        Res.string.settings_server_theme_pro_description
    } else {
        Res.string.settings_server_theme_pro_description_neutral
    }
