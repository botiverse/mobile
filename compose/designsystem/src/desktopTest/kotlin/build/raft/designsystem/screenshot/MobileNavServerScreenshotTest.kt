package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.designsystem.components.LocalRaftPressedPreview
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftMobileNavDefaults
import build.raft.designsystem.components.RaftMobileNavItem
import build.raft.designsystem.components.RaftMobileNavServerPopoverItem
import build.raft.designsystem.components.RaftMobileNavServerPopover
import build.raft.designsystem.components.RaftMobileNavServerPopoverContent
import build.raft.designsystem.components.RaftMobileNavServerPopoverDestination
import build.raft.designsystem.components.RaftMobileNavServerPopoverDestinations
import build.raft.designsystem.components.RaftMobileNavServerPopoverGroup
import build.raft.designsystem.components.RaftMobileNavServerPopoverList
import build.raft.designsystem.components.RaftMobileNavServerPopoverRow
import build.raft.designsystem.components.RaftMobileNavServerPopoverSeparator
import build.raft.designsystem.components.RaftMobileNavServerPopoverState
import build.raft.designsystem.components.RaftUnreadCount
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette
import kotlin.test.Test

/**
 * raft-ui MobileNav server tab (raft-design #712, design D1a): the phone bar with the server
 * as its last tab (never selected; another server unread; a long name cut; the menu open) and
 * the wide rail's compact entry; and the compact server popover (raft-design #712 59a248f9, Kirby 10-22):
 * servers at the option height with the slug under the name, the pages as one row of
 * icon-over-label destinations (Computers dotted), the footer rows, on a 375dp phone above the
 * bar, at most 60% of the screen. The
 * bar's other tabs use stand-in glyphs: app icons are the app's, not this module's.
 */
class MobileNavServerScreenshotTest {
    private val appearances = listOf(RaftAppearance.Light, RaftAppearance.Dark)

    private fun capture(name: String, content: @Composable () -> Unit) =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("${name}_${appearance.name.lowercase()}", appearance, content = content)
        }

    @Test
    fun mobileNavServerTab() =
        capture("mobile_nav_server_tab") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Caption("Home selected, another server unread")
                Bar(serverName = "Botiverse", attention = true)
                Caption("A long server name is cut")
                Bar(serverName = "Raft Design Partners Worldwide")
                Caption("Pressed")
                CompositionLocalProvider(LocalRaftPressedPreview provides true) { Bar(serverName = "Botiverse", pressedServerOnly = true) }
                Caption("Menu open")
                Bar(serverName = "Botiverse", open = true)
                Caption("Rail entry: rest, unread, open")
                Row(
                    Modifier.background(RaftTheme.colors.layerFrame).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(false to false, false to true, true to false).forEach { (open, unread) ->
                        RaftMobileNavServerPopoverItem(
                            label = "Botiverse",
                            state = menuState(open),
                            avatar = { size -> ServerFace("B", size) },
                            attention = unread,
                            compact = true,
                        )
                    }
                }
            }
        }

    @Test
    fun mobileNavServerPopover() =
        capture("mobile_nav_server_popover") {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Caption("Open over the bar (375dp phone)")
                    Phone(menuState(open = true), servers = 4)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Caption("Held long press over Acme Labs; 14 servers scroll")
                    val held = menuState(open = true)
                    held.beginHold()
                    // Rows register their real bounds on layout; a held finger at (1, 1) over a
                    // stand-in target shows the hold's highlight on Acme Labs.
                    held.register("server:acme", Rect(0f, 0f, 2f, 2f))
                    held.moveHold(Offset(1f, 1f))
                    Phone(held, servers = 14)
                }
            }
        }

    private fun menuState(open: Boolean) = RaftMobileNavServerPopoverState().apply { if (open) show() }

    @Composable
    private fun Phone(state: RaftMobileNavServerPopoverState, servers: Int) {
        Column(Modifier.width(375.dp)) {
            Box(Modifier.width(375.dp).height(640.dp).background(RaftTheme.colors.layerCanvas)) {
                RaftMobileNavServerPopover(state, screenHeight = 640.dp + RaftMobileNavDefaults.BarHeight) {
                    RaftMobileNavServerPopoverContent(paneTitle = "Server panel") {
                        RaftMobileNavServerPopoverList {
                            sampleServers(servers).forEach { (id, name, unread) ->
                                val current = id == "botiverse"
                                RaftMobileNavServerPopoverRow(
                                    label = name,
                                    description = "/$id",
                                    onClick = {},
                                    current = current,
                                    state = state,
                                    key = "server:$id",
                                    leading = { ServerFace(name.take(1), RaftMobileNavDefaults.ServerPopoverAvatarSize) },
                                    trailing =
                                        when {
                                            current -> ({ RaftGlyphIcon(RaftGlyph.Check) })
                                            unread > 0 -> ({ RaftUnreadCount(unread) })
                                            else -> null
                                        },
                                )
                            }
                        }
                        RaftMobileNavServerPopoverSeparator()
                        RaftMobileNavServerPopoverGroup {
                            listOf("Join Community", "Switch or Create Server", "Invite Human").forEach { label ->
                                RaftMobileNavServerPopoverRow(label = label, onClick = {}, leading = { RaftGlyphIcon(RaftGlyph.Plus) })
                            }
                        }
                        RaftMobileNavServerPopoverSeparator()
                        RaftMobileNavServerPopoverDestinations {
                            listOf(
                                Triple("Members", RaftGlyph.AtSign, false),
                                Triple("Computers", RaftGlyph.Info, true),
                                Triple("Settings", RaftGlyph.Hash, false),
                            ).forEach { (label, glyph, attention) ->
                                RaftMobileNavServerPopoverDestination(
                                    label = label,
                                    onClick = {},
                                    icon = { RaftGlyphIcon(glyph, size = 20.dp) },
                                    attention = attention,
                                    state = state,
                                    key = "page:$label",
                                )
                            }
                        }
                    }
                }
            }
            Bar(serverName = "Botiverse", open = state.open)
        }
    }

    private fun sampleServers(count: Int): List<Triple<String, String, Int>> {
        val base =
            listOf(
                Triple("botiverse", "Botiverse", 0),
                Triple("acme", "Acme Labs", 3),
                Triple("long", "Raft Design Partners Worldwide", 120),
                Triple("community", "Raft Community", 0),
            )
        return base + (base.size until count).map { Triple("s$it", "Server $it", 0) }
    }

    @Composable
    private fun Bar(serverName: String, attention: Boolean = false, open: Boolean = false, pressedServerOnly: Boolean = false) {
        Box(Modifier.width(375.dp).height(RaftMobileNavDefaults.BarHeight).background(RaftTheme.colors.layerCard)) {
            Row(Modifier.fillMaxWidth().fillMaxHeight().padding(top = RaftStroke.Hairline + RaftMobileNavDefaults.TopPadding)) {
                CompositionLocalProvider(LocalRaftPressedPreview provides false) {
                    listOf(
                        Triple("Home", RaftGlyph.Hash, true),
                        Triple("Activity", RaftGlyph.AtSign, false),
                        Triple("Tasks", RaftGlyph.CircleCheck, false),
                        Triple("Search", RaftGlyph.Search, false),
                    ).forEach { (label, glyph, selected) ->
                        RaftMobileNavItem(
                            label = label,
                            selected = selected,
                            onClick = {},
                            icon = { ink -> RaftGlyphIcon(glyph, size = RaftMobileNavDefaults.IconSize, color = ink) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
                CompositionLocalProvider(LocalRaftPressedPreview provides pressedServerOnly) {
                    RaftMobileNavServerPopoverItem(
                        label = serverName,
                        state = menuState(open),
                        avatar = { size -> ServerFace(serverName.take(1), size) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        attention = attention,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(RaftTheme.colors.lineSeam))
        }
    }

    /** The app's server avatar fallback: the initial in brand yellow on an ink tile. */
    @Composable
    private fun ServerFace(initial: String, size: Dp) {
        Box(
            Modifier.size(size).background(RaftUiPalette.stickerInk, RoundedCornerShape(RaftRadius.Sticker)),
            contentAlignment = Alignment.Center,
        ) {
            RaftText(
                initial.uppercase(),
                style = RaftTheme.typography.callout.copy(fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold),
                color = RaftUiPalette.brand,
                maxLines = 1,
            )
        }
    }

    @Composable
    private fun Caption(text: String) {
        RaftText(text = text, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint)
    }
}
