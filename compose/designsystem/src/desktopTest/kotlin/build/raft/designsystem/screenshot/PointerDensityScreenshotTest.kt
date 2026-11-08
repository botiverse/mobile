package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftBarButtons
import build.raft.designsystem.components.RaftAppRail
import build.raft.designsystem.components.RaftAppRailDefaults
import build.raft.designsystem.components.RaftAppRailItem
import build.raft.designsystem.components.RaftBackButton
import build.raft.designsystem.components.RaftBackButtonDefaults
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialogSheetSurface
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftIconToggleButton
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMenuSurface
import build.raft.designsystem.components.RaftSearchTrigger
import build.raft.designsystem.components.RaftSegment
import build.raft.designsystem.components.RaftSegmentedControl
import build.raft.designsystem.components.RaftSidebarChannelItem
import build.raft.designsystem.components.RaftSwitch
import build.raft.designsystem.components.RaftTab
import build.raft.designsystem.components.RaftTabs
import build.raft.designsystem.components.RaftTabsVariant
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTopBar
import build.raft.designsystem.components.RaftWorkspaceButton
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette
import kotlin.test.Test

/**
 * Sizing v12 under a mouse or trackpad (raft-ui 0.65 desktop density), pinned with
 * [RaftPointerInput.Fine] at [RaftScale.Wide], light and dark. With a finger these components
 * keep their sizes (the other goldens, which render touch).
 * - `pointer-density-controls_*`: the one 34dp height of a field, Button `md` and `lg`, an icon
 *   button at `md` and `lg`, tabs, a segmented control, menu options, sidebar rows; the 38 x 22
 *   switch with its 14dp knob.
 * - `pointer-density-heads_*`: a 44dp head with 28dp, radius-6 bar buttons (Back, an action, a
 *   panel toggle on and off), a dialog sheet's 44dp head, and the 44dp top bar with 32dp items.
 * - `pointer-density-rail_*`: the rail at any window height is the compact one (48 + the seam,
 *   36dp items) with a 44dp header.
 */
class PointerDensityScreenshotTest {
    private fun capture(name: String, settleFrames: Int = 0, content: @Composable () -> Unit) =
        listOf(RaftAppearance.Light, RaftAppearance.Dark).forEach { appearance ->
            RaftScreenshot.capture(
                "${name}_${appearance.name.lowercase()}",
                appearance,
                settleFrames = settleFrames,
                scale = RaftScale.Wide,
                pointerInput = RaftPointerInput.Fine,
                content = content,
            )
        }

    @Test
    fun controls() =
        capture("pointer-density-controls") {
            Column(Modifier.width(460.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RaftTextField("design-review", {}, placeholder = "Channel name")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RaftButton(text = "Cancel", onClick = {}, size = RaftButtonSize.Md)
                    RaftButton(text = "Save", onClick = {}, variant = RaftButtonVariant.Primary, size = RaftButtonSize.Md)
                    RaftButton(text = "Continue", onClick = {}, variant = RaftButtonVariant.Primary, size = RaftButtonSize.Lg)
                    RaftIconButton(onClick = {}, tooltip = "Search", size = RaftIconButtonSize.Md) {
                        RaftGlyphIcon(RaftGlyph.Search, size = 18.dp)
                    }
                    RaftIconButton(onClick = {}, tooltip = "Search", size = RaftIconButtonSize.Lg) {
                        RaftGlyphIcon(RaftGlyph.Search, size = RaftTheme.controls.iconSize)
                    }
                }
                RaftTabs {
                    RaftTab("Workspace", selected = true, onClick = {})
                    RaftTab("Activity", selected = false, onClick = {})
                }
                RaftTabs(variant = RaftTabsVariant.Underline) {
                    RaftTab("Messages", selected = true, onClick = {})
                    RaftTab("Files", selected = false, onClick = {})
                }
                RaftSegmentedControl {
                    RaftSegment("Light", selected = true, onClick = {})
                    RaftSegment("Dark", selected = false, onClick = {})
                    RaftSegment("System", selected = false, onClick = {})
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    RaftMenuSurface(Modifier.width(220.dp)) {
                        RaftMenuItem("Copy Link", {}, leadingIcon = { RaftGlyphIcon(RaftGlyph.Copy) })
                        RaftMenuItem("Recent Activity", {}, checked = true)
                        RaftMenuItem("Archive Channel", {}, enabled = false)
                    }
                    Column(Modifier.width(220.dp).background(RaftTheme.colors.layerSidebar)) {
                        RaftSidebarChannelItem("general", onClick = {}, selected = true)
                        RaftSidebarChannelItem("design", onClick = {})
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RaftSwitch(checked = false, onCheckedChange = {}, label = "Sound")
                    RaftSwitch(checked = true, onCheckedChange = {}, label = "Desktop notifications")
                    RaftSwitch(checked = true, onCheckedChange = {}, enabled = false, label = "Locked")
                }
            }
        }

    @Test
    fun heads() =
        capture("pointer-density-heads", settleFrames = 2) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // A page head as the app draws one: the bar buttons come from LocalRaftBarButtons.
                CompositionLocalProvider(LocalRaftBarButtons provides true) {
                    Row(
                        Modifier
                            .width(420.dp)
                            .height(RaftTheme.controls.headHeight - RaftStroke.Hairline)
                            .background(RaftTheme.colors.layerCanvas)
                            .padding(horizontal = RaftBackButtonDefaults.EdgeInset),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RaftBackButton(onClick = {}, label = "Back")
                        RaftText("design-review", Modifier.weight(1f), style = RaftTheme.typography.headline)
                        RaftIconToggleButton(checked = true, onCheckedChange = {}, tooltip = "Close Tasks") {
                            RaftGlyphIcon(RaftGlyph.Check, size = RaftTheme.controls.barIconSize)
                        }
                        RaftIconToggleButton(checked = false, onCheckedChange = {}, tooltip = "Files") {
                            RaftGlyphIcon(RaftGlyph.Copy, size = RaftTheme.controls.barIconSize)
                        }
                        RaftIconButton(onClick = {}, tooltip = "Search", variant = RaftButtonVariant.Ghost, size = RaftIconButtonSize.Lg) {
                            RaftGlyphIcon(RaftGlyph.Search, size = RaftTheme.controls.barIconSize)
                        }
                    }
                }
                Box(Modifier.width(420.dp)) {
                    RaftDialogSheetSurface(
                        title = "Pinned",
                        commitLabel = "Save",
                        onCommit = {},
                        onClose = {},
                        modifier = Modifier.height(160.dp),
                    ) {
                        RaftText("proj-uiux", Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = RaftTheme.typography.callout)
                    }
                }
                RaftTopBar(
                    modifier = Modifier.width(900.dp),
                    endInset = 0.dp,
                    leading = {
                        RaftWorkspaceButton(
                            name = "Botiverse",
                            onClick = {},
                            avatar = {
                                Box(Modifier.size(20.dp).background(RaftUiPalette.selected, RoundedCornerShape(RaftRadius.Sticker)))
                            },
                        )
                    },
                    center = { RaftSearchTrigger(label = "Search", onClick = {}, windowWidth = 900.dp, shortcut = "⌘K") },
                    trailing = {
                        RaftIconButton(onClick = {}, tooltip = "Inbox", variant = RaftButtonVariant.OnFrame) {
                            RaftGlyphIcon(RaftGlyph.Search, size = 20.dp)
                        }
                    },
                )
            }
        }

    @Test
    fun rail() =
        capture("pointer-density-rail") {
            Box(Modifier.height(720.dp)) {
                RaftAppRail(
                    header = {
                        Box(Modifier.size(28.dp).background(RaftUiPalette.selected, RoundedCornerShape(RaftRadius.Sticker)))
                    },
                ) {
                    RaftAppRailItem(
                        label = "Home",
                        selected = true,
                        onClick = {},
                        icon = { ink -> AppIconImage(AppIcon.House, RaftAppRailDefaults.IconSize, ink) },
                    )
                    RaftAppRailItem(
                        label = "Activity",
                        selected = false,
                        onClick = {},
                        icon = { ink -> AppIconImage(AppIcon.Activity, RaftAppRailDefaults.IconSize, ink) },
                    )
                }
            }
        }
}
