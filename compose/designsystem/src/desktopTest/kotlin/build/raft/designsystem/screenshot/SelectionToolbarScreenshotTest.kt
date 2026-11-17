package build.raft.designsystem.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftFocusedPreview
import build.raft.designsystem.components.LocalRaftPressedPreview
import build.raft.designsystem.components.RaftCheckbox
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftSelectionToolbar
import build.raft.designsystem.components.RaftSelectionToolbarAction
import build.raft.designsystem.components.RaftSelectionToolbarClose
import build.raft.designsystem.components.RaftSelectionToolbarDefaults
import build.raft.designsystem.components.RaftSelectionToolbarDock
import build.raft.designsystem.components.RaftSelectionToolbarMore
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * raft-ui SelectionToolbar (selection-toolbar.recipe.ts, raft-ui 0.101) by touch, as the
 * thread's message select mode draws it:
 *  - `selection_toolbar`: "2 selected" · Copy Link · Forward · More · ×; with 0 selected (every
 *    action disabled but ×); a thread's select-all checkbox before "All 12 selected"; Forward
 *    pressed and More open; × focused; with a hint.
 *  - `selection_toolbar_dock`: docked over the bottom of a 390dp phone conversation, 20dp from
 *    its sides and 12dp above its bottom.
 */
class SelectionToolbarScreenshotTest {
    private val appearances = listOf(RaftAppearance.Light, RaftAppearance.Dark)

    private fun capture(name: String, content: @Composable () -> Unit) =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("${name}_${appearance.name.lowercase()}", appearance) { content() }
        }

    @Test
    fun selectionToolbar() =
        capture("selection_toolbar") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Caption("Rest")
                Toolbar(count = 2)
                Caption("0 selected: only × is enabled")
                Toolbar(count = 0)
                Caption("In a thread: select-all, all selected")
                Toolbar(count = 12, selectAll = true)
                Caption("Forward pressed, More open")
                Toolbar(count = 3, pressed = 1, moreOpen = true)
                Caption("× focused")
                Toolbar(count = 3, focused = 3)
                Caption("With a hint")
                Toolbar(count = 3, hint = "1 will be skipped")
            }
        }

    @Test
    fun selectionToolbarDock() =
        capture("selection_toolbar_dock") {
            Box(Modifier.width(390.dp).height(160.dp)) {
                RaftSelectionToolbarDock(modifier = Modifier.align(Alignment.BottomCenter)) {
                    Toolbar(count = 2)
                }
            }
        }

    @Composable
    private fun Toolbar(
        count: Int,
        selectAll: Boolean = false,
        hint: String? = null,
        pressed: Int = -1,
        focused: Int = -1,
        moreOpen: Boolean = false,
    ) {
        val has = count > 0
        val iconSize = RaftSelectionToolbarDefaults.IconSize
        RaftSelectionToolbar(
            count = if (selectAll) "All $count selected" else "$count selected",
            countValue = count.toString(),
            label = "Selected message actions",
            hint = hint,
            leading =
                if (selectAll) {
                    { RaftCheckbox(checked = true, onCheckedChange = {}) }
                } else {
                    null
                },
        ) {
            State(0, pressed, focused) {
                RaftSelectionToolbarAction(label = "Copy Link", onClick = {}, enabled = has) {
                    RaftGlyphIcon(RaftGlyph.Copy, size = iconSize)
                }
            }
            State(1, pressed, focused) {
                RaftSelectionToolbarAction(label = "Forward", onClick = {}, enabled = has) {
                    RaftGlyphIcon(RaftGlyph.Forward, size = iconSize)
                }
            }
            State(2, pressed, focused) {
                RaftSelectionToolbarMore(onClick = {}, label = "More selected message actions", tooltip = "More", enabled = has, open = moreOpen)
            }
            State(3, pressed, focused) {
                RaftSelectionToolbarClose(onClick = {}, label = "Cancel")
            }
        }
    }

    @Composable
    private fun State(index: Int, pressed: Int, focused: Int, content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalRaftPressedPreview provides (index == pressed),
            LocalRaftFocusedPreview provides (index == focused),
            content = content,
        )
    }

    @Composable
    private fun Caption(text: String) {
        RaftText(text = text, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint)
    }
}
