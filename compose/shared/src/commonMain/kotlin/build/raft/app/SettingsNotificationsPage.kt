package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.components.RaftSlotIcon
import build.raft.app.components.SegmentedControlOption
import build.raft.app.components.raftCardSurface
import build.raft.app.data.MOBILE_ACTION_FOREGROUND_KEEP_ALIVE
import build.raft.app.data.MOBILE_ACTION_FOREGROUND_MESSAGE_BANNERS
import build.raft.app.data.MOBILE_ACTION_NOTIFICATION_PREFERENCE
import build.raft.app.data.MOBILE_ACTION_OFFLINE_COMPUTER_ALERTS
import build.raft.app.data.MOBILE_ACTION_PERMISSION
import build.raft.app.data.MOBILE_ACTION_PUSH_PREVIEW_MODE
import build.raft.app.data.MOBILE_ACTION_SERVER_MUTE
import build.raft.app.data.MOBILE_ACTION_SETTINGS
import build.raft.app.data.MOBILE_ACTION_TEST_NOTIFICATION
import build.raft.app.data.NotificationPreferenceSnapshot
import build.raft.app.data.PushPreviewMode
import build.raft.app.data.RaftBackgroundConnectionControls
import build.raft.app.data.RaftBackgroundConnectionControlsRegistry
import build.raft.app.data.RaftNotificationAccountSettings
import build.raft.app.data.RaftNotificationAccountSettingsPatch
import build.raft.app.data.RaftNotificationAccountSettingsStore
import build.raft.app.data.RaftNotificationMobileDelay
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.ServerPushMode
import build.raft.app.data.SettingsMobileSnapshot
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.raftCurrentTimeZoneId
import build.raft.app.data.raftNowEpochMillis
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowValue
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftSwitch
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.components.RaftHoverTier

@Composable
internal fun SettingsNotificationsContent(
    state: ScreenLoadState<SettingsMobileSnapshot>,
    /**
     * The On {server} > Notifications page (web `CurrentServerNotificationsCard`): only how the
     * current server notifies, without this device's and the account's settings.
     */
    serverOnly: Boolean = false,
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
    onRetry: () -> Unit,
    /** The account's servers, the current one first ([settingsNotificationServers]). */
    servers: List<SettingsNotificationServer> = emptyList(),
    /** Fixed clock and zone for screenshot fixtures; null: the device's. */
    nowEpochMillis: Long? = null,
    timeZoneId: String? = null,
    /** Fixture account settings in place of the store's (screenshot and visual fixtures). */
    accountSettingsOverride: RaftNotificationAccountSettings? = null,
    /** Opens Settings › On {server} › Notifications (web `ServerNotificationsLink`); null: no link. */
    onOpenServerNotifications: (() -> Unit)? = null
) {
    // The account settings (`me/notification-settings`): null on an older server or before they
    // load, which keeps the previous workspace and preview cards instead of controls that could
    // only fail.
    val accountState by RaftNotificationAccountSettingsStore.state.collectAsState()
    val accountSettings = accountSettingsOverride ?: accountState.settings
    val scope = rememberCoroutineScope()
    var failedSection by remember { mutableStateOf<SettingsNotificationsSaveSection?>(null) }
    fun save(section: SettingsNotificationsSaveSection, patch: RaftNotificationAccountSettingsPatch, onDone: (Boolean) -> Unit = {}) {
        failedSection = null
        scope.launch {
            val saved = RaftNotificationAccountSettingsStore.update(patch)
            if (!saved) failedSection = section
            onDone(saved)
        }
    }
    val now = nowEpochMillis ?: raftNowEpochMillis()
    val zone = timeZoneId ?: raftCurrentTimeZoneId()
    LazyColumn(
        state = rememberSettingsSearchListState(),
        modifier = Modifier.fillMaxSize().background(RaftColor.Canvas).raftSafeAreaHorizontalPadding().padding(horizontal = 16.dp),
        // The top and bottom inset inside the list, so a located section first on the page has room
        // for its highlight (SettingsSearchSectionShape) instead of being cut where the list starts.
        contentPadding = PaddingValues(vertical = 16.dp),
        // Web `stack-y-6` between the sections.
        verticalArrangement = Arrangement.spacedBy(24.dp)
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
                        onRetry,
                        traceId = state.error.traceId
                    )
                }
            is ScreenLoadState.NoPermission ->
                item {
                    SettingsLoadStateCard(
                        RaftLucideIconName.Lock,
                        stringResource(Res.string.settings_state_permission_required),
                        state.blocker.localizedUserFacingMessage(),
                        null,
                        onRetry,
                        traceId = state.blocker.traceId
                    )
                }
            is ScreenLoadState.Content -> {
                val snapshot = state.data
                val currentServer = servers.firstOrNull { it.current }
                if (serverOnly) {
                    item {
                        // The web's CurrentServerNotificationsCard; an older server without the
                        // unified settings keeps the previous workspace card.
                        val currentServerCard = accountSettings != null && currentServer != null
                        // The previous card starts with its section's label: the highlight reaches around it.
                        val shape = if (currentServerCard) SettingsSearchCardShape else SettingsSearchSectionShape
                        Box(Modifier.fillMaxWidth().settingsSearchAnchor("mute-server", shape)) {
                            if (currentServerCard && currentServer != null) {
                                SettingsNotificationCurrentServerCard(currentServer)
                            } else {
                                SettingsServerNotificationModeCard(
                                    snapshot = snapshot,
                                    actionState = actionState,
                                    onServerNotificationModeChange = onServerNotificationModeChange
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(bottomContentPadding)) }
                    return@LazyColumn
                }
                // Web order (NotificationSettingsSection): this device, pause and schedule, content,
                // servers.
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
                if (accountSettings != null) {
                    item {
                        SettingsNotificationPauseSection(
                            settings = accountSettings,
                            saving = accountState.saving,
                            saveFailed = failedSection == SettingsNotificationsSaveSection.Schedule,
                            nowEpochMillis = now,
                            timeZoneId = zone,
                            onSave = { patch, onDone -> save(SettingsNotificationsSaveSection.Schedule, patch, onDone) }
                        )
                    }
                    item {
                        // The phone keeps a device copy of the preview for the text it builds itself:
                        // it saves through the settings store, which writes the account too.
                        val devicePreview = raftPlatformSupportsPushPreviewPrivacySetting() && snapshot.pushPreviewMode.available
                        SettingsNotificationContentSection(
                            settings = accountSettings,
                            previewMode = accountSettings.preview,
                            saving = accountState.saving || actionState?.action == MOBILE_ACTION_PUSH_PREVIEW_MODE && actionState.inProgress,
                            saveFailed =
                                failedSection == SettingsNotificationsSaveSection.Content ||
                                    actionState?.action == MOBILE_ACTION_PUSH_PREVIEW_MODE && actionState.errorCode != null,
                            onPreviewChange = { mode ->
                                if (devicePreview) {
                                    onPushPreviewModeChange(mode.wireName)
                                } else {
                                    save(SettingsNotificationsSaveSection.Content, RaftNotificationAccountSettingsPatch(previewMode = mode))
                                }
                            },
                            onSave = { patch -> save(SettingsNotificationsSaveSection.Content, patch) }
                        )
                    }
                    if (servers.isNotEmpty()) {
                        item { SettingsNotificationServersSection(servers) }
                    }
                    if (currentServer != null && onOpenServerNotifications != null) {
                        item { SettingsNotificationServerLink(currentServer, onOpenServerNotifications) }
                    }
                } else {
                    item {
                        SettingsServerNotificationModeCard(
                            snapshot = snapshot,
                            actionState = actionState,
                            onServerNotificationModeChange = onServerNotificationModeChange
                        )
                    }
                    if (raftPlatformSupportsPushPreviewPrivacySetting() && snapshot.pushPreviewMode.available) {
                        item {
                            Box(Modifier.fillMaxWidth().settingsSearchAnchor("notification-preview", SettingsSearchCardShape)) {
                                SettingsPushPreviewModeCard(
                                    snapshot = snapshot,
                                    actionState = actionState,
                                    onPushPreviewModeChange = onPushPreviewModeChange
                                )
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
}

/** The card a failed account save shows its warning in. */
private enum class SettingsNotificationsSaveSection { Schedule, Content }

/**
 * This device (web `ThisDeviceCard`): what this device can do. The system permission, the device's
 * own switches and a test notification through the real push path.
 */
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsNotificationsSectionHeader(RaftLucideIconName.Bell, stringResource(Res.string.settings_notifications_device_section))
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
        RaftListRowGroup {
            Box(Modifier.fillMaxWidth().settingsSearchAnchor("push-notifications")) {
                SettingsNotificationSwitchRow(
                    title = stringResource(Res.string.settings_notifications_message_title),
                    detail = stringResource(Res.string.settings_notifications_message_detail),
                    checked = notificationPreferenceSnapshot.enabled,
                    enabled = !preferenceBusy,
                    divider = false,
                    onCheckedChange = onNotificationPreferenceChange
                )
            }
            val backgroundConnection = remember { RaftBackgroundConnectionControlsRegistry.offeredControls() }
            if (backgroundConnection != null) {
                // Android without Google Play services: the same preference keeps the realtime connection
                // alive from a foreground service, the only notification path such a device has.
                SettingsNotificationSwitchRow(
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
                    RaftBackgroundConnectionBatteryRow(backgroundConnection)
                }
            } else if (raftPlatformSupportsBackgroundRealtime()) {
                SettingsNotificationSwitchRow(
                    title = stringResource(Res.string.settings_notifications_background_realtime_title),
                    detail = stringResource(Res.string.settings_notifications_background_realtime_detail, raftPlatformProductName()),
                    checked = notificationPreferenceSnapshot.foregroundKeepAliveEnabled,
                    enabled = !foregroundKeepAliveBusy,
                    onCheckedChange = onForegroundKeepAlivePreferenceChange
                )
            }
            SettingsNotificationSwitchRow(
                title = stringResource(Res.string.settings_notifications_offline_computers_title),
                detail = stringResource(Res.string.settings_notifications_offline_computers_detail),
                checked = notificationPreferenceSnapshot.offlineComputerAlertsEnabled,
                enabled = !offlineComputerAlertsBusy,
                onCheckedChange = onOfflineComputerAlertsChange
            )
            SettingsNotificationSwitchRow(
                title = stringResource(Res.string.settings_foreground_banners_title),
                detail = stringResource(Res.string.settings_foreground_banners_detail),
                checked = snapshot.foregroundMessageBanners.enabled,
                enabled = snapshot.foregroundMessageBanners.available && !foregroundMessageBannersBusy,
                onCheckedChange = onForegroundMessageBannersChange
            )
            SettingsNotificationActionRow(
                title = stringResource(Res.string.settings_notifications_test_title),
                detail = snapshot.testNotification.localizedTestNotificationDetail(),
                status = snapshot.testNotification.localizedTestNotificationStatus(),
                enabled = snapshot.testNotification.available && snapshot.sendTestEnabled && !anyActionBusy,
                onClick = onTest
            )
        }
        if (actionState?.action in setOf(
                MOBILE_ACTION_NOTIFICATION_PREFERENCE,
                MOBILE_ACTION_FOREGROUND_KEEP_ALIVE,
                MOBILE_ACTION_OFFLINE_COMPUTER_ALERTS,
                MOBILE_ACTION_FOREGROUND_MESSAGE_BANNERS,
                MOBILE_ACTION_TEST_NOTIFICATION
            ) && actionState?.errorCode != null
        ) {
            SettingsFormErrorText(actionState)
        }
    }
}

/** A device setting that applies at once: ListRow plain with its switch (web `ListRow` + `Switch`). */
@Composable
private fun SettingsNotificationSwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    divider: Boolean = true
) {
    RaftListRow(
        title = title,
        variant = RaftListRowVariant.Plain,
        titleMaxLines = 2,
        description = detail,
        wrapDescription = true,
        divider = divider,
        trailing = { RaftSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled, label = title) }
    )
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
                    .raftClickable(onClick = onSettings, enabled = settingsAvailable && !busy, tier = RaftHoverTier.Subtle),
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
private fun SettingsNotificationActionRow(
    title: String,
    detail: String,
    status: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    RaftListRow(
        title = title,
        variant = RaftListRowVariant.Plain,
        titleMaxLines = 2,
        description = detail,
        wrapDescription = true,
        divider = true,
        enabled = enabled,
        onClick = onClick,
        trailing = {
            RaftListRowValue(status)
            RaftLucideIcon(RaftLucideIconName.Send, modifier = Modifier.size(18.dp), color = raftArgbHex(RaftTheme.colors.foregroundHint))
        }
    )
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
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
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
        RaftSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
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
 * The PATCH for picking [option], or null when it is already the stored wire value. An unknown
 * stored value shows as Inactive, so picking Inactive still writes it.
 */
internal fun settingsMobileDelayPatch(
    settings: RaftNotificationAccountSettings,
    option: RaftNotificationMobileDelay
): RaftNotificationAccountSettingsPatch? =
    if (settings.mobileDelay == null || settings.mobileDelay == option.wireName) {
        null
    } else {
        RaftNotificationAccountSettingsPatch(mobileDelay = option)
    }

@Composable
internal fun RaftNotificationMobileDelay.localizedMobileDelayLabel(): String =
    when (this) {
        RaftNotificationMobileDelay.Always -> stringResource(Res.string.settings_notifications_mobile_delay_always)
        RaftNotificationMobileDelay.Inactive -> stringResource(Res.string.settings_notifications_mobile_delay_inactive)
        RaftNotificationMobileDelay.Minutes1 -> stringResource(Res.string.settings_notifications_mobile_delay_minute_1)
        else -> stringResource(Res.string.settings_notifications_mobile_delay_minutes, minutes ?: 0)
    }
