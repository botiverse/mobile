package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.compat.ui.LocalRaftPageActive
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.DataResult
import build.raft.app.data.RaftAppModalPromptSlot
import build.raft.app.data.RaftAppUpdateCoordinator
import build.raft.app.data.RaftAppUpdateStrongPromptReason
import build.raft.app.data.RaftAppUpdates
import build.raft.app.data.RaftMinBuildGate
import build.raft.app.data.RaftMinBuildGateDecision
import build.raft.app.data.RaftPostUpdateNoticeState
import build.raft.app.data.SharedAnnouncementStore
import build.raft.app.data.SharedSettingsPlatformCapabilities
import build.raft.app.data.SharedSettingsUpdateCheckSnapshot
import build.raft.app.data.SharedSettingsUpdateTransactionState
import build.raft.app.data.arbitrateRaftAppModalPrompt
import build.raft.app.data.parseRaftBuildNumber
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.network.RaftAppIdentityRegistry
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftDismissOnPageCover
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal const val RAFT_APP_UPDATE_STRONG_DIALOG_TAG = "raft_app_update_strong_dialog"
internal const val RAFT_APP_UPDATE_STRONG_DIALOG_UPDATE_TAG = "raft_app_update_strong_dialog_update"
internal const val RAFT_APP_UPDATE_STRONG_DIALOG_LATER_TAG = "raft_app_update_strong_dialog_later"
internal const val RAFT_POST_UPDATE_WHATS_NEW_BANNER_TAG = "raft_post_update_whats_new_banner"
internal const val RAFT_POST_UPDATE_WHATS_NEW_ACTION_TAG = "raft_post_update_whats_new_action"
internal const val RAFT_POST_UPDATE_WHATS_NEW_DISMISS_TAG = "raft_post_update_whats_new_dismiss"

internal data class RaftTopBarUpdatePresentation(
    val label: String,
    val tooltip: String,
)

/**
 * Pure presentation mapping for the wide top bar's Update button (L1 ambient entry).
 */
internal fun raftAppTopBarUpdatePresentation(
    snapshot: SharedSettingsUpdateCheckSnapshot,
    updateLabel: String,
    installLabel: String,
    restartLabel: String,
    updateTooltipForVersion: (String) -> String,
    installTooltipForVersion: (String) -> String,
    restartTooltipForVersion: (String) -> String,
): RaftTopBarUpdatePresentation {
    val latestVersion = snapshot.latestVersionLabel.trim()
    return when (snapshot.transactionState) {
        SharedSettingsUpdateTransactionState.ReadyToRestart ->
            RaftTopBarUpdatePresentation(
                label = restartLabel,
                tooltip = if (latestVersion.isNotBlank()) restartTooltipForVersion(latestVersion) else restartLabel,
            )
        SharedSettingsUpdateTransactionState.ReadyToInstall,
        SharedSettingsUpdateTransactionState.InstallerOpened ->
            RaftTopBarUpdatePresentation(
                label = installLabel,
                tooltip = if (latestVersion.isNotBlank()) installTooltipForVersion(latestVersion) else installLabel,
            )
        else ->
            RaftTopBarUpdatePresentation(
                label = updateLabel,
                tooltip = if (latestVersion.isNotBlank()) updateTooltipForVersion(latestVersion) else updateLabel,
            )
    }
}

/**
 * Extracts up to [maxLines] concise, readable preview lines from [changelog] for the L2 strong
 * update reminder modal.
 */
internal fun raftAppUpdateChangelogPreviewLines(
    changelog: String,
    maxLines: Int = 3,
): List<String> {
    if (changelog.isBlank() || maxLines <= 0) return emptyList()
    return changelog
        .lineSequence()
        .map { raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                ""
            } else if (trimmed.all { it == '-' || it == '*' || it == '_' } && trimmed.length >= 3) {
                ""
            } else {
                trimmed.trimStart('#').trim()
            }
        }
        .filter { it.isNotBlank() }
        .take(maxLines)
        .toList()
}

/**
 * Triggers in-app update completion when [snapshot] is [SharedSettingsUpdateTransactionState.ReadyToRestart],
 * falling back to opening [AppUpdatePage] if completion fails or reports an error.
 */
internal fun triggerAppShellUpdateRestartOrOpenPage(
    snapshot: SharedSettingsUpdateCheckSnapshot,
    scope: CoroutineScope,
    settingsCapabilities: SharedSettingsPlatformCapabilities,
    onOpenUpdatePage: () -> Unit,
) {
    scope.launch {
        val result =
            runCatchingPreservingCancellation {
                settingsCapabilities.installHandsUpdateForToken(snapshot.downloadProgressToken)
            }.getOrNull()
        if (result !is DataResult.Content || result.data.errorCode.isNotBlank()) {
            onOpenUpdatePage()
        }
    }
}

/**
 * Resolves the wide top bar's L1 update button action from [coordinator] state.
 * Kept in its own composable scope so update coordinator emissions do not recompose the full shell.
 */
@Composable
internal fun rememberAppShellTopBarUpdateAction(
    onOpenUpdatePage: () -> Unit,
    onCompleteRestart: (SharedSettingsUpdateCheckSnapshot) -> Unit,
    onBeforeAction: () -> Unit = {},
    coordinator: RaftAppUpdateCoordinator = RaftAppUpdates.coordinator,
): RaftAppTopBarUpdateAction? {
    val updateState by coordinator.state.collectAsState()
    val snapshot = updateState.snapshot
    if (!updateState.decision.showAmbientPrompt || snapshot == null) return null

    val updateLabel = stringResource(Res.string.app_update_update)
    val installLabel = stringResource(Res.string.app_update_install)
    val restartLabel = stringResource(Res.string.app_update_restart_to_update)
    val latestVersion = snapshot.latestVersionLabel.trim()
    val updateTooltip =
        if (latestVersion.isNotBlank()) stringResource(Res.string.app_update_top_bar_tooltip_update, latestVersion) else updateLabel
    val installTooltip =
        if (latestVersion.isNotBlank()) stringResource(Res.string.app_update_top_bar_tooltip_install, latestVersion) else installLabel
    val restartTooltip =
        if (latestVersion.isNotBlank()) stringResource(Res.string.app_update_top_bar_tooltip_restart, latestVersion) else restartLabel
    val presentation =
        raftAppTopBarUpdatePresentation(
            snapshot = snapshot,
            updateLabel = updateLabel,
            installLabel = installLabel,
            restartLabel = restartLabel,
            updateTooltipForVersion = { updateTooltip },
            installTooltipForVersion = { installTooltip },
            restartTooltipForVersion = { restartTooltip },
        )
    return RaftAppTopBarUpdateAction(
        label = presentation.label,
        tooltip = presentation.tooltip,
        onClick = {
            onBeforeAction()
            if (snapshot.transactionState == SharedSettingsUpdateTransactionState.ReadyToRestart) {
                onCompleteRestart(snapshot)
            } else {
                onOpenUpdatePage()
            }
        },
    )
}

private data class PresentedStrongUpdatePrompt(
    val snapshot: SharedSettingsUpdateCheckSnapshot,
    val reason: RaftAppUpdateStrongPromptReason,
)

/**
 * Shell-level host for the L2 strong update reminder modal.
 *
 * - Records the current app launch on mount so upgrade detection runs even if the coordinator
 *   initialized before app identity registration.
 * - Arbitrates foreground modal ownership via [arbitrateRaftAppModalPrompt] so L3 hard blocks,
 *   user-initiated dialogs, and pending/visible announcements always preempt update reminders.
 * - Holds [presentedPrompt] locally while open and records `markStrongPromptShown()` once on open
 *   so the 24h strong-prompt cooldown never flicker-closes the visible dialog.
 */
@Composable
internal fun AppShellUpdatePromptHost(
    pageVisible: Boolean,
    userDialogActive: Boolean,
    announcementStore: SharedAnnouncementStore?,
    announcementModalShown: Boolean,
    onOpenUpdatePage: () -> Unit,
    onCompleteRestart: (SharedSettingsUpdateCheckSnapshot) -> Unit,
    onSnoozed: () -> Unit,
    coordinator: RaftAppUpdateCoordinator = RaftAppUpdates.coordinator,
    minBuildGateDecision: RaftMinBuildGateDecision? = null,
) {
    LaunchedEffect(coordinator) {
        val identity = RaftAppIdentityRegistry.current()
        val buildCode = parseRaftBuildNumber(identity?.buildNumber) ?: 0L
        if (buildCode > 0L) {
            coordinator.recordAppLaunch(
                currentVersionCode = buildCode,
                currentVersionLabel = identity?.version.orEmpty(),
            )
        }
    }

    val updateState by coordinator.state.collectAsState()
    val collectedMinBuildGateDecision by RaftMinBuildGate.decision.collectAsState()
    val effectiveMinBuildGate = minBuildGateDecision ?: collectedMinBuildGateDecision
    val activeAnnouncement = announcementStore?.announcement?.collectAsState()?.value
    val pageActive = LocalRaftPageActive.current

    var presentedPrompt by remember { mutableStateOf<PresentedStrongUpdatePrompt?>(null) }

    RaftDismissOnPageCover { presentedPrompt = null }

    val minBuildGateBlocking = effectiveMinBuildGate is RaftMinBuildGateDecision.Block
    val announcementReady = announcementModalShown || activeAnnouncement != null
    val canPresentCandidate =
        pageVisible &&
            pageActive &&
            updateState.decision.showStrongPromptCandidate &&
            updateState.snapshot != null &&
            updateState.decision.strongPromptReason != null
    val activeSlot =
        arbitrateRaftAppModalPrompt(
            minBuildGateBlocking = minBuildGateBlocking,
            userDialogActive = userDialogActive,
            announcementReady = announcementReady,
            appUpdateStrongPromptReady = canPresentCandidate || presentedPrompt != null,
        )

    LaunchedEffect(
        activeSlot,
        canPresentCandidate,
        pageVisible,
        pageActive,
        minBuildGateBlocking,
        updateState.snapshot,
        updateState.decision,
    ) {
        val currentPresented = presentedPrompt
        if (currentPresented != null) {
            if (minBuildGateBlocking || !pageVisible || !pageActive || !updateState.decision.hasAvailableUpdate) {
                presentedPrompt = null
                return@LaunchedEffect
            }
            val latestSnap = updateState.snapshot
            if (latestSnap != null && latestSnap.latestVersionCode >= currentPresented.snapshot.latestVersionCode) {
                val updatedReason = updateState.decision.strongPromptReason ?: currentPresented.reason
                if (latestSnap != currentPresented.snapshot || updatedReason != currentPresented.reason) {
                    presentedPrompt = PresentedStrongUpdatePrompt(snapshot = latestSnap, reason = updatedReason)
                }
            }
            return@LaunchedEffect
        }
        if (activeSlot == RaftAppModalPromptSlot.AppUpdateStrongPrompt && canPresentCandidate) {
            val snapshot = updateState.snapshot
            val reason = updateState.decision.strongPromptReason
            if (snapshot != null && reason != null) {
                presentedPrompt = PresentedStrongUpdatePrompt(snapshot = snapshot, reason = reason)
                coordinator.markStrongPromptShown()
            }
        }
    }

    val visiblePrompt =
        presentedPrompt?.takeIf {
            pageVisible &&
                pageActive &&
                activeSlot == RaftAppModalPromptSlot.AppUpdateStrongPrompt
        }
    if (visiblePrompt != null) {
        RaftAppUpdateStrongPromptDialog(
            snapshot = visiblePrompt.snapshot,
            reason = visiblePrompt.reason,
            onUpdateNow = {
                presentedPrompt = null
                if (visiblePrompt.snapshot.transactionState == SharedSettingsUpdateTransactionState.ReadyToRestart) {
                    onCompleteRestart(visiblePrompt.snapshot)
                } else {
                    onOpenUpdatePage()
                }
            },
            onRemindLater = {
                presentedPrompt = null
                coordinator.snoozeCurrentUpdate()
                coordinator.markStrongPromptShown()
                onSnoozed()
            },
            onDismiss = {
                presentedPrompt = null
                coordinator.markStrongPromptShown()
            },
        )
    }
}

/**
 * L2 strong update reminder modal dialog.
 *
 * Follows raft-ui 0.103 DialogBody (`colors.foreground` ink for readable body/version/changelog copy,
 * never muted gray) with Primary commit action and Ghost "Later (24h)" snooze action.
 */
@Composable
internal fun RaftAppUpdateStrongPromptDialog(
    snapshot: SharedSettingsUpdateCheckSnapshot,
    reason: RaftAppUpdateStrongPromptReason,
    onUpdateNow: () -> Unit,
    onRemindLater: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestVersion = snapshot.latestVersionLabel.trim()
    val isReady =
        snapshot.transactionState == SharedSettingsUpdateTransactionState.ReadyToRestart ||
            snapshot.transactionState == SharedSettingsUpdateTransactionState.ReadyToInstall ||
            snapshot.transactionState == SharedSettingsUpdateTransactionState.InstallerOpened ||
            reason == RaftAppUpdateStrongPromptReason.ReadyToRestart ||
            reason == RaftAppUpdateStrongPromptReason.ReadyToInstall
    val title =
        when {
            isReady && latestVersion.isNotBlank() ->
                stringResource(Res.string.app_update_strong_dialog_title_ready, latestVersion)
            isReady ->
                stringResource(Res.string.app_update_strong_dialog_title_ready_generic)
            latestVersion.isNotBlank() ->
                stringResource(Res.string.app_update_strong_dialog_title_available, latestVersion)
            else ->
                stringResource(Res.string.home_notification_app_update_title)
        }
    val bodyMessage =
        when (reason) {
            RaftAppUpdateStrongPromptReason.ReadyToRestart ->
                stringResource(Res.string.app_update_strong_dialog_body_ready_to_restart)
            RaftAppUpdateStrongPromptReason.ReadyToInstall ->
                stringResource(Res.string.app_update_strong_dialog_body_ready_to_install)
            RaftAppUpdateStrongPromptReason.ForceUpdate,
            RaftAppUpdateStrongPromptReason.HighPriority ->
                stringResource(Res.string.app_update_strong_dialog_body_important)
            RaftAppUpdateStrongPromptReason.BehindMultipleReleases ->
                stringResource(Res.string.app_update_strong_dialog_body_behind)
            RaftAppUpdateStrongPromptReason.UninstalledForDays ->
                stringResource(Res.string.app_update_strong_dialog_body_waiting)
        }
    val primaryLabel =
        when (snapshot.transactionState) {
            SharedSettingsUpdateTransactionState.ReadyToRestart ->
                stringResource(Res.string.app_update_restart_to_update)
            SharedSettingsUpdateTransactionState.ReadyToInstall,
            SharedSettingsUpdateTransactionState.InstallerOpened ->
                stringResource(Res.string.app_update_strong_dialog_action_install_now)
            else ->
                when (reason) {
                    RaftAppUpdateStrongPromptReason.ReadyToRestart ->
                        stringResource(Res.string.app_update_restart_to_update)
                    RaftAppUpdateStrongPromptReason.ReadyToInstall ->
                        stringResource(Res.string.app_update_strong_dialog_action_install_now)
                    else ->
                        stringResource(Res.string.app_update_strong_dialog_action_update_now)
                }
        }
    val versionLabels =
        appUpdateVersionLabels(
            currentLabel = snapshot.currentVersionLabel,
            currentCode = snapshot.currentVersionCode,
            latestLabel = snapshot.latestVersionLabel,
            latestCode = snapshot.latestVersionCode,
        )
    val hasVersionPair = versionLabels.first.isNotBlank() && versionLabels.second.isNotBlank()
    val changelogLines = remember(snapshot.latestChangelog) { raftAppUpdateChangelogPreviewLines(snapshot.latestChangelog) }

    RaftDesignSystemTheme {
        val colors = RaftTheme.colors
        val typography = RaftTheme.typography
        RaftDialog(
            onDismissRequest = onDismiss,
            title = title,
            modifier = modifier.testTag(RAFT_APP_UPDATE_STRONG_DIALOG_TAG),
            size = RaftDialogSize.Sm,
            closeLabel = stringResource(Res.string.common_close),
            footer = {
                RaftButton(
                    text = stringResource(Res.string.app_update_strong_dialog_action_later),
                    onClick = onRemindLater,
                    modifier = Modifier.testTag(RAFT_APP_UPDATE_STRONG_DIALOG_LATER_TAG),
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg,
                )
                RaftButton(
                    text = primaryLabel,
                    onClick = onUpdateNow,
                    modifier = Modifier.testTag(RAFT_APP_UPDATE_STRONG_DIALOG_UPDATE_TAG),
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg,
                )
            },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RaftText(
                    text = bodyMessage,
                    style = typography.body,
                    color = colors.foreground,
                )
                if (hasVersionPair) {
                    val shape = RoundedCornerShape(RaftRadius.Control)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(colors.fillMuted, shape)
                                .border(RaftStroke.Hairline, colors.lineHairline, shape)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RaftContentLucideIcon(RaftLucideIconName.CircleArrowUp, size = 16.dp)
                        RaftText(
                            text = stringResource(Res.string.app_update_version_summary, versionLabels.first, versionLabels.second),
                            style = typography.callout.copy(fontWeight = FontWeight.Bold),
                            color = colors.foreground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (changelogLines.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        changelogLines.forEach { line ->
                            RaftText(
                                text = line,
                                style = typography.callout,
                                color = colors.foreground,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * L0 post-update "What's New" dismissible light banner, shown once after upgrading to a newer
 * build when no higher-priority update prompt is active.
 */
@Composable
internal fun RaftPostUpdateWhatsNewBanner(
    notice: RaftPostUpdateNoticeState,
    onViewReleaseNotes: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RaftDesignSystemTheme {
        RaftBanner(
            description = AnnotatedString(stringResource(Res.string.app_update_post_update_banner_title, notice.versionLabel)),
            status = RaftBannerStatus.Info,
            size = RaftBannerSize.Sm,
            live = false,
            modifier = modifier.testTag(RAFT_POST_UPDATE_WHATS_NEW_BANNER_TAG),
            action = {
                RaftButton(
                    text = stringResource(Res.string.common_dismiss),
                    onClick = onDismiss,
                    modifier = Modifier.testTag(RAFT_POST_UPDATE_WHATS_NEW_DISMISS_TAG),
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Md,
                )
                RaftButton(
                    text = stringResource(Res.string.app_update_post_update_banner_action),
                    onClick = onViewReleaseNotes,
                    modifier = Modifier.testTag(RAFT_POST_UPDATE_WHATS_NEW_ACTION_TAG),
                    variant = RaftButtonVariant.Default,
                    size = RaftButtonSize.Md,
                )
            },
        )
    }
}

/**
 * Home host for [RaftPostUpdateWhatsNewBanner], observing [coordinator] in its own narrow scope.
 */
@Composable
internal fun HomePostUpdateWhatsNewBannerHost(
    onOpenReleaseNotes: (versionCode: String) -> Unit,
    modifier: Modifier = Modifier,
    coordinator: RaftAppUpdateCoordinator = RaftAppUpdates.coordinator,
) {
    val updateState by coordinator.state.collectAsState()
    val notice = updateState.decision.postUpdateNotice ?: return
    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        RaftPostUpdateWhatsNewBanner(
            notice = notice,
            onViewReleaseNotes = {
                coordinator.dismissPostUpdateNotice()
                onOpenReleaseNotes(notice.versionCode.toString())
            },
            onDismiss = {
                coordinator.dismissPostUpdateNotice()
            },
        )
    }
}
