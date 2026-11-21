package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import build.raft.app.data.RaftBackgroundKillNotice
import build.raft.app.data.RaftBackgroundKillNoticeHostRegistry
import build.raft.app.data.RaftBackgroundWhitelistGuide
import build.raft.app.data.RaftMinBuildGate
import build.raft.app.data.RaftMinBuildGateDecision
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding

internal const val RAFT_BACKGROUND_KILL_NOTICE_BANNER_TAG = "raft_background_kill_notice_banner"
internal const val RAFT_BACKGROUND_KILL_NOTICE_DISMISS_TAG = "raft_background_kill_notice_dismiss"
internal const val RAFT_BACKGROUND_KILL_NOTICE_SETTINGS_TAG = "raft_background_kill_notice_open_settings"

/**
 * Pure visibility predicate: show the background-kill whitelist notice only when a notice is active,
 * the shell is online, and no minimum-build gate banner/block is currently visible ("at most one
 * banner in one place").
 */
internal fun shouldShowBackgroundKillNotice(
    notice: RaftBackgroundKillNotice?,
    networkStatus: RaftAppShellNetworkStatus,
    minBuildGateDecision: RaftMinBuildGateDecision,
    dismissedMinBuildBannerThreshold: Long? = null,
): Boolean {
    if (notice == null || networkStatus == RaftAppShellNetworkStatus.Offline) return false
    val minBuildBannerVisible =
        when (minBuildGateDecision) {
            RaftMinBuildGateDecision.None -> false
            is RaftMinBuildGateDecision.Banner -> dismissedMinBuildBannerThreshold != minBuildGateDecision.threshold
            is RaftMinBuildGateDecision.Block -> true
        }
    return !minBuildBannerVisible
}

/**
 * Signed-in app-shell slot for the Android background-kill whitelist notice.
 * Kept out of [RaftAppShellReadyContent] to preserve JVM method size headroom.
 */
@Composable
internal fun rememberRaftBackgroundKillNoticeBannerSlot(
    networkStatus: RaftAppShellNetworkStatus,
): (@Composable () -> Unit)? {
    val host = RaftBackgroundKillNoticeHostRegistry.host ?: return null
    val notice by host.notice.collectAsState()
    val minBuildDecision by RaftMinBuildGate.decision.collectAsState()
    val dismissedMinBuildBanner by RaftMinBuildGate.dismissedBanner.collectAsState()
    if (!shouldShowBackgroundKillNotice(notice, networkStatus, minBuildDecision, dismissedMinBuildBanner)) return null
    val activeNotice = notice ?: return null
    return remember(activeNotice, host) {
        {
            RaftBackgroundKillNoticeBanner(
                notice = activeNotice,
                onOpenSettings = { host.openVendorWhitelistSettings() },
                onDismiss = { host.dismiss() },
            )
        }
    }
}

@Composable
internal fun RaftBackgroundKillNoticeBanner(
    notice: RaftBackgroundKillNotice,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description =
        when (notice.guide) {
            RaftBackgroundWhitelistGuide.HuaweiAppLaunch -> stringResource(Res.string.background_kill_notice_body_huawei)
            RaftBackgroundWhitelistGuide.LenovoZui -> stringResource(Res.string.background_kill_notice_body_lenovo)
            RaftBackgroundWhitelistGuide.VendorSettings -> stringResource(Res.string.background_kill_notice_body_vendor)
            RaftBackgroundWhitelistGuide.AppDetails -> stringResource(Res.string.background_kill_notice_body_app_details)
        }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(RaftColor.Canvas)
                .raftSafeAreaHorizontalPadding()
                .padding(horizontal = RaftAppSpacing.Md, vertical = RaftAppSpacing.Sm),
        contentAlignment = Alignment.TopCenter,
    ) {
        RaftBanner(
            title = stringResource(Res.string.background_kill_notice_title),
            description = description,
            status = RaftBannerStatus.Warning,
            modifier = Modifier.testTag(RAFT_BACKGROUND_KILL_NOTICE_BANNER_TAG).fillMaxWidth(),
            // raft-ui Banner rule: a banner with actions closes via a ghost Dismiss before a Default
            // action button (never pink, never both action and onDismiss X).
            action = {
                RaftButton(
                    text = stringResource(Res.string.common_dismiss),
                    onClick = onDismiss,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Md,
                    modifier = Modifier.testTag(RAFT_BACKGROUND_KILL_NOTICE_DISMISS_TAG),
                )
                RaftButton(
                    text = stringResource(Res.string.background_kill_notice_action_open_settings),
                    onClick = onOpenSettings,
                    variant = RaftButtonVariant.Default,
                    size = RaftButtonSize.Md,
                    modifier = Modifier.testTag(RAFT_BACKGROUND_KILL_NOTICE_SETTINGS_TAG),
                )
            },
            onDismiss = null,
        )
    }
}
