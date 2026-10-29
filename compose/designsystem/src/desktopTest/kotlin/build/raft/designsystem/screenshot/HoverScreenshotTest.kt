package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftHoveredPreview
import build.raft.designsystem.components.RaftAppRailDefaults
import build.raft.designsystem.components.RaftAppRailItem
import build.raft.designsystem.components.RaftAvatarEmpty
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMenuSurface
import build.raft.designsystem.components.RaftMessageImageFrame
import build.raft.designsystem.components.RaftMessageQuotedPreview
import build.raft.designsystem.components.RaftPickerTriggerButton
import build.raft.designsystem.components.RaftSegment
import build.raft.designsystem.components.RaftSegmentedControl
import build.raft.designsystem.components.RaftSidebarChannelItem
import build.raft.designsystem.components.RaftTab
import build.raft.designsystem.components.RaftTabs
import build.raft.designsystem.components.RaftTabsVariant
import build.raft.designsystem.components.RaftTaskCompactList
import build.raft.designsystem.components.RaftTaskCompactRow
import build.raft.designsystem.components.RaftTaskStatus
import build.raft.designsystem.components.RaftTaskStatusBadge
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.components.RaftToast
import build.raft.designsystem.components.RaftToggleGroup
import build.raft.designsystem.components.RaftToggleGroupItem
import build.raft.designsystem.components.appendRaftTextLink
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftUiPalette
import kotlin.test.Test

/**
 * Pointer hover (raft-ui states.md § Hover and press) on the components that answer a mouse or
 * trackpad: each row shows rest, then hovered, forced through [LocalRaftHoveredPreview] (an
 * offscreen frame has no pointer). Selected, disabled and display-only items are hovered too,
 * to show that they don't respond. Light and dark: the list cell's hover steps up in dark.
 */
class HoverScreenshotTest {
    private val appearances = listOf(RaftAppearance.Light, RaftAppearance.Dark)

    private fun capture(name: String, content: @Composable () -> Unit) =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("${name}_${appearance.name.lowercase()}", appearance, content = content)
        }

    /** Menu items: `brand-soft` under the pointer, as the keyboard highlight; disabled stays plain. */
    @Test
    fun menuItemHovered() =
        capture("hover_menu_item") {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.width(260.dp)) {
                    Caption("Rest", Modifier.padding(bottom = 8.dp))
                    RaftMenuSurface(Modifier.fillMaxWidth()) { MenuItems() }
                }
                Column(Modifier.width(260.dp)) {
                    Caption("Hovered: item, checked item, disabled", Modifier.padding(bottom = 8.dp))
                    RaftMenuSurface(Modifier.fillMaxWidth()) { Hovered { MenuItems() } }
                }
            }
        }

    @Composable
    private fun MenuItems() {
        RaftMenuItem("Copy Link", {}, leadingIcon = { RaftGlyphIcon(RaftGlyph.Copy) })
        RaftMenuItem("Recent Activity", {}, checked = true)
        RaftMenuItem("Archive Channel", {}, enabled = false)
    }

    /**
     * Rows: a ListRow lays `fill-subtle`; a sidebar row (a list cell) `fill-cell-hover` on its
     * rounded shape, none when selected; a compact task row `fill-subtle`; a display-only
     * ListRow nothing.
     */
    @Test
    fun listRowsHovered() =
        capture("hover_list_rows") {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                listOf(false, true).forEach { hovered ->
                    Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Caption(if (hovered) "Hovered" else "Rest")
                        HoveredIf(hovered) { Rows() }
                    }
                }
            }
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
    @Test
    fun tabsHovered() =
        capture("hover_tabs") {
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
    @Test
    fun appRailItemHovered() =
        capture("hover_app_rail_item") {
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

    /**
     * Clickable text: a TextLink and a link in a sentence underline in their own colour (muted
     * turns ink), a toast's action underlines; none of them is underlined at rest.
     */
    @Test
    fun textLinkHovered() =
        capture("hover_text_link") {
            val type = RaftTheme.typography
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(false, true).forEach { hovered ->
                    Caption(if (hovered) "Hovered: muted, strong, disabled; in a sentence; a toast's action" else "Rest")
                    HoveredIf(hovered) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                RaftTextLink("Forgot Password", {}, variant = RaftTextLinkVariant.Muted, style = type.footnote)
                                RaftTextLink("Manage Machines", {}, variant = RaftTextLinkVariant.Strong, style = type.footnote)
                                RaftTextLink("Log Out", {}, style = type.footnote, enabled = false)
                            }
                            RaftText(
                                buildAnnotatedString {
                                    append("Don't have an account? ")
                                    appendRaftTextLink("Create One", onClick = {})
                                },
                                style = type.footnote,
                                color = RaftTheme.colors.foregroundMuted,
                            )
                            RaftToast("Wug finished reviewing #25.", actionLabel = "View", onAction = {})
                        }
                    }
                }
            }
        }

    /**
     * Small controls and readable cards: the quoted preview, the picker trigger (also with a
     * value), the editable status chip and the empty avatar take the medium tier; a gallery
     * image dims with `ink-8`.
     */
    @Test
    fun controlsHovered() =
        capture("hover_controls") {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                listOf(false, true).forEach { hovered ->
                    Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Caption(if (hovered) "Hovered" else "Rest")
                        HoveredIf(hovered) {
                            RaftMessageQuotedPreview(
                                authorName = "Alpha",
                                text = "Rolled back the migration; the error rate is flat again.",
                                channel = "launch-week",
                                onClick = {},
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                RaftPickerTriggerButton("Assignee", {})
                                RaftPickerTriggerButton("Status: Open", {}, hasValue = true)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                RaftTaskStatusBadge(RaftTaskStatus.InReview, "In Review", onClick = {})
                                RaftAvatarEmpty(RaftAvatarSize.Md, contentDescription = "Add Member", onClick = {})
                                RaftMessageImageFrame(Modifier.size(64.dp), onClick = {}) {
                                    Box(Modifier.fillMaxSize().background(RaftUiPalette.attention400))
                                }
                            }
                        }
                    }
                }
            }
        }

    @Composable
    private fun Hovered(content: @Composable () -> Unit) = HoveredIf(true, content)

    @Composable
    private fun HoveredIf(hovered: Boolean, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalRaftHoveredPreview provides hovered, content = content)
    }

    @Composable
    private fun Caption(text: String, modifier: Modifier = Modifier) {
        RaftText(text = text, modifier = modifier, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint)
    }
}
