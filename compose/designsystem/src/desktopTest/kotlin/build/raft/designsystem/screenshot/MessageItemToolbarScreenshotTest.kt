package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftFocusedPreview
import build.raft.designsystem.components.LocalRaftHoveredPreview
import build.raft.designsystem.components.LocalRaftPressedPreview
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftMessageItemDefaults
import build.raft.designsystem.components.RaftMessageItemToolbar
import build.raft.designsystem.components.RaftMessageItemToolbarButton
import build.raft.designsystem.components.RaftMessageItemToolbarDefaults
import build.raft.designsystem.components.RaftTextSelectionToolbar
import build.raft.designsystem.components.RaftTextSelectionToolbarButton
import build.raft.designsystem.components.RaftTextSelectionToolbarSeparator
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * raft-ui MessageItem hover toolbar and TextSelectionToolbar (raft-design message-item.recipe.ts
 * `toolbar` / `toolbarButton`, text-selection-toolbar.recipe.ts):
 *  - `message_item_toolbar`: the toolbar at rest, one button hovered, pressed, focused, Save
 *    active, ⋯ open (its menu showing); then over a hovered row (`fill-subtle`), 18dp above the
 *    highlight's top and 12dp in from its end, and over a row whose menu is open.
 *  - `text_selection_toolbar`: Quote | Copy at rest, Quote hovered and pressed, Copy focused,
 *    and on the desktop scale (`text-control` 14/22).
 */
class MessageItemToolbarScreenshotTest {
    private val appearances = listOf(RaftAppearance.Light, RaftAppearance.Dark)

    private fun capture(name: String, content: @Composable (RaftAppearance) -> Unit) =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("${name}_${appearance.name.lowercase()}", appearance) { content(appearance) }
        }

    @Test
    fun messageItemToolbar() =
        capture("message_item_toolbar") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Caption("Rest")
                HoverToolbar()
                Caption("React hovered, Forward pressed, Save focused")
                HoverToolbar(hovered = 0, pressed = 1, focused = 2)
                Caption("Save active (saved), More open (its menu showing)")
                HoverToolbar(saved = true, moreOpen = true)
                Caption("Over a hovered row")
                MessageRow(hovered = true, popupOpen = false)
                Caption("Over a row whose menu is open")
                MessageRow(hovered = false, popupOpen = true)
            }
        }

    @Test
    fun textSelectionToolbar() =
        capture("text_selection_toolbar") { appearance ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Caption("Rest")
                SelectionToolbar()
                Caption("Quote hovered, Copy pressed")
                SelectionToolbar(hovered = 0, pressed = 1)
                Caption("Quote focused")
                SelectionToolbar(focused = 0)
                Caption("Desktop scale: text-control 14/22")
                RaftTheme(appearance = appearance, systemDark = false, scale = RaftScale.Desktop) {
                    SelectionToolbar()
                }
            }
        }

    @Composable
    private fun HoverToolbar(
        hovered: Int = -1,
        pressed: Int = -1,
        focused: Int = -1,
        saved: Boolean = false,
        moreOpen: Boolean = false,
        modifier: Modifier = Modifier,
    ) {
        RaftMessageItemToolbar(modifier = modifier, label = "Message Actions") {
            State(0, hovered, pressed, focused) {
                RaftMessageItemToolbarButton(onClick = {}, tooltip = "Add Reaction") { Icon(AppIcon.SmilePlus) }
            }
            State(1, hovered, pressed, focused) {
                RaftMessageItemToolbarButton(onClick = {}, tooltip = "Forward") { RaftGlyphIcon(RaftGlyph.Forward) }
            }
            State(2, hovered, pressed, focused) {
                RaftMessageItemToolbarButton(onClick = {}, tooltip = if (saved) "Remove from Saved" else "Save", active = saved) {
                    RaftGlyphIcon(RaftGlyph.Bookmark)
                }
            }
            State(3, hovered, pressed, focused) {
                RaftMessageItemToolbarButton(onClick = {}, tooltip = "More Actions", open = moreOpen) { RaftGlyphIcon(RaftGlyph.Ellipsis) }
            }
        }
    }

    @Composable
    private fun SelectionToolbar(hovered: Int = -1, pressed: Int = -1, focused: Int = -1) {
        RaftTextSelectionToolbar(label = "Text Selection Actions") {
            State(0, hovered, pressed, focused) {
                RaftTextSelectionToolbarButton(label = "Quote", onClick = {}) { Icon(AppIcon.TextQuote) }
            }
            RaftTextSelectionToolbarSeparator()
            State(1, hovered, pressed, focused) {
                RaftTextSelectionToolbarButton(label = "Copy", onClick = {}) { RaftGlyphIcon(RaftGlyph.Copy) }
            }
        }
    }

    /** A message row as the list lays it: the fill over the padding box, the toolbar over its top right corner. */
    @Composable
    private fun MessageRow(hovered: Boolean, popupOpen: Boolean) {
        Box(Modifier.width(460.dp).padding(top = RaftMessageItemToolbarDefaults.TopRoom)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(RaftMessageItemDefaults.rowFill(hovered = hovered, popupOpen = popupOpen))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Column(Modifier.padding(start = 47.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RaftText("Carol", style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.Bold))
                        RaftText("2:05 AM", style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint)
                    }
                    RaftText("Is the migration safe to run twice? The new column has a default value.")
                }
            }
            HoverToolbar(
                moreOpen = popupOpen,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = -RaftMessageItemToolbarDefaults.EndInset, y = RaftMessageItemToolbarDefaults.TopOffset),
            )
        }
    }

    @Composable
    private fun State(index: Int, hovered: Int, pressed: Int, focused: Int, content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalRaftHoveredPreview provides (index == hovered),
            LocalRaftPressedPreview provides (index == pressed),
            LocalRaftFocusedPreview provides (index == focused),
            content = content,
        )
    }

    /** An app icon in the button's current ink, as the app's Lucide icon draws (`currentColor`). */
    @Composable
    private fun Icon(icon: AppIcon) {
        AppIconImage(icon, size = 16.dp, color = LocalRaftContentColor.current)
    }

    @Composable
    private fun Caption(text: String) {
        RaftText(text = text, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint)
    }
}
