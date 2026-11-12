package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftHoveredPreview
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftSidebarChannelItem
import build.raft.designsystem.components.RaftSidebarSectionAction
import build.raft.designsystem.components.RaftSidebarSectionHeader
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * Sidebar section headers with a mouse or trackpad ([RaftPointerInput.Fine]), on the sidebar's
 * 16dp glyph axis: at rest the controls are hidden (their room kept), a hovered header shows
 * them, as does a control whose menu is open (web SidebarSectionControls: "show on section
 * hover, keyboard focus or an open menu"). A collapsed section's attention pixel stays, before
 * the controls' room; a header whose own menu is open keeps its pixel and shows its controls.
 */
class SidebarSectionMouseScreenshotTest {
    @Test
    fun sectionControlsShowOnHoverWithAMouse() =
        listOf(RaftAppearance.Light, RaftAppearance.Dark).forEach { appearance ->
            RaftScreenshot.capture(
                "sidebar-section-mouse_${appearance.name.lowercase()}",
                appearance,
                pointerInput = RaftPointerInput.Fine,
                // An open menu shows its header's controls from the frame after it composes.
                settleFrames = 2,
            ) {
                Column(Modifier.width(280.dp).background(RaftTheme.colors.layerSidebar)) {
                    // At rest: the controls hidden.
                    RaftSidebarSectionHeader(
                        "Pinned",
                        expanded = true,
                        onExpandedChange = {},
                        count = 2,
                        actions = { RaftSidebarSectionAction(RaftGlyph.ArrowDownUp, "Sort Pinned", onClick = {}) },
                    )
                    // Hovered: shown.
                    CompositionLocalProvider(LocalRaftHoveredPreview provides true) {
                        RaftSidebarSectionHeader(
                            "Channels",
                            expanded = true,
                            onExpandedChange = {},
                            count = 38,
                            actions = {
                                RaftSidebarSectionAction(RaftGlyph.ArrowDownUp, "Sort Channels", onClick = {})
                                RaftSidebarSectionAction(RaftGlyph.Plus, "Create Channel", onClick = {})
                            },
                        )
                    }
                    RaftSidebarChannelItem("general", onClick = {})
                    // The sort menu open: shown without a hover.
                    RaftSidebarSectionHeader(
                        "Joint Channels",
                        expanded = true,
                        onExpandedChange = {},
                        count = 4,
                        actions = { RaftSidebarSectionAction(RaftGlyph.ArrowDownUp, "Sort Joint Channels", onClick = {}, menuOpen = true) },
                    )
                    RaftSidebarSectionHeader(
                        "Direct Messages",
                        expanded = false,
                        onExpandedChange = {},
                        count = 12,
                        attention = true,
                        actions = { RaftSidebarSectionAction(RaftGlyph.Plus, "New Message", onClick = {}) },
                    )
                    RaftSidebarSectionHeader(
                        "External Channels",
                        expanded = false,
                        onExpandedChange = {},
                        count = 1,
                        attention = true,
                        onLongClick = {},
                        menuOpen = true,
                    )
                }
            }
        }
}
