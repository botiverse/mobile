package build.raft.app

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant

/**
 * Free-plan external channel freeze notice (web `ChannelLimitBanners.tsx` `ExternalFreezeNotice`,
 * botiverse/slock #15653): shown above the composer while the freeze deadline is still ahead.
 * Action label follows plan and server role so label and click outcome agree; on iOS view-only
 * billing, falls back to "View your plan".
 */
@Composable
internal fun ThreadExternalFreezeNoticeBanner(
    deadlineIso: String,
    plan: String?,
    role: String?,
    onOpenUpgrade: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val deadlineLabel = raftFormatFreezeDeadline(deadlineIso)
    RaftUiBanner(
        status = RaftUiBannerStatus.Warning,
        description = stringResource(Res.string.external_freeze_notice, deadlineLabel),
        size = RaftUiBannerSize.Medium,
        iconName = RaftLucideIconName.TriangleAlert,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        action = {
            ThreadExternalFreezeActionButton(
                plan = plan,
                role = role,
                onOpenUpgrade = onOpenUpgrade,
            )
        },
    )
}

/**
 * Free-plan external channel frozen banner (web `ChannelLimitBanners.tsx` `ExternalFrozenBanner`,
 * botiverse/slock #15653): replaces the composer once `externalFreezeAt` has passed, on both
 * channels and reply threads in them. Body copy varies by role (owner/admin vs member).
 */
@Composable
internal fun ThreadExternalFrozenBanner(
    serverName: String,
    plan: String?,
    role: String?,
    onOpenUpgrade: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bodyRes =
        if (externalFrozenUsesManagerCopy(role)) {
            Res.string.external_frozen_body_manager
        } else {
            Res.string.external_frozen_body_member
        }
    RaftUiBanner(
        status = RaftUiBannerStatus.Warning,
        title = stringResource(Res.string.external_frozen_title, serverName),
        description = stringResource(bodyRes),
        size = RaftUiBannerSize.Medium,
        iconName = RaftLucideIconName.TriangleAlert,
        modifier = modifier,
        action = {
            ThreadExternalFreezeActionButton(
                plan = plan,
                role = role,
                onOpenUpgrade = onOpenUpgrade,
            )
        },
    )
}

@Composable
private fun ThreadExternalFreezeActionButton(
    plan: String?,
    role: String?,
    onOpenUpgrade: () -> Unit,
) {
    if (settingsBillingOffersUpgrade()) {
        val labelRes =
            when (externalFreezeActionKind(plan, role)) {
                ExternalFreezeActionKind.ViewBilling -> Res.string.external_freeze_action_view_billing
                ExternalFreezeActionKind.UpgradeToPro -> Res.string.external_freeze_action_upgrade_to_pro
                ExternalFreezeActionKind.AskOwner -> Res.string.external_freeze_action_ask_owner
            }
        RaftButton(
            text = stringResource(labelRes),
            onClick = onOpenUpgrade,
            variant = RaftButtonVariant.Default,
            size = RaftButtonSize.Md,
        )
    } else {
        val openPlan = LocalThreadActionCardSpecHost.current.openPlan
        build.raft.app.components.RaftViewPlanButton(onClick = openPlan)
    }
}
