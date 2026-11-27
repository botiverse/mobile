package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import build.raft.app.data.RaftBackgroundKillNotice
import build.raft.app.data.RaftBackgroundKillNoticeHostRegistry
import build.raft.app.data.RaftMinBuildGate
import build.raft.app.data.RaftMinBuildGateDecision
import build.raft.app.resources.*
import build.raft.app.settings.RaftSettingsLink
import build.raft.app.settings.RaftSettingsLinkTarget
import build.raft.app.settings.SETTINGS_NOTIFICATIONS_BACKGROUND_KEEP_ALIVE_ANCHOR
import build.raft.app.settings.SettingsNativeDetailPage
import build.raft.app.thread.key
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding

internal const val RAFT_BACKGROUND_KILL_NOTICE_BANNER_TAG = "raft_background_kill_notice_banner"
internal const val RAFT_BACKGROUND_KILL_NOTICE_DISMISS_TAG = "raft_background_kill_notice_dismiss"
internal const val RAFT_BACKGROUND_KILL_NOTICE_DISMISS_FOREVER_TAG = "raft_background_kill_notice_dismiss_forever"
internal const val RAFT_BACKGROUND_KILL_NOTICE_SETTINGS_TAG = "raft_background_kill_notice_open_settings"

internal val RaftBackgroundKillNoticeSettingsLink =
    RaftSettingsLink(
        serverSlug = null,
        webTab = "notifications",
        destinationKey = SettingsNativeDetailPage.Notifications.key,
        section = SETTINGS_NOTIFICATIONS_BACKGROUND_KEEP_ALIVE_ANCHOR,
    )

internal val RaftBackgroundKillNoticeSettingsTarget =
    RaftSettingsLinkTarget(
        switchToServerId = null,
        destinationKey = SettingsNativeDetailPage.Notifications.key,
    )

/**
 * Pure visibility predicate: show the background-kill notice only when a notice is active,
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
 * Signed-in app-shell slot for the Android background-kill notice.
 * Kept out of [RaftAppShellReadyContent] to preserve JVM method size headroom.
 */
@Composable
internal fun rememberRaftBackgroundKillNoticeBannerSlot(
    networkStatus: RaftAppShellNetworkStatus,
    onOpenNotificationSettings: () -> Unit,
): (@Composable () -> Unit)? {
    val host = RaftBackgroundKillNoticeHostRegistry.host ?: return null
    val notice by host.notice.collectAsState()
    val minBuildDecision by RaftMinBuildGate.decision.collectAsState()
    val dismissedMinBuildBanner by RaftMinBuildGate.dismissedBanner.collectAsState()
    if (!shouldShowBackgroundKillNotice(notice, networkStatus, minBuildDecision, dismissedMinBuildBanner)) return null
    val activeNotice = notice ?: return null
    val currentOpenNotificationSettings by rememberUpdatedState(onOpenNotificationSettings)
    return remember(activeNotice, host) {
        {
            RaftBackgroundKillNoticeBanner(
                notice = activeNotice,
                onOpenSettings = {
                    host.markSettingsOpened()
                    currentOpenNotificationSettings()
                },
                onDismiss = { host.dismiss() },
                onDismissForever = { host.dismissForever() },
                onShown = { host.markShown() },
            )
        }
    }
}

@Composable
internal fun RaftBackgroundKillNoticeBanner(
    notice: RaftBackgroundKillNotice,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    onDismissForever: () -> Unit,
    onShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // An appearance counts once the banner is really composed, never when a headless start (a
    // push, a widget, the keep-alive service) only selected the notice; the host counts once per process.
    val currentOnShown by rememberUpdatedState(onShown)
    LaunchedEffect(notice.exitTimestampMillis) { currentOnShown() }
    val description = stringResource(Res.string.background_kill_notice_body)
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
            description = AnnotatedString(description),
            status = RaftBannerStatus.Warning,
            modifier = Modifier.testTag(RAFT_BACKGROUND_KILL_NOTICE_BANNER_TAG).fillMaxWidth(),
            onDismiss = null,
        ) {
            RaftBackgroundKillNoticeActions(
                onDismissForever = onDismissForever,
                onDismiss = onDismiss,
                onOpenSettings = onOpenSettings,
            )
        }
    }
}

/**
 * raft-ui Banner rule: a banner with actions closes via ghost buttons (Don't show again, then
 * Dismiss) before a Default action button (never pink, never both action and onDismiss X).
 * Three buttons are too wide for one line on a phone: they wrap onto a second line, aligned to
 * the end, rather than run past the banner's start edge. The banner's own action row does not
 * wrap, so they ride in its content slot (as [ArchivedNameCollisionBanner]'s do).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RaftBackgroundKillNoticeActions(
    onDismissForever: () -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm),
    ) {
        RaftButton(
            text = stringResource(Res.string.background_kill_notice_action_dont_show_again),
            onClick = onDismissForever,
            variant = RaftButtonVariant.Ghost,
            size = RaftButtonSize.Md,
            modifier = Modifier.testTag(RAFT_BACKGROUND_KILL_NOTICE_DISMISS_FOREVER_TAG),
        )
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
    }
}
