package ai.slock.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.slock.android.ui.components.SlockIcons
import ai.slock.android.ui.components.SlockTitledPageHeader
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.ui.theme.SlockType

private val SettingsRowMinHeight = 56.dp
private val SettingsIconBoxSize = 32.dp
private val SettingsIconSize = 18.dp
private val SettingsChevronSize = 16.dp

data class SettingsHomeItem(
    val key: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val value: String? = null,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val interactionState: SettingsRowInteractionState = SettingsRowInteractionState.Default
)

enum class SettingsRowInteractionState {
    Default,
    Focused,
    Pressed
}

@Composable
fun SettingsHomeScreen(
    items: List<SettingsHomeItem>,
    modifier: Modifier = Modifier,
    title: String = "Settings",
    subtitle: String = "Slock Android",
    showSelection: Boolean = true,
    onItemClick: (String) -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
        SlockTitledPageHeader(title = title)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val stroke = SlockStroke.Default.toPx()
                    drawLine(
                        color = SlockColor.BorderBlack,
                        start = Offset(0f, size.height - stroke / 2f),
                        end = Offset(size.width, size.height - stroke / 2f),
                        strokeWidth = stroke
                    )
                }
        ) {
            items.forEach { item ->
                SettingsNavRow(
                    item = if (showSelection) item else item.copy(selected = false),
                    onClick = { onItemClick(item.key) }
                )
            }
        }
    }
}

@Composable
fun SettingsNavRow(
    item: SettingsHomeItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val borderColor = if (item.enabled) SlockColor.BorderBlack else SlockColor.TextMutedGray
    val backgroundColor = when {
        !item.enabled -> SlockColor.EmptyStateGray
        item.interactionState == SettingsRowInteractionState.Pressed -> SlockColor.EmptyStateGray
        item.selected -> SlockColor.AccentCyan
        else -> SlockColor.SurfaceWhite
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SettingsRowMinHeight)
            .background(backgroundColor)
            .drawBehind {
                val stroke = SlockStroke.Default.toPx()
                drawLine(
                    color = borderColor,
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width, size.height - stroke / 2f),
                    strokeWidth = stroke
                )
            }
            .clickable(enabled = item.enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        Box(
            modifier = Modifier
                .size(SettingsIconBoxSize),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = if (item.enabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
                modifier = Modifier.size(SettingsIconSize)
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = item.title,
                style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                color = if (item.enabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.subtitle,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (item.value != null) {
            Text(
                text = item.value,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.34f, fill = false)
            )
            Spacer(modifier = Modifier.width(SlockSpacing.Xs))
        }
        Icon(
            imageVector = SlockIcons.ChevronRight,
            contentDescription = null,
            tint = if (item.enabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
            modifier = Modifier.size(SettingsChevronSize)
        )
    }
}

private val settingsHomeSampleItems = listOf(
    SettingsHomeItem(
        key = "account",
        title = "Account",
        subtitle = "Profile, email, password, connected accounts",
        icon = SlockIcons.User,
        value = "Verified",
        selected = true
    ),
    SettingsHomeItem(
        key = "mobile",
        title = "Mobile",
        subtitle = "Push notifications and server mute",
        icon = SlockIcons.MessageSquare
    ),
    SettingsHomeItem(
        key = "server",
        title = "Server",
        subtitle = "Workspace name, members, invites, permissions",
        icon = SlockIcons.Users,
        value = "Owner"
    ),
    SettingsHomeItem(
        key = "computers",
        title = "Computers",
        subtitle = "Connected machines and active sessions",
        icon = SlockIcons.Square
    ),
    SettingsHomeItem(
        key = "release-notes",
        title = "Release Notes",
        subtitle = "What changed recently",
        icon = SlockIcons.ListTodo
    )
)

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun SettingsHomePreview() {
    SlockTheme {
        SettingsHomeScreen(
            items = settingsHomeSampleItems,
            title = "Settings",
            subtitle = "Slock Android"
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SettingsHomeStatesPreview() {
    SlockTheme {
        Column(
            modifier = Modifier
                .background(SlockColor.AppBgLightBlue)
                .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            SettingsNavRow(
                item = settingsHomeSampleItems[0],
                onClick = {}
            )
            SettingsNavRow(
                item = SettingsHomeItem(
                    key = "long",
                    title = "Server settings with very long mixed 中文 English title",
                    subtitle = "This subtitle is intentionally long with 中文 English mixed text to verify wrapping without covering the right chevron or action.",
                    icon = SlockIcons.Settings,
                    value = "Current"
                )
            )
            SettingsNavRow(
                item = settingsHomeSampleItems[2].copy(
                    selected = false,
                    value = "Focused",
                    interactionState = SettingsRowInteractionState.Focused
                )
            )
            SettingsNavRow(
                item = settingsHomeSampleItems[1].copy(
                    value = "Pressed",
                    interactionState = SettingsRowInteractionState.Pressed
                )
            )
            SettingsNavRow(
                item = settingsHomeSampleItems[3].copy(enabled = false, value = "Disabled")
            )
        }
    }
}
