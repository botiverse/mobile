package ai.slock.android.feature.settings

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.slock.android.core.data.repository.SettingsComputerSnapshot
import ai.slock.android.core.data.repository.SettingsComputersSnapshot
import ai.slock.android.core.data.repository.SettingsMobileSnapshot
import ai.slock.android.core.data.repository.SettingsMobileToggleSnapshot
import ai.slock.android.core.data.repository.SettingsSectionAvailability
import ai.slock.android.core.data.repository.SettingsServerProfileSnapshot
import ai.slock.android.core.data.repository.SettingsServerSectionSnapshot
import ai.slock.android.core.data.repository.SettingsServerSnapshot
import ai.slock.android.ui.components.SlockButton
import ai.slock.android.ui.components.SlockButtonVariant
import ai.slock.android.ui.components.SlockEmptyState
import ai.slock.android.ui.components.SlockIcons
import ai.slock.android.ui.components.SlockTextField
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow

@Composable
fun SettingsComputersScreen(
    snapshot: SettingsComputersSnapshot,
    modifier: Modifier = Modifier,
    onAddComputer: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
            .verticalScroll(rememberScrollState())
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        SlockButton(
            text = "Add computer",
            onClick = onAddComputer,
            modifier = Modifier.fillMaxWidth(),
            variant = SlockButtonVariant.Yellow,
            enabled = snapshot.canAddComputer
        )
        snapshot.addComputerUnavailableReason?.let {
            Text(
                text = it,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
        }
        if (snapshot.computers.isEmpty()) {
            SlockEmptyState(
                title = "No computers",
                detail = "Connected machines will appear here after the device management API is wired."
            )
        } else {
            snapshot.computers.forEach { computer ->
                ComputerCard(computer)
            }
        }
    }
}

@Composable
private fun ComputerCard(computer: SettingsComputerSnapshot) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = SlockShadow.Small.offsetX, bottom = SlockShadow.Small.offsetY)
            .slockHardShadow(SlockShadow.Small)
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .clickable(enabled = false, role = Role.Button, onClick = {})
            .padding(SlockSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
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
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
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
                text = computer.daemonVersion,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun OnlineDot(online: Boolean) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(
                color = if (online) SlockColor.AccentLime else SlockColor.EmptyStateGray,
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
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
            .verticalScroll(rememberScrollState())
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        SettingsSectionHeader(
            iconBackground = SlockColor.EmptyStateGray,
            label = "PROFILE"
        )
        ServerProfileSection(
            profile = snapshot.profile,
            onSaveProfile = onSaveProfile
        )
        snapshot.sections.forEach { section ->
            SettingsServerSectionCard(section)
        }
    }
}

@Composable
private fun ServerProfileSection(
    profile: SettingsServerProfileSnapshot,
    onSaveProfile: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = SlockShadow.Small.offsetX, bottom = SlockShadow.Small.offsetY)
            .slockHardShadow(SlockShadow.Small)
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        SlockTextField(
            value = profile.name,
            onValueChange = {},
            label = "Name",
            placeholder = "Server name",
            enabled = profile.canSave,
            reserveErrorSpace = false
        )
        SlockTextField(
            value = profile.slug,
            onValueChange = {},
            label = "Slug",
            placeholder = "server-slug",
            enabled = false,
            reserveErrorSpace = false
        )
        SlockButton(
            text = "Save Profile",
            onClick = onSaveProfile,
            modifier = Modifier.fillMaxWidth(),
            variant = SlockButtonVariant.Pink,
            enabled = profile.canSave
        )
    }
}

@Composable
private fun SettingsServerSectionCard(section: SettingsServerSectionSnapshot) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
                text = section.title,
                style = SlockType.Button.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextBlack,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            SettingsStatusBadge(
                text = section.state.label,
                color = section.state.badgeColor
            )
        }
        Text(
            text = section.detail,
            style = SlockType.Body,
            color = SlockColor.TextMutedGray
        )
        if (section.key == "danger") {
            SlockButton(
                text = "Delete Server",
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
                variant = SlockButtonVariant.Red,
                enabled = false
            )
        }
    }
}

@Composable
fun SettingsMobileScreen(
    snapshot: SettingsMobileSnapshot,
    notificationPermissionGranted: Boolean,
    notificationPermissionAvailable: Boolean = true,
    modifier: Modifier = Modifier,
    onRequestNotificationPermission: () -> Unit = {},
    onTogglePush: () -> Unit = {},
    onSendTest: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onToggleMute: () -> Unit = {}
) {
    var pushEnabled by remember(snapshot, notificationPermissionGranted) {
        mutableStateOf(snapshot.pushNotifications.enabled && notificationPermissionGranted)
    }
    var feedback by remember(snapshot) {
        mutableStateOf<String?>(null)
    }
    var showSettingsAction by remember(snapshot) {
        mutableStateOf(false)
    }
    val pushToggle = snapshot.pushNotifications.copy(
        enabled = pushEnabled,
        available = true,
        statusLabel = when {
            pushEnabled -> "Enabled"
            !notificationPermissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> "Permission required"
            else -> "Disabled"
        },
        detail = if (pushEnabled) {
            "Android notification permission is enabled. Send Test Push will show a local test notification."
        } else {
            snapshot.pushNotifications.detail
        }
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
            .verticalScroll(rememberScrollState())
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        SettingsMobileCard(
            title = "Push Notifications",
            body = "DMs, direct mentions, and followed thread replies.",
            toggle = pushToggle,
            primaryLabel = if (pushEnabled) "Disable Notifications" else "Enable Notifications",
            onPrimary = {
                if (pushEnabled) {
                    pushEnabled = false
                    feedback = "Notifications disabled for this local sample."
                } else if (!notificationPermissionGranted) {
                    if (notificationPermissionAvailable) {
                        onRequestNotificationPermission()
                        feedback = "Requesting Android notification permission."
                    } else {
                        showSettingsAction = true
                        feedback = "Notification permission required. Open Android settings, then try again."
                    }
                } else {
                    pushEnabled = true
                    feedback = "Notifications enabled. Send Test Push can now show a local notification."
                }
                onTogglePush()
            }
        )
        SlockButton(
            text = "Send Test Push",
            onClick = {
                if (!notificationPermissionGranted) {
                    showSettingsAction = true
                    feedback = "Notification permission required before sending a test push."
                } else {
                    pushEnabled = true
                    feedback = "Sending local test notification."
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
                text = "Open System Settings",
                onClick = onOpenNotificationSettings,
                modifier = Modifier.fillMaxWidth(),
                variant = SlockButtonVariant.Outline
            )
        }
        SettingsMobileCard(
            title = "Mute this server",
            body = "Stops notifications from this server for your account. Other servers are unchanged.",
            toggle = snapshot.muteServer,
            primaryLabel = if (snapshot.muteServer.enabled) "Unmute Server" else "Mute Server",
            onPrimary = onToggleMute
        )
    }
}

@Composable
private fun SettingsSampleFeedback(text: String) {
    Text(
        text = text,
        style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
        color = SlockColor.TextBlack,
        modifier = Modifier
            .fillMaxWidth()
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = SlockShadow.Small.offsetX, bottom = SlockShadow.Small.offsetY)
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
                color = if (toggle.enabled) SlockColor.BrandYellow else SlockColor.EmptyStateGray
            )
        }
        Text(
            text = body,
            style = SlockType.Body,
            color = SlockColor.TextBlack
        )
        Text(
            text = toggle.detail,
            style = SlockType.Caption,
            color = SlockColor.TextMutedGray
        )
        SlockButton(
            text = primaryLabel,
            onClick = onPrimary,
            modifier = Modifier.fillMaxWidth(),
            variant = SlockButtonVariant.Outline,
            enabled = toggle.available
        )
    }
}

private val SettingsSectionAvailability.label: String
    get() = when (this) {
        SettingsSectionAvailability.Available -> "Available"
        SettingsSectionAvailability.Empty -> "Empty"
        SettingsSectionAvailability.Unavailable -> "Unavailable"
        SettingsSectionAvailability.NoPermission -> "No permission"
    }

private val SettingsSectionAvailability.badgeColor
    get() = when (this) {
        SettingsSectionAvailability.Available -> SlockColor.AccentLime
        SettingsSectionAvailability.Empty -> SlockColor.BrandYellow
        SettingsSectionAvailability.Unavailable -> SlockColor.EmptyStateGray
        SettingsSectionAvailability.NoPermission -> SlockColor.AccentOrange
    }

private val settingsComputersPreview = SettingsComputersSnapshot(
    computers = listOf(
        SettingsComputerSnapshot(
            id = "mac",
            name = "Jiachengs-MacBook-Pro.local",
            daemonVersion = "daemon v0.44.2",
            online = true
        )
    ),
    addComputerUnavailableReason = "Computer management API is not wired on Android yet."
)

private val settingsServerPreview = SettingsServerSnapshot(
    profile = SettingsServerProfileSnapshot("Slock Android", "slock-android"),
    sections = listOf(
        SettingsServerSectionSnapshot("admins", "Admins", "No admin endpoint is wired yet."),
        SettingsServerSectionSnapshot("billing", "Plan & Billing", "Billing is unavailable."),
        SettingsServerSectionSnapshot("danger", "Danger Zone", "Delete Server is disabled.", SettingsSectionAvailability.NoPermission)
    )
)

private val settingsMobilePreview = SettingsMobileSnapshot(
    pushNotifications = SettingsMobileToggleSnapshot(
        enabled = false,
        available = true,
        statusLabel = "Disabled",
        detail = "Notifications can arrive while Slock is in the background after wiring."
    ),
    muteServer = SettingsMobileToggleSnapshot(
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
    SlockTheme {
        SettingsComputersScreen(settingsComputersPreview)
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun SettingsServerManagementScreenPreview() {
    SlockTheme {
        SettingsServerManagementScreen(settingsServerPreview)
    }
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
