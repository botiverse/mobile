package ai.slock.android.feature.settings

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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.slock.android.ui.components.SlockEmptyState
import ai.slock.android.ui.components.SlockErrorState
import ai.slock.android.ui.components.SlockIcons
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow

private val SettingsPanelRowMinHeight = 72.dp
private val SettingsPanelIconBoxSize = 38.dp
private val SettingsPanelIconSize = 18.dp
private val SettingsActionMinHeight = 30.dp

data class SettingsSessionItem(
    val id: String,
    val deviceName: String,
    val browserLabel: String,
    val locationLabel: String,
    val lastActiveLabel: String,
    val current: Boolean = false,
    val revokeEnabled: Boolean = true,
    val revokeLoading: Boolean = false,
    val errorCode: String? = null
)

data class SettingsDangerItem(
    val key: String,
    val title: String,
    val subtitle: String,
    val actionLabel: String,
    val confirmationRequired: Boolean = false,
    val enabled: Boolean = true,
    val noPermission: Boolean = false,
    val errorCode: String? = null
)

@Composable
fun SettingsSessionsDangerScreen(
    sessions: List<SettingsSessionItem>,
    dangerItems: List<SettingsDangerItem>,
    modifier: Modifier = Modifier,
    showSessionError: Boolean = false,
    showOuterBorder: Boolean = true,
    onRevokeSession: (String) -> Unit = {},
    onDangerAction: (String) -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
            .then(
                if (showOuterBorder) {
                    Modifier.border(SlockStroke.Default, SlockColor.BorderBlack)
                } else {
                    Modifier
                }
            )
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        SettingsPanelSectionHeader(
            title = "Sessions",
            subtitle = "Signed-in devices and browser sessions"
        )
        when {
            showSessionError -> SlockErrorState(
                title = "Could not load sessions",
                detail = "Try again before revoking devices.",
                retryLabel = "Retry",
                onRetry = {}
            )
            sessions.isEmpty() -> SlockEmptyState(
                title = "No active sessions",
                detail = "New browser and device sessions will appear here."
            )
            else -> sessions.forEach { session ->
                SettingsSessionRow(
                    item = session,
                    onRevoke = { onRevokeSession(session.id) }
                )
            }
        }

        SettingsPanelSectionHeader(
            title = "Danger Zone",
            subtitle = "Destructive actions require confirmation"
        )
        dangerItems.forEach { item ->
            SettingsDangerRow(
                item = item,
                onAction = { onDangerAction(item.key) }
            )
        }
    }
}

@Composable
private fun SettingsPanelSectionHeader(
    title: String,
    subtitle: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title.uppercase(),
            style = SlockType.SectionLabel,
            color = SlockColor.TextBlack
        )
        Text(
            text = subtitle,
            style = SlockType.Caption,
            color = SlockColor.TextMutedGray
        )
    }
}

@Composable
fun SettingsSessionRow(
    item: SettingsSessionItem,
    modifier: Modifier = Modifier,
    onRevoke: () -> Unit = {}
) {
    SettingsPanelRow(
        icon = SlockIcons.Square,
        title = item.deviceName,
        subtitle = "${item.browserLabel} · ${item.locationLabel} · ${item.lastActiveLabel}",
        badge = if (item.current) "Current" else null,
        iconBackground = if (item.current) SlockColor.AccentCyan else SlockColor.BrandYellow,
        errorCode = item.errorCode,
        modifier = modifier,
        action = {
            SettingsSmallActionButton(
                label = if (item.revokeLoading) "Revoking" else "Revoke",
                enabled = item.revokeEnabled && !item.current && !item.revokeLoading,
                destructive = true,
                onClick = onRevoke
            )
        }
    )
}

@Composable
fun SettingsDangerRow(
    item: SettingsDangerItem,
    modifier: Modifier = Modifier,
    onAction: () -> Unit = {}
) {
    val disabledReason = when {
        item.noPermission -> "No permission"
        item.confirmationRequired -> "Confirmation required"
        else -> null
    }
    SettingsPanelRow(
        icon = SlockIcons.LogOut,
        title = item.title,
        subtitle = if (disabledReason == null) item.subtitle else "${item.subtitle} · $disabledReason",
        badge = if (item.confirmationRequired) "Confirm" else null,
        iconBackground = SlockColor.AccentRed,
        errorCode = item.errorCode,
        modifier = modifier,
        action = {
            SettingsSmallActionButton(
                label = item.actionLabel,
                enabled = item.enabled && !item.noPermission && !item.confirmationRequired,
                destructive = true,
                onClick = onAction
            )
        }
    )
}

@Composable
private fun SettingsPanelRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconBackground: Color,
    modifier: Modifier = Modifier,
    badge: String? = null,
    errorCode: String? = null,
    action: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SettingsPanelRowMinHeight)
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        Box(
            modifier = Modifier
                .size(SettingsPanelIconBoxSize)
                .background(iconBackground)
                .border(SlockStroke.Default, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = SlockColor.TextBlack,
                modifier = Modifier.size(SettingsPanelIconSize)
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
                    text = title,
                    style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (badge != null) {
                    SettingsInlineBadge(badge)
                }
            }
            Text(
                text = subtitle,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (errorCode != null) {
                Text(
                    text = "Error: $errorCode",
                    style = SlockType.Caption,
                    color = SlockColor.AccentRed,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        action()
    }
}

@Composable
private fun SettingsInlineBadge(label: String) {
    Text(
        text = label,
        style = SlockType.Caption,
        color = SlockColor.TextBlack,
        modifier = Modifier
            .background(SlockColor.AccentLime)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .padding(horizontal = SlockSpacing.Sm, vertical = 2.dp)
    )
}

@Composable
private fun SettingsSmallActionButton(
    label: String,
    enabled: Boolean,
    destructive: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = label,
        style = SlockType.Caption,
        color = when {
            !enabled -> SlockColor.TextMutedGray
            destructive -> SlockColor.TextBlack
            else -> SlockColor.TextBlack
        },
        modifier = Modifier
            .heightIn(min = SettingsActionMinHeight)
            .background(
                when {
                    !enabled -> SlockColor.EmptyStateGray
                    destructive -> SlockColor.AccentRed
                    else -> SlockColor.SurfaceWhite
                }
            )
            .border(
                SlockStroke.Default,
                if (enabled) SlockColor.BorderBlack else SlockColor.TextMutedGray
            )
            .then(if (enabled) Modifier.slockHardShadow(SlockShadow.Small) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = SlockSpacing.Sm, vertical = 5.dp)
    )
}

private val sessionSamples = listOf(
    SettingsSessionItem(
        id = "current",
        deviceName = "Jiachengs MacBook Pro",
        browserLabel = "Chrome on macOS",
        locationLabel = "Shanghai",
        lastActiveLabel = "Active now",
        current = true
    ),
    SettingsSessionItem(
        id = "android",
        deviceName = "Nexus 5 Android browser with a very long device label",
        browserLabel = "Chrome Mobile",
        locationLabel = "Recent login",
        lastActiveLabel = "14 minutes ago"
    ),
    SettingsSessionItem(
        id = "failed",
        deviceName = "Old Safari session",
        browserLabel = "Safari",
        locationLabel = "Unknown location",
        lastActiveLabel = "3 days ago",
        errorCode = "revoke_failed"
    )
)

private val dangerSamples = listOf(
    SettingsDangerItem(
        key = "leave",
        title = "Leave server",
        subtitle = "Remove your account from this workspace.",
        actionLabel = "Leave"
    ),
    SettingsDangerItem(
        key = "delete",
        title = "Delete server",
        subtitle = "Permanently delete channels, messages, tasks, and members.",
        actionLabel = "Delete",
        confirmationRequired = true
    ),
    SettingsDangerItem(
        key = "disabled",
        title = "Transfer ownership",
        subtitle = "Only owners can transfer workspace ownership.",
        actionLabel = "Transfer",
        noPermission = true
    )
)

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun SettingsSessionsDangerPreview() {
    SlockTheme {
        SettingsSessionsDangerScreen(
            sessions = sessionSamples,
            dangerItems = dangerSamples
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SettingsSessionsDangerStatesPreview() {
    SlockTheme {
        Column(
            modifier = Modifier
                .background(SlockColor.AppBgLightBlue)
                .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            SettingsSessionsDangerScreen(
                sessions = emptyList(),
                dangerItems = dangerSamples.take(1),
                modifier = Modifier.heightIn(min = 360.dp)
            )
            SettingsSessionsDangerScreen(
                sessions = emptyList(),
                dangerItems = dangerSamples.takeLast(2),
                showSessionError = true,
                modifier = Modifier.heightIn(min = 360.dp)
            )
        }
    }
}
