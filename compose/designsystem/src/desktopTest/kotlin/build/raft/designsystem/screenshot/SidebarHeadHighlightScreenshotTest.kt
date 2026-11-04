package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftFocusedPreview
import build.raft.designsystem.components.LocalRaftPressedPreview
import build.raft.designsystem.components.RaftAvatar
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftAvatarType
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftSidebarChannelItem
import build.raft.designsystem.components.RaftSidebarItem
import build.raft.designsystem.components.RaftSidebarItemIcon
import build.raft.designsystem.components.RaftSidebarItemMeta
import build.raft.designsystem.components.RaftSidebarItemVariant
import build.raft.designsystem.components.RaftSidebarSectionAction
import build.raft.designsystem.components.RaftSidebarSectionHeader
import build.raft.designsystem.components.RaftUnreadCount
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * A highlighted sidebar header or row lights up the whole row, its trailing parts included
 * (artin, 2026-11-04: a section header's press fill stopped before its attention pixel and
 * its controls). Each state is one the app reaches: a header whose press-and-hold menu is
 * open (`menuOpen`), a header under a finger (pressed), a header in keyboard focus, a channel
 * row whose menu is open and a DM row under a finger, in the page's 8dp inset.
 */
class SidebarHeadHighlightScreenshotTest {
    @Test
    fun aHighlightedSidebarHeaderOrRowCoversItsTrailingParts() =
        listOf(RaftAppearance.Light, RaftAppearance.Dark).forEach { appearance ->
            RaftScreenshot.capture("sidebar-head-highlight_${appearance.name.lowercase()}", appearance) {
                Column(
                    Modifier
                        .width(320.dp)
                        .background(RaftTheme.colors.layerSidebar)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    // Press and hold opened the section's menu.
                    RaftSidebarSectionHeader(
                        "Channels",
                        expanded = true,
                        onExpandedChange = {},
                        count = 38,
                        onLongClick = {},
                        menuOpen = true,
                        actions = {
                            RaftSidebarSectionAction(RaftGlyph.ArrowDownUp, "Sort Channels", onClick = {})
                            RaftSidebarSectionAction(RaftGlyph.Plus, "Create Channel", onClick = {})
                        },
                    )
                    RaftSidebarChannelItem("general", onClick = {})
                    // Under a finger: collapsed, with the attention pixel before its control. The
                    // preview forces the whole subtree, so the control is put back at rest.
                    State(pressed = true) {
                        RaftSidebarSectionHeader(
                            "Direct Messages",
                            expanded = false,
                            onExpandedChange = {},
                            count = 12,
                            attention = true,
                            actions = { State { RaftSidebarSectionAction(RaftGlyph.Plus, "New Message", onClick = {}) } },
                        )
                    }
                    // Press and hold opened it, with the attention pixel and no controls.
                    RaftSidebarSectionHeader(
                        "Joint Channels",
                        expanded = false,
                        onExpandedChange = {},
                        count = 1,
                        attention = true,
                        onLongClick = {},
                        menuOpen = true,
                    )
                    State(focused = true) {
                        RaftSidebarSectionHeader(
                            "Pinned",
                            expanded = false,
                            onExpandedChange = {},
                            count = 3,
                            attention = true,
                            actions = { State { RaftSidebarSectionAction(RaftGlyph.ArrowUpDown, "Sort Pinned", onClick = {}) } },
                        )
                    }
                    // A channel row whose menu is open: the fill runs under its mark and counts.
                    RaftSidebarChannelItem(
                        "proj-mobile",
                        onClick = {},
                        onLongClick = {},
                        menuOpen = true,
                        mentioned = true,
                        unreadCount = 4,
                        threadCount = 2,
                        titleTrailing = { RaftGlyphIcon(RaftGlyph.Info, size = 14.dp) },
                    )
                    // A DM under a finger, with its aside.
                    State(pressed = true) {
                        RaftSidebarItem(
                            "Bugen",
                            onClick = {},
                            variant = RaftSidebarItemVariant.Peer,
                            leading = { RaftAvatar(RaftAvatarType.Agent, RaftAvatarSize.Xxs, initials = "B") },
                            trailing = {
                                RaftSidebarItemMeta("Codex")
                                RaftUnreadCount(4)
                            },
                        )
                    }
                    // Saved under a finger, with its quiet count.
                    State(pressed = true) {
                        RaftSidebarItem(
                            "Saved",
                            onClick = {},
                            variant = RaftSidebarItemVariant.Entry,
                            leading = { RaftSidebarItemIcon(RaftGlyph.Bookmark) },
                            trailing = { RaftSidebarItemMeta("12") },
                        )
                    }
                }
            }
        }

    /** Forces the press or focus look on [content]; with neither it puts [content] back at rest. */
    @Composable
    private fun State(pressed: Boolean = false, focused: Boolean = false, content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalRaftPressedPreview provides pressed,
            LocalRaftFocusedPreview provides focused,
            content = content,
        )
    }
}
