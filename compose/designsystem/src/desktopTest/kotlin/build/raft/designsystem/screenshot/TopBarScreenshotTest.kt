package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.designsystem.components.LocalRaftFocusedPreview
import build.raft.designsystem.components.LocalRaftPressedPreview
import build.raft.designsystem.components.RaftAvatarAttentionLight
import build.raft.designsystem.components.RaftAvatarBadgeBox
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftSearchTrigger
import build.raft.designsystem.components.RaftTopBar
import build.raft.designsystem.components.RaftWorkspaceButton
import build.raft.designsystem.components.RaftWorkspaceButtonDefaults
import build.raft.designsystem.components.RaftWorkspaceStrip
import build.raft.designsystem.components.RaftWorkspaceStripEntry
import build.raft.designsystem.components.RaftWorkspaceStripItem
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftUiPalette
import kotlin.test.Test

/**
 * The web top bar (AppTopBar) and its parts: [RaftTopBar] with the workspace button, the
 * workspace strip (the current mark, another server's unread) and the search trigger centred;
 * a narrower bar where the current server is beyond what fits (last slot, after the hairline)
 * and one too narrow for the strip; [RaftSearchTrigger] at rest, pressed, focused and with a
 * shortcut; [RaftWorkspaceStripItem] at rest, current, unread and pressed.
 */
class TopBarScreenshotTest {
    private val appearances = listOf(RaftAppearance.Light, RaftAppearance.Dark)

    private fun capture(name: String, content: @Composable () -> Unit) =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("${name}_${appearance.name.lowercase()}", appearance, content = content)
        }

    private val servers =
        listOf(
            RaftWorkspaceStripEntry("b", "Botiverse"),
            RaftWorkspaceStripEntry("a", "Acme Labs", unread = true),
            RaftWorkspaceStripEntry("q", "Quiet"),
            RaftWorkspaceStripEntry("r", "Raft Design"),
            RaftWorkspaceStripEntry("s", "Stamp", unread = true),
            RaftWorkspaceStripEntry("z", "Zed"),
        )

    @Test
    fun topBar() =
        appearances.forEach { appearance ->
            // The strip reports whether it shows a frame after it lays out.
            RaftScreenshot.capture("top_bar_${appearance.name.lowercase()}", appearance, settleFrames = 2) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Bar(width = 1100.dp, currentKey = "b")
                    // The current server is the last one: it takes the strip's last slot after the hairline.
                    Bar(width = 900.dp, currentKey = "z")
                    // No room for two avatars beside a long name: the strip hides and the button
                    // carries "another server has unread".
                    Bar(width = 820.dp, currentKey = "b", name = "Botiverse Research Group")
                }
            }
        }

    @Composable
    private fun Bar(width: Dp, currentKey: String, name: String? = null, arrows: Boolean = false) {
        val current = servers.first { it.key == currentKey }
        var stripShown by remember { mutableStateOf(true) }
        val otherUnread = servers.any { it.key != currentKey && it.unread }
        RaftTopBar(
            modifier = Modifier.width(width),
            leading = {
                RaftWorkspaceButton(
                    name = name ?: current.name,
                    onClick = {},
                    nameMaxWidth = RaftWorkspaceButtonDefaults.NameMaxWidthBesideStrip,
                    avatar = {
                        RaftAvatarBadgeBox(
                            size = RaftAvatarSize.Sm,
                            badge = {
                                if (!stripShown && otherUnread) {
                                    RaftAvatarAttentionLight(RaftAvatarSize.Sm, cutout = RaftTheme.colors.layerFrame)
                                }
                            },
                        ) { ServerAvatar(current, RaftAvatarSize.Sm.edge) }
                    },
                )
                RaftWorkspaceStrip(
                    entries = servers,
                    currentKey = currentKey,
                    onPick = {},
                    avatar = { ServerAvatar(it, RaftAvatarSize.Xs.edge) },
                    modifier = Modifier.weight(1f),
                    onShownChange = { stripShown = it },
                )
            },
            center = { RaftSearchTrigger(label = "Search", onClick = {}, windowWidth = width) },
            centerLeading =
                if (arrows) {
                    {
                        RaftIconButton(onClick = {}, tooltip = "Back", variant = RaftButtonVariant.OnFrame) {
                            RaftGlyphIcon(RaftGlyph.ChevronRight, Modifier.rotate(180f), size = 20.dp)
                        }
                        RaftIconButton(onClick = {}, tooltip = "Forward", variant = RaftButtonVariant.OnFrame, enabled = false) {
                            RaftGlyphIcon(RaftGlyph.ChevronRight, size = 20.dp)
                        }
                    }
                } else {
                    null
                },
            leadingMinWidth = RaftWorkspaceButtonDefaults.MinWidth,
        )
    }

    /**
     * The centre-leading slot (the app's history arrows): two 36dp on-frame icon buttons 8dp
     * before the centred search, outside its width. At 1100dp and 840dp the search stays at the
     * bar's middle; at 700dp the workspace button would get less than its narrowest, so the
     * arrows and the search move toward the end together and the search narrows from its start.
     */
    @Test
    fun topBarCenterLeading() =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("top_bar_center_leading_${appearance.name.lowercase()}", appearance, settleFrames = 2) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Bar(width = 1100.dp, currentKey = "b", arrows = true)
                    Bar(width = 840.dp, currentKey = "b", name = "Botiverse Research Group", arrows = true)
                    Bar(width = 700.dp, currentKey = "b", name = "Botiverse Research Group", arrows = true)
                }
            }
        }

    @Test
    fun searchTrigger() =
        capture("search_trigger") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Caption("Rest, with a shortcut, pressed, focused, in a 280dp slot")
                RaftSearchTrigger(label = "Search", onClick = {}, windowWidth = 1280.dp)
                RaftSearchTrigger(label = "Search Raft", onClick = {}, windowWidth = 1280.dp, shortcut = "Ctrl K")
                CompositionLocalProvider(LocalRaftPressedPreview provides true) {
                    RaftSearchTrigger(label = "Search", onClick = {}, windowWidth = 1280.dp)
                }
                CompositionLocalProvider(LocalRaftFocusedPreview provides true) {
                    RaftSearchTrigger(label = "Search", onClick = {}, windowWidth = 1280.dp)
                }
                Box(Modifier.width(280.dp)) {
                    RaftSearchTrigger(label = "Search channels, people and messages", onClick = {}, windowWidth = 1280.dp, shortcut = "⌘K")
                }
            }
        }

    @Test
    fun workspaceStripItem() =
        capture("workspace_strip_item") {
            Column(
                Modifier.background(RaftTheme.colors.layerFrame).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Caption("Rest, current, unread, current + another unread, pressed")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    Item(servers[0], current = false, attention = false)
                    Item(servers[0], current = true, attention = false)
                    Item(servers[1], current = false, attention = true)
                    Item(servers[0], current = true, attention = true)
                    CompositionLocalProvider(LocalRaftPressedPreview provides true) {
                        Item(servers[2], current = false, attention = false)
                    }
                }
            }
        }

    @Composable
    private fun Item(entry: RaftWorkspaceStripEntry, current: Boolean, attention: Boolean) {
        RaftWorkspaceStripItem(
            name = entry.name,
            current = current,
            onClick = {},
            attention = attention,
            avatar = { ServerAvatar(entry, RaftAvatarSize.Xs.edge) },
        )
    }

    /** A server avatar's initial fallback: the brand yellow initial on an ink tile. */
    @Composable
    private fun ServerAvatar(entry: RaftWorkspaceStripEntry, size: Dp) {
        Box(
            Modifier.size(size).background(RaftUiPalette.stickerInk, RoundedCornerShape(RaftRadius.Sticker)),
            contentAlignment = Alignment.Center,
        ) {
            RaftText(
                entry.name.take(1),
                style = RaftTheme.typography.caption.copy(fontSize = if (size > 20.dp) 13.sp else 10.sp, lineHeight = if (size > 20.dp) 13.sp else 10.sp),
                color = RaftUiPalette.selected,
            )
        }
    }

    @Composable
    private fun Caption(text: String) {
        RaftText(text = text, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint)
    }
}
