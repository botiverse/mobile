package ai.slock.android.feature.settings

import ai.slock.android.R
import ai.slock.android.core.data.preferences.MyMessageAlignmentPreference
import ai.slock.android.core.data.repository.SettingsComputerAgentSnapshot
import ai.slock.android.core.data.repository.SettingsComputerDetailSnapshot
import ai.slock.android.core.data.repository.SettingsComputerSnapshot
import ai.slock.android.core.data.repository.SettingsComputerWorkspaceSnapshot
import ai.slock.android.core.data.repository.SettingsComputersSnapshot
import ai.slock.android.core.data.repository.SettingsMobileSnapshot
import ai.slock.android.core.data.repository.SettingsMobileToggleSnapshot
import ai.slock.android.core.data.repository.SettingsPlanUsageSnapshot
import ai.slock.android.core.data.repository.SettingsSectionAvailability
import ai.slock.android.core.data.repository.SettingsSectionErrorSnapshot
import ai.slock.android.core.data.repository.SettingsServerAdminSnapshot
import ai.slock.android.core.data.repository.SettingsServerProfileSnapshot
import ai.slock.android.core.data.repository.SettingsServerSectionSnapshot
import ai.slock.android.core.data.repository.SettingsServerSnapshot
import ai.slock.android.ui.components.SlockButton
import ai.slock.android.ui.components.SlockButtonVariant
import ai.slock.android.ui.components.SlockEmptyState
import ai.slock.android.ui.components.SlockIcons
import ai.slock.android.ui.components.SlockSegmentOption
import ai.slock.android.ui.components.SlockTextField
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun SettingsComputersScreen(
        snapshot: SettingsComputersSnapshot,
        modifier: Modifier = Modifier,
        onAddComputer: () -> Unit = {},
        onOpenComputer: (String) -> Unit = {}
) {
    Column(
            modifier =
                    modifier.fillMaxSize()
                            .background(SlockColor.SurfaceWhite)
                            .verticalScroll(rememberScrollState())
                            .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        if (snapshot.canAddComputer) {
            SlockButton(
                    text = stringResource(R.string.settings_computers_add),
                    onClick = onAddComputer,
                    modifier = Modifier.fillMaxWidth(),
                    variant = SlockButtonVariant.Yellow
            )
        }
        if (snapshot.computers.isEmpty()) {
            SlockEmptyState(
                    title = stringResource(R.string.settings_computers_empty_title),
                    detail = stringResource(R.string.settings_computers_empty_detail)
            )
        } else {
            snapshot.computers.forEach { computer ->
                ComputerCard(computer = computer, onClick = { onOpenComputer(computer.id) })
            }
        }
    }
}

@Composable
private fun ComputerCard(computer: SettingsComputerSnapshot, onClick: () -> Unit) {
    Row(
            modifier =
                    Modifier.fillMaxWidth()
                            .padding(
                                    end = SlockShadow.Small.offsetX,
                                    bottom = SlockShadow.Small.offsetY
                            )
                            .slockHardShadow(SlockShadow.Small)
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .clickable(role = Role.Button, onClick = onClick)
                            .padding(SlockSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        Box(
                modifier =
                        Modifier.size(40.dp)
                                .background(SlockColor.BrandYellow)
                                .border(SlockStroke.Default, SlockColor.BorderBlack),
                contentAlignment = Alignment.Center
        ) {
            Icon(
                    imageVector = SlockIcons.Monitor,
                    contentDescription = null,
                    tint = SlockColor.TextBlack,
                    modifier = Modifier.size(20.dp)
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                Text(
                        text = computer.name,
                        style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                )
                OnlineDot(online = computer.online)
            }
            Text(
                    text =
                            if (computer.daemonVersion == "daemon unavailable") {
                                stringResource(R.string.settings_computers_daemon_unavailable)
                            } else {
                                computer.daemonVersion
                            },
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SettingsComputerDetailScreen(
        snapshot: SettingsComputerDetailSnapshot,
        modifier: Modifier = Modifier,
        onRetryWorkspaces: () -> Unit = {},
        onRetryAgents: () -> Unit = {}
) {
    Column(
            modifier =
                    modifier.fillMaxSize()
                            .background(SlockColor.SurfaceWhite)
                            .verticalScroll(rememberScrollState())
                            .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        ComputerCard(computer = snapshot.computer, onClick = {})
        SettingsSectionHeader(
                iconBackground = SlockColor.BrandYellow,
                label = stringResource(R.string.settings_computer_detail_info_section)
        )
        DetailRow(
                label = stringResource(R.string.settings_computer_detail_platform),
                value = snapshot.computer.platform
                                ?: stringResource(R.string.settings_computer_detail_unknown)
        )
        DetailRow(
                label = stringResource(R.string.settings_computer_detail_hostname),
                value = snapshot.computer.hostname ?: snapshot.computer.name
        )
        DetailRow(
                label = stringResource(R.string.settings_computer_detail_last_seen),
                value = snapshot.computer.lastSeenLabel
                                ?: stringResource(R.string.settings_computer_detail_unknown)
        )
        SettingsSectionHeader(
                iconBackground = SlockColor.AccentLime,
                label = stringResource(R.string.settings_computer_detail_workspaces_section)
        )
        if (snapshot.workspacesError != null) {
            SettingsSectionInlineError(
                    title =
                            stringResource(
                                    R.string.settings_computer_detail_workspaces_error_title
                            ),
                    error = snapshot.workspacesError,
                    onRetry = onRetryWorkspaces
            )
        } else if (snapshot.workspaces.isEmpty()) {
            SlockEmptyState(
                    title = stringResource(R.string.settings_computer_detail_no_workspaces),
                    detail = stringResource(R.string.settings_computer_detail_no_workspaces_detail)
            )
        } else {
            snapshot.workspaces.forEach { workspace -> WorkspaceCard(workspace) }
        }
        SettingsSectionHeader(
                iconBackground = SlockColor.AccentOrange,
                label = stringResource(R.string.settings_computer_detail_agents_section)
        )
        if (snapshot.agentsError != null) {
            SettingsSectionInlineError(
                    title = stringResource(R.string.settings_computer_detail_agents_error_title),
                    error = snapshot.agentsError,
                    onRetry = onRetryAgents
            )
        } else if (snapshot.agents.isEmpty()) {
            SlockEmptyState(
                    title = stringResource(R.string.settings_computer_detail_no_agents),
                    detail = stringResource(R.string.settings_computer_detail_no_agents_detail)
            )
        } else {
            snapshot.agents.forEach { agent -> AgentCard(agent) }
        }
        snapshot.sections.filterVisibleSettingsSections().forEach { section ->
            SettingsServerSectionCard(section)
        }
    }
}

@Composable
private fun SettingsSectionInlineError(
        title: String,
        error: SettingsSectionErrorSnapshot,
        onRetry: () -> Unit
) {
    Column(
            modifier =
                    Modifier.fillMaxWidth()
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(
                text = title,
                style = SlockType.Button.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextBlack
        )
        Text(
                text =
                        stringResource(
                                R.string.settings_computer_detail_section_error_detail,
                                error.code
                        ),
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
        )
        SlockButton(
                text = stringResource(R.string.settings_retry),
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
                variant = SlockButtonVariant.Outline,
                enabled = error.retryable
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
            modifier =
                    Modifier.fillMaxWidth()
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .padding(SlockSpacing.Md),
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        Text(
                text = label,
                style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextMutedGray,
                modifier = Modifier.weight(0.38f)
        )
        Text(
                text = value,
                style = SlockType.Caption,
                color = SlockColor.TextBlack,
                modifier = Modifier.weight(0.62f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun WorkspaceCard(workspace: SettingsComputerWorkspaceSnapshot) {
    Column(
            modifier =
                    Modifier.fillMaxWidth()
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
                text = workspace.name,
                style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
        )
        Text(
                text = workspace.path,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
        )
        workspace.statusLabel?.let {
            Text(text = it, style = SlockType.Caption, color = SlockColor.TextMutedGray)
        }
    }
}

@Composable
private fun AgentCard(agent: SettingsComputerAgentSnapshot) {
    Column(
            modifier =
                    Modifier.fillMaxWidth()
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
                text = agent.name,
                style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
        )
        Text(
                text =
                        listOfNotNull(agent.statusLabel, agent.ownerLabel)
                                .joinToString(" / ")
                                .ifBlank {
                                    stringResource(R.string.settings_computer_detail_unknown)
                                },
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun OnlineDot(online: Boolean) {
    Box(
            modifier =
                    Modifier.size(10.dp)
                            .background(
                                    color =
                                            if (online) SlockColor.AccentLime
                                            else SlockColor.EmptyStateGray,
                                    shape = CircleShape
                            )
                            .border(SlockStroke.Thin, SlockColor.BorderBlack, CircleShape)
    )
}

@Composable
fun SettingsServerManagementScreen(
        snapshot: SettingsServerSnapshot,
        modifier: Modifier = Modifier,
        onSaveProfile: () -> Unit = {}
) {
    Column(
            modifier =
                    modifier.fillMaxSize()
                            .background(SlockColor.SurfaceWhite)
                            .imePadding()
                            .verticalScroll(rememberScrollState())
                            .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        SettingsSectionHeader(
                iconBackground = SlockColor.EmptyStateGray,
                label = stringResource(R.string.settings_server_profile_section)
        )
        ServerProfileSection(profile = snapshot.profile, onSaveProfile = onSaveProfile)
        snapshot.sections.filterVisibleSettingsSections().forEach { section ->
            SettingsServerSectionCard(
                    section = section,
                    admins = snapshot.admins,
                    usageRows = snapshot.usageRows,
                    onboardingStatus = snapshot.onboarding?.statusLabel
            )
        }
    }
}

@Composable
private fun ServerProfileSection(
        profile: SettingsServerProfileSnapshot,
        onSaveProfile: () -> Unit
) {
    Column(
            modifier =
                    Modifier.fillMaxWidth()
                            .padding(
                                    end = SlockShadow.Small.offsetX,
                                    bottom = SlockShadow.Small.offsetY
                            )
                            .slockHardShadow(SlockShadow.Small)
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        SlockTextField(
                value = profile.name,
                onValueChange = {},
                label = stringResource(R.string.settings_server_name_label),
                placeholder = stringResource(R.string.settings_server_name_placeholder),
                enabled = profile.canSave,
                reserveErrorSpace = false
        )
        SlockTextField(
                value = profile.slug,
                onValueChange = {},
                label = stringResource(R.string.settings_server_slug_label),
                placeholder = stringResource(R.string.settings_server_slug_placeholder),
                enabled = false,
                reserveErrorSpace = false
        )
        SlockButton(
                text = stringResource(R.string.settings_server_save_profile),
                onClick = onSaveProfile,
                modifier = Modifier.fillMaxWidth(),
                variant = SlockButtonVariant.Pink,
                enabled = profile.canSave
        )
    }
}

@Composable
private fun SettingsServerSectionCard(
        section: SettingsServerSectionSnapshot,
        admins: List<SettingsServerAdminSnapshot> = emptyList(),
        usageRows: List<SettingsPlanUsageSnapshot> = emptyList(),
        onboardingStatus: String? = null
) {
    Column(
            modifier =
                    Modifier.fillMaxWidth()
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            Text(
                    text = section.localizedTitle(),
                    style = SlockType.Button.copy(fontWeight = FontWeight.Bold),
                    color = SlockColor.TextBlack,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
            SettingsStatusBadge(
                    text = stringResource(section.state.labelRes),
                    color = section.state.badgeColor
            )
        }
        Text(
                text = section.localizedDetail(),
                style = SlockType.Body,
                color = SlockColor.TextMutedGray
        )
        when (section.key) {
            "admins" -> SettingsServerAdminRows(admins)
            "billing" -> SettingsServerUsageRows(usageRows)
            "pending_invites" ->
                    if (section.state == SettingsSectionAvailability.Empty) {
                        Text(
                                text = stringResource(R.string.settings_server_no_pending_invites),
                                style = SlockType.Caption,
                                color = SlockColor.TextMutedGray
                        )
                    }
            "join_links" ->
                    if (section.state == SettingsSectionAvailability.Empty) {
                        Text(
                                text = stringResource(R.string.settings_server_join_links_not_confirmed),
                                style = SlockType.Caption,
                                color = SlockColor.TextMutedGray
                        )
                    }
            "onboarding_agent" ->
                    onboardingStatus?.let {
                        SettingsStatusBadge(text = it, color = SlockColor.AppBgLightBlue)
                    }
        }
        if (section.key == "danger") {
            SlockButton(
                    text = stringResource(R.string.settings_server_delete),
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                    variant = SlockButtonVariant.Red,
                    enabled = false
            )
        }
    }
}

@Composable
private fun SettingsServerAdminRows(admins: List<SettingsServerAdminSnapshot>) {
    if (admins.isEmpty()) {
        Text(
                text = stringResource(R.string.settings_server_no_admins),
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
        )
        return
    }
    admins.forEach { admin ->
        Row(
                modifier =
                        Modifier.fillMaxWidth()
                                .background(SlockColor.AppBgLightBlue)
                                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                                .padding(SlockSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                        text = admin.name,
                        style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                )
                admin.detail?.let {
                    Text(
                            text = it,
                            style = SlockType.Caption,
                            color = SlockColor.TextMutedGray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                    )
                }
            }
            admin.role?.let { SettingsStatusBadge(text = it, color = SlockColor.BrandYellow) }
        }
    }
}

@Composable
private fun SettingsServerUsageRows(rows: List<SettingsPlanUsageSnapshot>) {
    rows.forEach { row ->
        Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            Text(
                    text = row.label,
                    style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
                    color = SlockColor.TextBlack,
                    modifier = Modifier.weight(1f)
            )
            Text(text = row.value, style = SlockType.Caption, color = SlockColor.TextMutedGray)
        }
    }
}

@Composable
fun SettingsMobileScreen(
        snapshot: SettingsMobileSnapshot,
        notificationPermissionGranted: Boolean,
        notificationPermissionAvailable: Boolean = true,
        modifier: Modifier = Modifier,
        chatLayoutPreference: MyMessageAlignmentPreference = MyMessageAlignmentPreference.Default,
        onRequestNotificationPermission: () -> Unit = {},
        onChatLayoutPreferenceSelected: (MyMessageAlignmentPreference) -> Unit = {},
        onTogglePush: () -> Unit = {},
        onSendTest: () -> Unit = {},
        onOpenNotificationSettings: () -> Unit = {},
        onToggleMute: () -> Unit = {}
) {
    var pushEnabled by
            remember(snapshot, notificationPermissionGranted) {
                mutableStateOf(snapshot.pushNotifications.enabled && notificationPermissionGranted)
            }
    var feedback by remember(snapshot) { mutableStateOf<String?>(null) }
    var showSettingsAction by remember(snapshot) { mutableStateOf(false) }
    val permissionGrantedFeedback =
            stringResource(R.string.settings_mobile_feedback_permission_granted)
    val notificationsDisabledFeedback =
            stringResource(R.string.settings_mobile_feedback_notifications_disabled)
    val requestingPermissionFeedback =
            stringResource(R.string.settings_mobile_feedback_requesting_permission)
    val permissionRequiredSettingsFeedback =
            stringResource(R.string.settings_mobile_feedback_permission_required_settings)
    val notificationsEnabledFeedback =
            stringResource(R.string.settings_mobile_feedback_notifications_enabled)
    val permissionRequiredSendFeedback =
            stringResource(R.string.settings_mobile_feedback_permission_required_send)
    val sendingTestFeedback = stringResource(R.string.settings_mobile_feedback_sending_test)
    LaunchedEffect(notificationPermissionGranted) {
        if (notificationPermissionGranted) {
            pushEnabled = true
            showSettingsAction = false
            feedback = permissionGrantedFeedback
        }
    }
    val pushEnabledDetail = stringResource(R.string.settings_mobile_push_enabled_detail)
    val pushDisabledDetail = stringResource(R.string.settings_mobile_push_disabled_detail)
    val muteToggle =
            snapshot.muteServer.copy(
                    statusLabel =
                            if (snapshot.muteServer.enabled) {
                                stringResource(R.string.settings_mobile_mute_status_muted)
                            } else {
                                stringResource(R.string.settings_mobile_mute_status_not_muted)
                            },
                    detail = stringResource(R.string.settings_mobile_mute_detail)
            )
    val pushToggle =
            snapshot.pushNotifications.copy(
                    enabled = pushEnabled,
                    available = true,
                    statusLabel =
                            when {
                                pushEnabled ->
                                        stringResource(R.string.settings_mobile_status_enabled)
                                !notificationPermissionGranted &&
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                                        stringResource(
                                                R.string.settings_mobile_status_permission_required
                                        )
                                else -> stringResource(R.string.settings_mobile_status_disabled)
                            },
                    detail =
                            if (pushEnabled) {
                                pushEnabledDetail
                            } else {
                                pushDisabledDetail
                            }
            )
    Column(
            modifier =
                    modifier.fillMaxSize()
                            .background(SlockColor.SurfaceWhite)
                            .verticalScroll(rememberScrollState())
                            .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        SettingsMobileCard(
                title = stringResource(R.string.settings_mobile_push_title),
                body = stringResource(R.string.settings_mobile_push_body),
                toggle = pushToggle,
                primaryLabel =
                        if (pushEnabled) {
                            stringResource(R.string.settings_mobile_disable_notifications)
                        } else {
                            stringResource(R.string.settings_mobile_enable_notifications)
                        },
                onPrimary = {
                    if (pushEnabled) {
                        pushEnabled = false
                        feedback = notificationsDisabledFeedback
                    } else if (!notificationPermissionGranted) {
                        if (notificationPermissionAvailable) {
                            onRequestNotificationPermission()
                            feedback = requestingPermissionFeedback
                        } else {
                            showSettingsAction = true
                            feedback = permissionRequiredSettingsFeedback
                        }
                    } else {
                        pushEnabled = true
                        feedback = notificationsEnabledFeedback
                    }
                    onTogglePush()
                }
        )
        SlockButton(
                text = stringResource(R.string.settings_mobile_send_test),
                onClick = {
                    if (!notificationPermissionGranted) {
                        showSettingsAction = true
                        feedback = permissionRequiredSendFeedback
                    } else {
                        pushEnabled = true
                        feedback = sendingTestFeedback
                        onSendTest()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                variant = SlockButtonVariant.Yellow,
                enabled = true
        )
        feedback?.let { SettingsSampleFeedback(it) }
        if (showSettingsAction) {
            SlockButton(
                    text = stringResource(R.string.settings_mobile_open_system_settings),
                    onClick = onOpenNotificationSettings,
                    modifier = Modifier.fillMaxWidth(),
                    variant = SlockButtonVariant.Outline
            )
        }
        SettingsMobileChatLayoutPreference(
                selected = chatLayoutPreference,
                onSelected = onChatLayoutPreferenceSelected
        )
        if (snapshot.muteServer.available) {
            SettingsMobileCard(
                    title = stringResource(R.string.settings_mobile_mute_title),
                    body = stringResource(R.string.settings_mobile_mute_body),
                    toggle = muteToggle,
                    primaryLabel =
                            if (snapshot.muteServer.enabled) {
                                stringResource(R.string.settings_mobile_unmute_server)
                            } else {
                                stringResource(R.string.settings_mobile_mute_server)
                            },
                    onPrimary = onToggleMute
            )
        }
    }
}

@Composable
private fun SettingsMobileChatLayoutPreference(
        selected: MyMessageAlignmentPreference,
        onSelected: (MyMessageAlignmentPreference) -> Unit,
        modifier: Modifier = Modifier
) {
    Column(
            modifier =
                    modifier.fillMaxWidth()
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(
                text = stringResource(R.string.settings_account_chat_layout_section).uppercase(),
                style = SlockType.SectionLabel,
                color = SlockColor.TextBlack
        )
        Text(
                text = stringResource(R.string.settings_account_chat_layout_detail),
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
        )
        Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            MyMessageAlignmentPreference.entries.forEach { option ->
                SlockSegmentOption(
                        text =
                                when (option) {
                                    MyMessageAlignmentPreference.Left ->
                                            stringResource(
                                                    R.string.settings_account_chat_layout_left
                                            )
                                    MyMessageAlignmentPreference.Right ->
                                            stringResource(
                                                    R.string.settings_account_chat_layout_right
                                            )
                                },
                        selected = option == selected,
                        onSelected = { onSelected(option) },
                        modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SettingsSampleFeedback(text: String) {
    Text(
            text = text,
            style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack,
            modifier =
                    Modifier.fillMaxWidth()
                            .background(SlockColor.BrandYellow)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .padding(SlockSpacing.Md)
    )
}

@Composable
private fun SettingsMobileCard(
        title: String,
        body: String,
        toggle: SettingsMobileToggleSnapshot,
        primaryLabel: String,
        onPrimary: () -> Unit
) {
    Column(
            modifier =
                    Modifier.fillMaxWidth()
                            .padding(
                                    end = SlockShadow.Small.offsetX,
                                    bottom = SlockShadow.Small.offsetY
                            )
                            .slockHardShadow(SlockShadow.Small)
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            Text(
                    text = title,
                    style = SlockType.Button.copy(fontWeight = FontWeight.Bold),
                    color = SlockColor.TextBlack,
                    modifier = Modifier.weight(1f)
            )
            SettingsStatusBadge(
                    text = toggle.statusLabel,
                    color =
                            if (toggle.enabled) SlockColor.BrandYellow
                            else SlockColor.EmptyStateGray
            )
        }
        Text(text = body, style = SlockType.Body, color = SlockColor.TextBlack)
        Text(text = toggle.detail, style = SlockType.Caption, color = SlockColor.TextMutedGray)
        SlockButton(
                text = primaryLabel,
                onClick = onPrimary,
                modifier = Modifier.fillMaxWidth(),
                variant = SlockButtonVariant.Outline,
                enabled = toggle.available
        )
    }
}

@Composable
private fun SettingsServerSectionSnapshot.localizedTitle(): String =
        when (key) {
            "admins" -> stringResource(R.string.settings_server_admins_title)
            "billing" -> stringResource(R.string.settings_server_billing_title)
            "pending_invites" -> stringResource(R.string.settings_server_pending_invites_title)
            "join_links" -> stringResource(R.string.settings_server_join_links_title)
            "onboarding_agent" -> stringResource(R.string.settings_server_onboarding_agent_title)
            "danger" -> stringResource(R.string.settings_server_danger_title)
            else -> title
        }

@Composable
private fun SettingsServerSectionSnapshot.localizedDetail(): String =
        detail.takeIf { it.isNotBlank() }
                ?: when (key) {
                    "admins" -> stringResource(R.string.settings_server_admins_unavailable_detail)
                    "billing" -> stringResource(R.string.settings_server_billing_unavailable_detail)
                    "pending_invites" ->
                            stringResource(
                                    R.string.settings_server_pending_invites_unavailable_detail
                            )
                    "join_links" ->
                            stringResource(R.string.settings_server_join_links_unavailable_detail)
                    "onboarding_agent" ->
                            stringResource(
                                    R.string.settings_server_onboarding_agent_unavailable_detail
                            )
                    "danger" -> stringResource(R.string.settings_server_danger_unavailable_detail)
                    else -> detail
                }

private fun List<SettingsServerSectionSnapshot>.filterVisibleSettingsSections():
        List<SettingsServerSectionSnapshot> = filter {
    it.state != SettingsSectionAvailability.Unavailable &&
            !it.key.equals("browser", ignoreCase = true) &&
            !it.title.contains("browser", ignoreCase = true)
}

private val SettingsSectionAvailability.labelRes: Int
    get() =
            when (this) {
                SettingsSectionAvailability.Available -> R.string.settings_section_available
                SettingsSectionAvailability.Empty -> R.string.settings_section_empty
                SettingsSectionAvailability.Unavailable -> R.string.settings_section_unavailable
                SettingsSectionAvailability.NoPermission -> R.string.settings_section_no_permission
            }

private val SettingsSectionAvailability.badgeColor
    get() =
            when (this) {
                SettingsSectionAvailability.Available -> SlockColor.AccentLime
                SettingsSectionAvailability.Empty -> SlockColor.BrandYellow
                SettingsSectionAvailability.Unavailable -> SlockColor.EmptyStateGray
                SettingsSectionAvailability.NoPermission -> SlockColor.AccentOrange
            }

private val settingsComputersPreview =
        SettingsComputersSnapshot(
                computers =
                        listOf(
                                SettingsComputerSnapshot(
                                        id = "mac",
                                        name = "Jiachengs-MacBook-Pro.local",
                                        daemonVersion = "daemon v0.44.2",
                                        online = true,
                                        hostname = "Jiachengs-MacBook-Pro.local",
                                        platform = "macOS",
                                        lastSeenLabel = "Active now"
                                )
                        ),
                addComputerUnavailableReason =
                        "Computer management API is not wired on Android yet."
        )

private val settingsServerPreview =
        SettingsServerSnapshot(
                profile = SettingsServerProfileSnapshot("Slock Android", "slock-android"),
                sections =
                        listOf(
                                SettingsServerSectionSnapshot(
                                        "admins",
                                        "Admins",
                                        "No admin endpoint is wired yet."
                                ),
                                SettingsServerSectionSnapshot(
                                        "billing",
                                        "Plan & Billing",
                                        "Billing is unavailable."
                                ),
                                SettingsServerSectionSnapshot(
                                        "danger",
                                        "Danger Zone",
                                        "Delete Server is disabled.",
                                        SettingsSectionAvailability.NoPermission
                                )
                        )
        )

private val settingsMobilePreview =
        SettingsMobileSnapshot(
                pushNotifications =
                        SettingsMobileToggleSnapshot(
                                enabled = false,
                                available = true,
                                statusLabel = "Disabled",
                                detail =
                                        "Notifications can arrive while Slock is in the background after wiring."
                        ),
                muteServer =
                        SettingsMobileToggleSnapshot(
                                enabled = false,
                                available = false,
                                statusLabel = "Not muted",
                                detail = "Server mute is not wired yet."
                        ),
                sendTestEnabled = true
        )

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun SettingsComputersScreenPreview() {
    SlockTheme { SettingsComputersScreen(settingsComputersPreview) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun SettingsServerManagementScreenPreview() {
    SlockTheme { SettingsServerManagementScreen(settingsServerPreview) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun SettingsMobileScreenPreview() {
    SlockTheme {
        SettingsMobileScreen(
                snapshot = settingsMobilePreview,
                notificationPermissionGranted = false
        )
    }
}
