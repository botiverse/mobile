package build.raft.designsystem.vcase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftHoveredPreview
import build.raft.designsystem.components.RaftAppRailDefaults
import build.raft.designsystem.components.RaftAppRailItem
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftSegment
import build.raft.designsystem.components.RaftSegmentedControl
import build.raft.designsystem.components.RaftSidebarChannelItem
import build.raft.designsystem.components.RaftTab
import build.raft.designsystem.components.RaftTabs
import build.raft.designsystem.components.RaftTabsVariant
import build.raft.designsystem.components.RaftTaskCompactList
import build.raft.designsystem.components.RaftTaskCompactRow
import build.raft.designsystem.components.RaftTaskStatus
import build.raft.designsystem.components.RaftToggleGroup
import build.raft.designsystem.components.RaftToggleGroupItem
import build.raft.designsystem.screenshot.AppIcon
import build.raft.designsystem.screenshot.AppIconImage
import build.raft.designsystem.theme.RaftTheme
import dev.vcase.VCase

// Pointer hover (raft-ui states.md § Hover and press) on the components that answer a mouse or
// trackpad: each row shows rest, then hovered, forced through [LocalRaftHoveredPreview] (an
// offscreen frame has no pointer). Selected, disabled and display-only items are hovered too,
// to show that they don't respond. Light and dark: the list cell's hover steps up in dark.
// These replace HoverScreenshotTest (each _light/_dark).

@Composable
private fun MenuItems() {
    RaftMenuItem("Copy Link", {}, leadingIcon = { RaftGlyphIcon(RaftGlyph.Copy) })
    RaftMenuItem("Recent Activity", {}, checked = true)
    RaftMenuItem("Archive Channel", {}, enabled = false)
}

@Composable
private fun Rows() {
    RaftListRowGroup {
        RaftListRow("Notifications", onClick = {}, subtitle = "Mentions and replies")
        RaftListRow("Selected", onClick = {}, subtitle = "The item open beside the list", selected = true, divider = true)
        RaftListRow("Display only", subtitle = "No onClick: no hover", divider = true)
    }
    Column {
        RaftSidebarChannelItem("general", onClick = {})
        RaftSidebarChannelItem("design", onClick = {}, selected = true)
        RaftSidebarChannelItem("visual-testing", onClick = {}, unreadCount = 3)
    }
    RaftTaskCompactList {
        RaftTaskCompactRow("#12", "Ship the preview build", onClick = {}, status = RaftTaskStatus.InProgress, statusText = "In Progress")
    }
}

/**
 * Tabs, segments and toggles: an unselected item lays `fill-medium`, the selected sticker
 * nothing; an underline tab's label turns `foreground`.
 */
@VCase("ds.hover.tabs")
@Composable
fun HoverTabs() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(false, true).forEach { hovered ->
            Caption(if (hovered) "Hovered: selected, rest, disabled" else "Rest")
            HoveredIf(hovered) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RaftTabs {
                        RaftTab("Workspace", selected = true, onClick = {})
                        RaftTab("Activity", selected = false, onClick = {})
                        RaftTab("Billing", selected = false, onClick = {}, enabled = false)
                    }
                    RaftTabs(variant = RaftTabsVariant.Underline) {
                        RaftTab("Messages", selected = true, onClick = {})
                        RaftTab("Files", selected = false, onClick = {})
                        RaftTab("Links", selected = false, onClick = {}, enabled = false)
                    }
                    RaftSegmentedControl {
                        RaftSegment("Light", selected = true, onClick = {})
                        RaftSegment("Dark", selected = false, onClick = {})
                        RaftSegment("System", selected = false, onClick = {}, enabled = false)
                    }
                    RaftToggleGroup {
                        RaftToggleGroupItem("Bold", pressed = true, onPressedChange = {})
                        RaftToggleGroupItem("Italic", pressed = false, onPressedChange = {})
                    }
                }
            }
        }
    }
}

/** Rail items on the frame: `fill-frame-medium` under the pointer; the selected sticker nothing. */
@VCase("ds.hover.app-rail-item")
@Composable
fun HoverAppRailItem() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Caption("Rest, then hovered: selected, unselected, disabled")
        Row(
            Modifier.background(RaftTheme.colors.layerFrame).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(false, true).forEach { hovered ->
                HoveredIf(hovered) {
                    RailItem("Home", AppIcon.House, selected = true)
                    RailItem("Tasks", AppIcon.SquareCheckBig)
                    RailItem("Computers", AppIcon.Monitor, enabled = false)
                }
                Box(Modifier.width(12.dp))
            }
        }
    }
}

@Composable
private fun RailItem(label: String, icon: AppIcon, selected: Boolean = false, enabled: Boolean = true) {
    RaftAppRailItem(
        label = label,
        selected = selected,
        onClick = {},
        icon = { ink -> AppIconImage(icon, RaftAppRailDefaults.IconSize, ink) },
        enabled = enabled,
    )
}

@Composable
private fun Hovered(content: @Composable () -> Unit) = HoveredIf(true, content)

@Composable
private fun HoveredIf(hovered: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRaftHoveredPreview provides hovered, content = content)
}
