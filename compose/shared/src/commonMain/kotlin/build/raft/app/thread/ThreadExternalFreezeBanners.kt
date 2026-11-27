package build.raft.app.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.RaftColor
import build.raft.app.raftFormatFreezeDeadline
import build.raft.app.resources.*
import build.raft.app.settingsBillingOffersUpgrade
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding

/**
 * Free-plan external channel freeze notice (web `ChannelLimitBanners.tsx` `ExternalFreezeNotice`,
 * botiverse/slock #15653): shown above the composer while the freeze deadline is still ahead.
 * Action label follows plan and server role so label and click outcome agree; on iOS view-only
 * billing ([offersUpgrade] = false), uses neutral non-upgrade copy and "View your plan" (App Store
 * guideline 3.1.1).
 */
@Composable
internal fun ThreadExternalFreezeNoticeBanner(
    deadlineIso: String,
    plan: String?,
    role: String?,
    onOpenUpgrade: () -> Unit,
    modifier: Modifier = Modifier,
    offersUpgrade: Boolean = settingsBillingOffersUpgrade(),
) {
    val deadlineLabel = raftFormatFreezeDeadline(deadlineIso)
    val descriptionRes =
        if (offersUpgrade) {
            Res.string.external_freeze_notice
        } else {
            Res.string.external_freeze_notice_ios
        }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(RaftColor.Canvas)
                .raftSafeAreaHorizontalPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        RaftBanner(
            status = RaftBannerStatus.Warning,
            description = stringResource(descriptionRes, deadlineLabel),
            size = RaftBannerSize.Md,
            action = {
                ThreadExternalFreezeActionButton(
                    plan = plan,
                    role = role,
                    offersUpgrade = offersUpgrade,
                    onOpenUpgrade = onOpenUpgrade,
                )
            },
        )
    }
}

/**
 * Free-plan external channel frozen banner (web `ChannelLimitBanners.tsx` `ExternalFrozenBanner`,
 * botiverse/slock #15653): replaces the composer once `externalFreezeAt` has passed, on channels,
 * reply threads, and task conversations in them. Body copy varies by role (owner/admin vs member),
 * and on iOS view-only billing ([offersUpgrade] = false) omits upgrade prompts (App Store 3.1.1).
 */
@Composable
internal fun ThreadExternalFrozenBanner(
    serverName: String,
    plan: String?,
    role: String?,
    onOpenUpgrade: () -> Unit,
    modifier: Modifier = Modifier,
    offersUpgrade: Boolean = settingsBillingOffersUpgrade(),
) {
    val manager = externalFrozenUsesManagerCopy(role)
    val bodyRes =
        when {
            offersUpgrade && manager -> Res.string.external_frozen_body_manager
            offersUpgrade -> Res.string.external_frozen_body_member
            manager -> Res.string.external_frozen_body_ios_manager
            else -> Res.string.external_frozen_body_ios_member
        }
    RaftBanner(
        status = RaftBannerStatus.Warning,
        title = stringResource(Res.string.external_frozen_title, serverName),
        description = stringResource(bodyRes),
        size = RaftBannerSize.Md,
        modifier = modifier,
        action = {
            ThreadExternalFreezeActionButton(
                plan = plan,
                role = role,
                offersUpgrade = offersUpgrade,
                onOpenUpgrade = onOpenUpgrade,
            )
        },
    )
}

@Composable
private fun ThreadExternalFreezeActionButton(
    plan: String?,
    role: String?,
    offersUpgrade: Boolean,
    onOpenUpgrade: () -> Unit,
) {
    if (offersUpgrade) {
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
