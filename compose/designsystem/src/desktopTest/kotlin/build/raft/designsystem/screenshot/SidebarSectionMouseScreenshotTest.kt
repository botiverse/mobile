package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftSidebarChannelItem
import build.raft.designsystem.components.RaftSidebarSectionAction
import build.raft.designsystem.components.RaftSidebarSectionHeader
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * Sidebar section headers with a mouse or trackpad ([RaftPointerInput.Fine]), at rest:
 * raft-ui 0.57.1 (#942) shows the controls at all times, with a mouse too, and a collapsed
 * section's attention pixel sits before them; a header whose menu is open keeps its pixel.
 */
class SidebarSectionMouseScreenshotTest {
    @Test
    fun sectionControlsShowAtRestWithAMouse() =
        listOf(RaftAppearance.Light, RaftAppearance.Dark).forEach { appearance ->
            RaftScreenshot.capture(
                "sidebar-section-mouse_${appearance.name.lowercase()}",
                appearance,
                pointerInput = RaftPointerInput.Fine,
            ) {
                Column(Modifier.width(280.dp).background(RaftTheme.colors.layerSidebar)) {
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
                    RaftSidebarChannelItem("general", onClick = {})
                    RaftSidebarSectionHeader(
                        "Direct Messages",
                        expanded = false,
                        onExpandedChange = {},
                        count = 12,
                        attention = true,
                        actions = { RaftSidebarSectionAction(RaftGlyph.Plus, "New Message", onClick = {}) },
                    )
                    RaftSidebarSectionHeader(
                        "Joint Channels",
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
