package build.raft.app

import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.app.components.RaftSlotIcon
import build.raft.app.components.RaftSwitchButton
import build.raft.app.components.SegmentedControlOption
import build.raft.app.components.raftCardSurface
import build.raft.app.data.MOBILE_ACTION_FOREGROUND_MESSAGE_BANNERS
import build.raft.app.data.MOBILE_ACTION_FOREGROUND_KEEP_ALIVE
import build.raft.app.data.RaftBackgroundConnectionControls
import build.raft.app.data.RaftBackgroundConnectionControlsRegistry
import build.raft.app.data.MOBILE_ACTION_OFFLINE_COMPUTER_ALERTS
import build.raft.app.data.MOBILE_ACTION_NOTIFICATION_PREFERENCE
import build.raft.app.data.MOBILE_ACTION_PERMISSION
import build.raft.app.data.MOBILE_ACTION_SERVER_MUTE
import build.raft.app.data.MOBILE_ACTION_PUSH_PREVIEW_MODE
import build.raft.app.data.MOBILE_ACTION_SETTINGS
import build.raft.app.data.MOBILE_ACTION_TEST_NOTIFICATION
import build.raft.app.data.SharedSettingsActionState
import org.jetbrains.compose.resources.StringResource
import build.raft.app.data.NotificationPreferenceSnapshot
import build.raft.app.data.ServerPushMode
import build.raft.app.data.PushPreviewMode
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsMobileSnapshot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import build.raft.app.data.RaftNotificationAccountSettingsPatch
import build.raft.app.data.RaftNotificationAccountSettingsStore
import build.raft.app.data.RaftNotificationPauseOption
import build.raft.app.data.raftCurrentTimeZoneId
import build.raft.app.data.raftMinuteOfDayLabel
import build.raft.app.data.raftNowEpochMillis
import build.raft.app.data.raftPausedUntilLabel
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

@Composable
internal fun SettingsNotificationsContent(
    state: ScreenLoadState<SettingsMobileSnapshot>,
    actionState: SharedSettingsActionState?,
    bottomContentPadding: Dp,
    notificationPreferenceSnapshot: NotificationPreferenceSnapshot,
    preferenceBusy: Boolean,
    foregroundKeepAliveBusy: Boolean,
    offlineComputerAlertsBusy: Boolean,
    foregroundMessageBannersBusy: Boolean,
    onNotificationPreferenceChange: (Boolean) -> Unit,
    onForegroundKeepAlivePreferenceChange: (Boolean) -> Unit,
    onOfflineComputerAlertsChange: (Boolean) -> Unit,
    onForegroundMessageBannersChange: (Boolean) -> Unit,
    onServerNotificationModeChange: (String) -> Unit,
    onPushPreviewModeChange: (String) -> Unit,
    onPermission: () -> Unit,
    onSettings: () -> Unit,
    onTest: () -> Unit,
    onRetry: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(RaftColor.Canvas).raftSafeAreaHorizontalPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (state) {
            ScreenLoadState.Loading ->
                item {
                    SettingsLoadStateCard(
                        RaftLucideIconName.Bell,
                        stringResource(Res.string.common_loading),
                        stringResource(Res.string.settings_notifications_loading),
                        null,
                        onRetry,
                        loading = true
                    )
                }
            is ScreenLoadState.Empty ->
                item {
                    SettingsLoadStateCard(
                        RaftLucideIconName.Bell,
                        stringResource(Res.string.settings_state_unavailable),
                        state.reason.localizedUserFacingMessage(),
                        stringResource(Res.string.common_retry),
                        onRetry
                    )
                }
            is ScreenLoadState.Error ->
                item {
                    SettingsLoadStateCard(
                        RaftLucideIconName.TriangleAlert,
                        stringResource(Res.string.settings_error_notification),
                        state.error.localizedUserFacingMessage(),
                        stringResource(Res.string.common_retry),
                        onRetry
                    )
                }
            is ScreenLoadState.NoPermission ->
                item {
                    SettingsLoadStateCard(
                        RaftLucideIconName.Lock,
                        stringResource(Res.string.settings_state_permission_required),
                        state.blocker.localizedUserFacingMessage(),
                        null,
                        onRetry
                    )
                }
            is ScreenLoadState.Content -> {
                val snapshot = state.data
                item {
                    SettingsPushStatusCard(
                        snapshot = snapshot,
                        actionState = actionState,
                        notificationPreferenceSnapshot = notificationPreferenceSnapshot,
                        preferenceBusy = preferenceBusy,
                        foregroundKeepAliveBusy = foregroundKeepAliveBusy,
                        offlineComputerAlertsBusy = offlineComputerAlertsBusy,
                        foregroundMessageBannersBusy = foregroundMessageBannersBusy,
                        onNotificationPreferenceChange = onNotificationPreferenceChange,
                        onForegroundKeepAlivePreferenceChange = onForegroundKeepAlivePreferenceChange,
                        onOfflineComputerAlertsChange = onOfflineComputerAlertsChange,
                        onForegroundMessageBannersChange = onForegroundMessageBannersChange,
                        onPermission = onPermission,
                        onSettings = onSettings,
                        onTest = onTest
                    )
                }
                item {
                    SettingsServerNotificationModeCard(
                        snapshot = snapshot,
                        actionState = actionState,
                        onServerNotificationModeChange = onServerNotificationModeChange
                    )
                }
                // Account-wide (slock docs/contracts/notifications.md §1.2 / §1.5): pause, quiet
                // hours and sound apply on every device; preview privacy below too.
                item { SettingsNotificationAccountCard() }
                if (raftPlatformSupportsPushPreviewPrivacySetting() && snapshot.pushPreviewMode.available) {
                    item {
                        SettingsPushPreviewModeCard(
                            snapshot = snapshot,
                            actionState = actionState,
                            onPushPreviewModeChange = onPushPreviewModeChange
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
}

@Composable
private fun SettingsPushStatusCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    notificationPreferenceSnapshot: NotificationPreferenceSnapshot,
    preferenceBusy: Boolean,
    foregroundKeepAliveBusy: Boolean,
    offlineComputerAlertsBusy: Boolean,
    foregroundMessageBannersBusy: Boolean,
    onNotificationPreferenceChange: (Boolean) -> Unit,
    onForegroundKeepAlivePreferenceChange: (Boolean) -> Unit,
    onOfflineComputerAlertsChange: (Boolean) -> Unit,
    onForegroundMessageBannersChange: (Boolean) -> Unit,
    onPermission: () -> Unit,
    onSettings: () -> Unit,
    onTest: () -> Unit
) {
    val anyActionBusy = actionState?.inProgress == true
    val permissionGranted = settingsNotificationPermissionGranted(snapshot.notificationPermission.statusLabel)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsNotificationSectionLabel(stringResource(Res.string.settings_notifications_system))
        SettingsNotificationPermissionBanner(
            detail = settingsNotificationPermissionDetail(
                permissionGranted = permissionGranted,
                permissionAvailable = snapshot.notificationPermission.available
            ),
            permissionGranted = permissionGranted,
            permissionAvailable = snapshot.notificationPermission.available,
            settingsAvailable = snapshot.notificationSettings.available,
            busy = anyActionBusy,
            onPermission = onPermission,
            onSettings = onSettings
        )
        if (actionState?.action in setOf(MOBILE_ACTION_PERMISSION, MOBILE_ACTION_SETTINGS) && actionState?.errorCode != null) {
            SettingsFormErrorText(actionState)
        }

        SettingsNotificationSectionLabel(stringResource(Res.string.settings_notifications_on_device))
        SettingsNotificationPreferenceGroup {
            SettingsNotificationToggleRow(
                title = stringResource(Res.string.settings_notifications_message_title),
                detail = stringResource(Res.string.settings_notifications_message_detail),
                checked = notificationPreferenceSnapshot.enabled,
                enabled = !preferenceBusy,
                onCheckedChange = onNotificationPreferenceChange
            )
            SettingsNotificationDivider()
            val backgroundConnection = remember { RaftBackgroundConnectionControlsRegistry.offeredControls() }
            if (backgroundConnection != null) {
                // Android without Google Play services: the same preference keeps the realtime connection
                // alive from a foreground service, the only notification path such a device has.
                SettingsNotificationToggleRow(
                    title = stringResource(Res.string.settings_notifications_keep_connection_title),
                    detail = stringResource(Res.string.settings_notifications_keep_connection_detail),
                    checked = notificationPreferenceSnapshot.foregroundKeepAliveEnabled,
                    enabled = !foregroundKeepAliveBusy,
                    onCheckedChange = { enabled ->
                        onForegroundKeepAlivePreferenceChange(enabled)
                        if (enabled && !backgroundConnection.isBatteryOptimizationExempt()) {
                            backgroundConnection.requestBatteryOptimizationExemption()
                        }
                    }
                )
                if (notificationPreferenceSnapshot.foregroundKeepAliveEnabled) {
                    SettingsNotificationDivider()
                    RaftBackgroundConnectionBatteryRow(backgroundConnection)
                }
                SettingsNotificationDivider()
            } else if (raftPlatformSupportsBackgroundRealtime()) {
                SettingsNotificationToggleRow(
                    title = stringResource(Res.string.settings_notifications_background_realtime_title),
                    detail = stringResource(Res.string.settings_notifications_background_realtime_detail, raftPlatformProductName()),
                    checked = notificationPreferenceSnapshot.foregroundKeepAliveEnabled,
                    enabled = !foregroundKeepAliveBusy,
                    onCheckedChange = onForegroundKeepAlivePreferenceChange
                )
                SettingsNotificationDivider()
            }
            SettingsNotificationToggleRow(
                title = stringResource(Res.string.settings_notifications_offline_computers_title),
                detail = stringResource(Res.string.settings_notifications_offline_computers_detail),
                checked = notificationPreferenceSnapshot.offlineComputerAlertsEnabled,
                enabled = !offlineComputerAlertsBusy,
                onCheckedChange = onOfflineComputerAlertsChange
            )
            SettingsNotificationDivider()
            SettingsNotificationToggleRow(
                title = stringResource(Res.string.settings_foreground_banners_title),
                detail = stringResource(Res.string.settings_foreground_banners_detail),
                checked = snapshot.foregroundMessageBanners.enabled,
                enabled = snapshot.foregroundMessageBanners.available && !foregroundMessageBannersBusy,
                onCheckedChange = onForegroundMessageBannersChange
            )
            SettingsNotificationDivider()
            SettingsNotificationActionRow(
                title = stringResource(Res.string.settings_notifications_test_title),
                detail = snapshot.testNotification.localizedTestNotificationDetail(),
                status = snapshot.testNotification.localizedTestNotificationStatus(),
                enabled = snapshot.testNotification.available && snapshot.sendTestEnabled && !anyActionBusy,
                onClick = onTest
            )
            if (actionState?.action in setOf(
                    MOBILE_ACTION_NOTIFICATION_PREFERENCE,
                    MOBILE_ACTION_FOREGROUND_KEEP_ALIVE,
                    MOBILE_ACTION_OFFLINE_COMPUTER_ALERTS,
                    MOBILE_ACTION_FOREGROUND_MESSAGE_BANNERS
                ) && actionState?.errorCode != null
            ) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    SettingsFormErrorText(actionState)
                }
            }
            if (actionState?.action == MOBILE_ACTION_TEST_NOTIFICATION && actionState.errorCode != null) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    SettingsFormErrorText(actionState)
                }
            }
        }
    }
}

internal fun settingsNotificationPermissionGranted(status: String): Boolean =
    status.trim().lowercase() in setOf("allowed", "granted", "enabled", "on")

internal enum class SettingsNotificationPermissionCopy {
    Allowed,
    Needed,
    Unavailable
}

internal fun settingsNotificationPermissionCopy(
    permissionGranted: Boolean,
    permissionAvailable: Boolean
): SettingsNotificationPermissionCopy =
    when {
        permissionGranted -> SettingsNotificationPermissionCopy.Allowed
        permissionAvailable -> SettingsNotificationPermissionCopy.Needed
        else -> SettingsNotificationPermissionCopy.Unavailable
    }

@Composable
private fun settingsNotificationPermissionDetail(
    permissionGranted: Boolean,
    permissionAvailable: Boolean
): String =
    when (settingsNotificationPermissionCopy(permissionGranted, permissionAvailable)) {
        SettingsNotificationPermissionCopy.Allowed ->
            stringResource(Res.string.settings_notifications_permission_allowed_detail)
        SettingsNotificationPermissionCopy.Needed ->
            stringResource(Res.string.settings_notifications_permission_open_settings, raftPlatformProductName())
        SettingsNotificationPermissionCopy.Unavailable ->
            stringResource(Res.string.settings_notifications_permission_unavailable_detail)
    }

internal enum class SettingsTestNotificationCopy {
    Ready,
    Sent,
    Requested,
    Unavailable,
    Error
}

internal fun settingsTestNotificationCopy(
    statusLabel: String,
    available: Boolean
): SettingsTestNotificationCopy =
    when (statusLabel.trim().lowercase()) {
        "sent", "done", "opened" -> SettingsTestNotificationCopy.Sent
        "requested" -> SettingsTestNotificationCopy.Requested
        "unsupported", "unavailable", "disabled", "blocked", "denied" ->
            SettingsTestNotificationCopy.Unavailable
        "error", "failed", "failure" -> SettingsTestNotificationCopy.Error
        else -> if (available) SettingsTestNotificationCopy.Ready else SettingsTestNotificationCopy.Unavailable
    }

@Composable
private fun build.raft.app.data.SettingsMobileActionSnapshot.localizedTestNotificationStatus(): String =
    when (settingsTestNotificationCopy(statusLabel, available)) {
        SettingsTestNotificationCopy.Ready -> stringResource(Res.string.settings_state_ready)
        SettingsTestNotificationCopy.Sent -> stringResource(Res.string.settings_notifications_test_status_sent)
        SettingsTestNotificationCopy.Requested -> stringResource(Res.string.settings_notifications_test_status_requested)
        SettingsTestNotificationCopy.Unavailable -> stringResource(Res.string.settings_state_unavailable)
        SettingsTestNotificationCopy.Error -> stringResource(Res.string.settings_state_error)
    }

@Composable
private fun build.raft.app.data.SettingsMobileActionSnapshot.localizedTestNotificationDetail(): String =
    when (settingsTestNotificationCopy(statusLabel, available)) {
        SettingsTestNotificationCopy.Ready -> stringResource(Res.string.settings_notifications_test_detail)
        SettingsTestNotificationCopy.Sent -> stringResource(Res.string.settings_notifications_test_sent_detail)
        SettingsTestNotificationCopy.Requested -> stringResource(Res.string.settings_notifications_test_requested_detail)
        SettingsTestNotificationCopy.Unavailable -> stringResource(Res.string.settings_notifications_test_unavailable_detail)
        SettingsTestNotificationCopy.Error -> stringResource(Res.string.settings_notifications_test_error_detail)
    }

@Composable
private fun SettingsNotificationSectionLabel(label: String) {
    Text(
        // raft-ui section titles keep source case.
        text = label,
        style = RaftType.SectionLabel.copy(color = RaftColor.ForegroundHint, letterSpacing = 0.sp),
        color = RaftColor.ForegroundHint,
        modifier = Modifier.fillMaxWidth().padding(start = 2.dp, top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun SettingsNotificationPermissionBanner(
    detail: String,
    permissionGranted: Boolean,
    permissionAvailable: Boolean,
    settingsAvailable: Boolean,
    busy: Boolean,
    onPermission: () -> Unit,
    onSettings: () -> Unit
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .raftCardSurface(fill = if (permissionGranted) RaftColor.SuccessSoft else RaftColor.BrandSoft)
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = settingsAvailable && !busy, onClick = onSettings),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RaftLucideIcon(
                if (permissionGranted) RaftLucideIconName.ShieldCheck else RaftLucideIconName.Bell,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text =
                        if (permissionGranted) {
                            stringResource(Res.string.settings_notifications_permission_allowed)
                        } else {
                            stringResource(Res.string.settings_notifications_permission_needed)
                        },
                    style = RaftType.Button.copy(color = RaftColor.TextBlack),
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = detail,
                    style = RaftType.Caption.copy(color = RaftColor.TextBlack),
                    color = RaftColor.TextBlack,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            RaftLucideIcon(RaftLucideIconName.ChevronRight, modifier = Modifier.size(18.dp))
        }
        if (!permissionGranted) {
            RaftButton(
                text = stringResource(Res.string.settings_notifications_request_permission),
                onClick = onPermission,
                enabled = permissionAvailable && !busy,
                size = RaftButtonSize.Md,
                leadingIcon = { RaftSlotIcon(RaftLucideIconName.Bell) }
            )
        }
    }
}

@Composable
private fun SettingsNotificationPreferenceGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().raftCardSurface(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        content = content
    )
}

@Composable
private fun SettingsNotificationDivider() {
    Box(Modifier.fillMaxWidth().height(RaftAppStroke.Thin).background(RaftColor.BorderHairline))
}

@Composable
private fun SettingsNotificationActionRow(
    title: String,
    detail: String,
    status: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                style = RaftType.Button.copy(color = RaftColor.TextBlack),
                color = RaftColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            SettingsFormDetailText(detail)
        }
        Text(
            text = status,
            style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        RaftLucideIcon(RaftLucideIconName.Send, modifier = Modifier.size(18.dp))
    }
}

/**
 * Battery-optimization row for the Android no-Play-services background connection. The exemption is only
 * ever requested from a tap here or on the switch above; the host re-reads the status when the app resumes
 * (the system prompt returns without a callback), so nothing polls here.
 */
@Composable
private fun RaftBackgroundConnectionBatteryRow(controls: RaftBackgroundConnectionControls) {
    val exempt by controls.batteryOptimizationExemptState.collectAsState()
    SettingsNotificationActionRow(
        title = stringResource(Res.string.settings_notifications_battery_optimization_title),
        detail = stringResource(Res.string.settings_notifications_battery_optimization_detail),
        status =
            if (exempt) {
                stringResource(Res.string.settings_notifications_battery_optimization_allowed)
            } else {
                stringResource(Res.string.settings_notifications_battery_optimization_request)
            },
        enabled = !exempt,
        onClick = { controls.requestBatteryOptimizationExemption() }
    )
}

@Composable
internal fun SettingsNotificationToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onCheckedChange(!checked) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                style = RaftType.Button.copy(color = RaftColor.TextBlack),
                color = RaftColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            SettingsFormDetailText(detail)
        }
        RaftSwitchButton(checked = checked, enabled = enabled)
    }
}

@Composable
private fun SettingsServerNotificationModeCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onServerNotificationModeChange: (String) -> Unit
) {
    val busy = actionState?.action == MOBILE_ACTION_SERVER_MUTE && actionState.inProgress
    val preference = snapshot.serverNotificationMode
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsNotificationSectionLabel(stringResource(Res.string.settings_notifications_workspace))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            preference.options.mapNotNull { option -> ServerPushMode.fromWire(option.key) }.forEach { mode ->
                SettingsServerSelectRow(
                    title = mode.settingsNotificationModeTitle(),
                    detail = mode.settingsNotificationModeDetail(),
                    selected = preference.selectedKey == mode.wireName,
                    enabled = preference.available && !busy,
                    onClick = { onServerNotificationModeChange(mode.wireName) }
                )
            }
        }
        if (!preference.available) {
            // The live value did not load: the rows show the last-known mode (or none), read-only.
            SettingsFormDetailText(stringResource(Res.string.settings_notifications_mode_unavailable))
        }
        if (actionState?.action == MOBILE_ACTION_SERVER_MUTE && actionState.errorCode != null) {
            SettingsFormErrorText(actionState)
        }
    }
}

@Composable
private fun SettingsPushPreviewModeCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onPushPreviewModeChange: (String) -> Unit
) {
    val busy = actionState?.action == MOBILE_ACTION_PUSH_PREVIEW_MODE && actionState.inProgress
    val preference = snapshot.pushPreviewMode
    SettingsNotificationPreferenceGroup {
        // Inside the card like its sibling rows: the same 14/12 inset and the row title type
        // (artin 2026-10-21: the label and control ran flush against the card's edge).
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = stringResource(Res.string.settings_notifications_preview_mode_title),
                style = RaftType.Button.copy(color = RaftColor.TextBlack),
                color = RaftColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            SettingsFormDetailText(stringResource(Res.string.settings_notifications_preview_mode_detail))
        }
        SettingsSegmentedControlField(
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            fillWidth = true,
            selectedValue = preference.selectedKey,
            options =
                PushPreviewMode.entries.map { mode ->
                    SegmentedControlOption(
                        mode.wireName,
                        stringResource(mode.settingsPreviewModeLabel()),
                        mode.settingsPreviewModeIcon(),
                    )
                },
            enabled = preference.available && !busy,
            onValueSelected = { onPushPreviewModeChange(it) },
        )
        if (actionState?.action == MOBILE_ACTION_PUSH_PREVIEW_MODE && actionState.errorCode != null) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                SettingsFormErrorText(actionState)
            }
        }
    }
}

internal fun PushPreviewMode.settingsPreviewModeIcon(): RaftLucideIconName =
    when (this) {
        PushPreviewMode.Full -> RaftLucideIconName.Eye
        PushPreviewMode.SenderOnly -> RaftLucideIconName.MessageSquare
        PushPreviewMode.Hidden -> RaftLucideIconName.EyeOff
    }

internal fun PushPreviewMode.settingsPreviewModeLabel(): StringResource =
    when (this) {
        PushPreviewMode.Full -> Res.string.settings_notifications_preview_mode_full
        PushPreviewMode.SenderOnly -> Res.string.settings_notifications_preview_mode_sender_only
        PushPreviewMode.Hidden -> Res.string.settings_notifications_preview_mode_hidden
    }

@Composable
private fun ServerPushMode.settingsNotificationModeTitle(): String =
    when (this) {
        ServerPushMode.All -> stringResource(Res.string.settings_notifications_mode_all)
        ServerPushMode.Mentions -> stringResource(Res.string.settings_notifications_mode_mentions)
        ServerPushMode.None -> stringResource(Res.string.settings_notifications_mode_none)
    }

@Composable
private fun ServerPushMode.settingsNotificationModeDetail(): String =
    when (this) {
        ServerPushMode.All -> stringResource(Res.string.settings_notifications_mode_all_detail)
        ServerPushMode.Mentions -> stringResource(Res.string.settings_notifications_mode_mentions_detail)
        ServerPushMode.None -> stringResource(Res.string.settings_notifications_mode_none_detail)
    }

/**
 * Pause, quiet hours and sound: the account-wide settings of `me/notification-settings` (design
 * §4.3), read from and written through [RaftNotificationAccountSettingsStore], which applies a change
 * at once and rolls it back when the server refuses.
 */
@Composable
private fun SettingsNotificationAccountCard() {
    val accountState by RaftNotificationAccountSettingsStore.state.collectAsState()
    // Shown once the account settings loaded: an older server without `me/notification-settings`
    // (or no answer yet) shows no controls that could only fail.
    val settings = accountState.settings ?: return
    val scope = rememberCoroutineScope()
    var pauseDialogOpen by remember { mutableStateOf(false) }
    var quietTimeEditing by remember { mutableStateOf<Boolean?>(null) } // true: start, false: end
    val busy = accountState.saving
    fun update(patch: RaftNotificationAccountSettingsPatch) {
        scope.launch { RaftNotificationAccountSettingsStore.update(patch) }
    }
    val now = raftNowEpochMillis()
    val pausedUntil = settings.pausedUntilEpochMillis()?.takeIf { it > now }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsNotificationSectionLabel(stringResource(Res.string.settings_notifications_everywhere))
        SettingsNotificationPreferenceGroup {
            SettingsNotificationValueRow(
                title = stringResource(Res.string.settings_notifications_pause_title),
                detail = stringResource(Res.string.settings_notifications_pause_detail),
                value =
                    pausedUntil?.let { stringResource(Res.string.settings_notifications_paused_until, raftPausedUntilLabel(it, now)) }
                        ?: stringResource(Res.string.settings_notifications_pause_off),
                enabled = !busy,
                onClick = { pauseDialogOpen = true }
            )
            SettingsNotificationDivider()
            SettingsNotificationToggleRow(
                title = stringResource(Res.string.settings_notifications_quiet_hours_title),
                detail = stringResource(Res.string.settings_notifications_quiet_hours_detail, settings.quietHours.timeZone),
                checked = settings.quietHours.enabled,
                enabled = !busy,
                onCheckedChange = { enabled ->
                    // Turning quiet hours on records this device's zone (the server needs an IANA zone).
                    val zone = if (enabled) raftCurrentTimeZoneId() else settings.quietHours.timeZone
                    update(RaftNotificationAccountSettingsPatch(quietHours = settings.quietHours.copy(enabled = enabled, timeZone = zone)))
                }
            )
            if (settings.quietHours.enabled) {
                SettingsNotificationDivider()
                SettingsNotificationValueRow(
                    title = stringResource(Res.string.settings_notifications_quiet_hours_start),
                    detail = "",
                    value = raftMinuteOfDayLabel(settings.quietHours.startMinute),
                    enabled = !busy,
                    onClick = { quietTimeEditing = true }
                )
                SettingsNotificationDivider()
                SettingsNotificationValueRow(
                    title = stringResource(Res.string.settings_notifications_quiet_hours_end),
                    detail = "",
                    value = raftMinuteOfDayLabel(settings.quietHours.endMinute),
                    enabled = !busy,
                    onClick = { quietTimeEditing = false }
                )
            }
            SettingsNotificationDivider()
            SettingsNotificationToggleRow(
                title = stringResource(Res.string.settings_notifications_sound_title),
                detail = stringResource(Res.string.settings_notifications_sound_detail),
                checked = settings.sound,
                enabled = !busy,
                onCheckedChange = { sound -> update(RaftNotificationAccountSettingsPatch(sound = sound)) }
            )
            if (accountState.errorCode != null && accountState.settings != null) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    SettingsFormErrorText(stringResource(Res.string.settings_notifications_account_save_failed))
                }
            }
        }
    }
    if (pauseDialogOpen) {
        RaftUnifiedDialog(
            title = stringResource(Res.string.settings_notifications_pause_title),
            onDismiss = { pauseDialogOpen = false },
            contentSpacing = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsServerSelectRow(
                    title =
                        if (pausedUntil != null) {
                            stringResource(Res.string.settings_notifications_resume)
                        } else {
                            stringResource(Res.string.settings_notifications_pause_off)
                        },
                    detail = "",
                    selected = pausedUntil == null,
                    enabled = true,
                    onClick = {
                        pauseDialogOpen = false
                        if (pausedUntil != null) update(RaftNotificationAccountSettingsPatch(resume = true))
                    }
                )
                RaftNotificationPauseOption.entries.forEach { option ->
                    SettingsServerSelectRow(
                        title = option.localizedPauseLabel(),
                        detail = "",
                        selected = false,
                        enabled = true,
                        onClick = {
                            pauseDialogOpen = false
                            update(RaftNotificationAccountSettingsPatch(pausedUntil = option.pausedUntil(raftNowEpochMillis())))
                        }
                    )
                }
            }
        }
    }
    quietTimeEditing?.let { editingStart ->
        val current = if (editingStart) settings.quietHours.startMinute else settings.quietHours.endMinute
        RaftUnifiedDialog(
            title =
                stringResource(
                    if (editingStart) Res.string.settings_notifications_quiet_hours_start else Res.string.settings_notifications_quiet_hours_end
                ),
            onDismiss = { quietTimeEditing = null },
            contentSpacing = 8.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                (0 until 48).map { it * 30 }.forEach { minute ->
                    SettingsServerSelectRow(
                        title = raftMinuteOfDayLabel(minute),
                        detail = "",
                        selected = minute == current,
                        // Start and end must differ (an empty window); the other end's time is not offered.
                        enabled = minute != (if (editingStart) settings.quietHours.endMinute else settings.quietHours.startMinute),
                        onClick = {
                            quietTimeEditing = null
                            // An edit also records this device's zone: the hours mean local time here.
                            val zone = raftCurrentTimeZoneId()
                            val quiet =
                                if (editingStart) {
                                    settings.quietHours.copy(startMinute = minute, timeZone = zone)
                                } else {
                                    settings.quietHours.copy(endMinute = minute, timeZone = zone)
                                }
                            update(RaftNotificationAccountSettingsPatch(quietHours = quiet))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RaftNotificationPauseOption.localizedPauseLabel(): String =
    when (this) {
        RaftNotificationPauseOption.Minutes30 -> stringResource(Res.string.settings_notifications_pause_30m)
        RaftNotificationPauseOption.Hour1 -> stringResource(Res.string.settings_notifications_pause_1h)
        RaftNotificationPauseOption.Hours2 -> stringResource(Res.string.settings_notifications_pause_2h)
        RaftNotificationPauseOption.TomorrowMorning -> stringResource(Res.string.settings_notifications_pause_tomorrow)
    }

/** A row with a value on its trailing side that opens a choice (pause, quiet hours start / end). */
@Composable
private fun SettingsNotificationValueRow(
    title: String,
    detail: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                style = RaftType.Button.copy(color = RaftColor.TextBlack),
                color = RaftColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (detail.isNotBlank()) SettingsFormDetailText(detail)
        }
        Text(
            text = value,
            style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        RaftLucideIcon(RaftLucideIconName.ChevronRight, modifier = Modifier.size(18.dp))
    }
}
