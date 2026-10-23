package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftAppRail
import build.raft.designsystem.components.RaftAppRailDefaults
import build.raft.designsystem.components.RaftAppRailItem
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftUiPalette
import kotlin.test.Test

/**
 * raft-ui AppRail (icons only, the product's): the rail with its 56dp header holding the server
 * tile, Home selected (the sticker on the frame), Activity with the attention pixel, and the
 * footer pinned to the bottom; next to it a rail with no header (a bar above holds the server).
 */
class AppRailScreenshotTest {
    private val appearances = listOf(RaftAppearance.Light, RaftAppearance.Dark)

    @Test
    fun appRail() =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("app_rail_${appearance.name.lowercase()}", appearance) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Rail(withHeader = true)
                    Rail(withHeader = false)
                }
            }
        }

    @Composable
    private fun Rail(withHeader: Boolean) {
        Box(Modifier.height(520.dp)) {
            RaftAppRail(
                header =
                    if (withHeader) {
                        {
                            Box(
                                Modifier
                                    .size(RaftAppRailDefaults.ItemSize)
                                    .background(RaftUiPalette.stickerInk, RoundedCornerShape(RaftRadius.Control)),
                                contentAlignment = Alignment.Center,
                            ) { RaftText("B", style = RaftTheme.typography.callout, color = RaftUiPalette.brand) }
                        }
                    } else {
                        null
                    },
                footer = {
                    Item("Settings", AppIcon.Settings)
                },
            ) {
                Item("Home", AppIcon.House, selected = true)
                Item("Activity", AppIcon.Activity, attention = true)
                Item("Tasks", AppIcon.SquareCheckBig)
                Item("Members", AppIcon.Users)
                Item("Computers", AppIcon.Monitor, enabled = false)
            }
        }
    }

    @Composable
    private fun Item(label: String, icon: AppIcon, selected: Boolean = false, attention: Boolean = false, enabled: Boolean = true) {
        RaftAppRailItem(
            label = label,
            selected = selected,
            onClick = {},
            icon = { ink -> AppIconImage(icon, RaftAppRailDefaults.IconSize, ink) },
            enabled = enabled,
            attention = attention,
            attentionDescription = "new",
        )
    }
}
