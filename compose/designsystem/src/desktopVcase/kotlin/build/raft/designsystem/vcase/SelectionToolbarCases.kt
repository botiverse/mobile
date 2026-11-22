package build.raft.designsystem.vcase

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftFocusedPreview
import build.raft.designsystem.components.RaftCheckbox
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftSelectionToolbar
import build.raft.designsystem.components.RaftSelectionToolbarActionSpec
import build.raft.designsystem.components.RaftSelectionToolbarClose
import build.raft.designsystem.components.RaftSelectionToolbarDefaults
import build.raft.designsystem.components.RaftSelectionToolbarMore
import build.raft.designsystem.screenshot.AppIcon
import build.raft.designsystem.screenshot.AppIconImage
import build.raft.designsystem.theme.LocalRaftContentColor
import dev.vcase.VCase

// raft-ui SelectionToolbar (selection-toolbar.recipe.ts, raft-ui 0.101) by touch, as the
// thread's message select mode draws it (web SelectModeToolbar: Forward, then the secondary
// Generate image, Copy Link, Copy MD, then More while some don't fit, then ×).
// These replace SelectionToolbarScreenshotTest (selection_toolbar, selection_toolbar_fit and
// selection_toolbar_dock, each _light/_dark).

private const val MORE = 0
private const val CLOSE = 1

/** Room for every action (no More); with 0 selected (every action disabled but ×); × focused; with a hint. */
@VCase("ds.selection-toolbar.gallery")
@Composable
fun SelectionToolbarGallery() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Caption("Rest, room for every action: no More")
        Toolbar(count = 2)
        Caption("0 selected: only × is enabled")
        Toolbar(count = 0)
        Caption("× focused")
        Toolbar(count = 3, focused = CLOSE)
        Caption("With a hint")
        Toolbar(count = 3, hint = "1 will be skipped")
    }
}

/**
 * Narrower rooms: the secondary actions give way into More, the last first, then (250dp) all
 * three, Forward staying, More open; the hint gives way first; a thread's select-all before
 * "All 12 selected" in a 390dp phone's 350dp dock.
 */
@VCase("ds.selection-toolbar.fit")
@Composable
fun SelectionToolbarFit() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Caption("290dp: Copy MD and Copy Link in More")
        Box(Modifier.width(290.dp)) { Toolbar(count = 3) }
        Caption("250dp: Generate image too, Forward stays; More open")
        Box(Modifier.width(250.dp)) { Toolbar(count = 3, moreOpen = true) }
        Caption("Hint gives way first (330dp)")
        Box(Modifier.width(330.dp)) { Toolbar(count = 3, hint = "1 will be skipped") }
        Caption("350dp (a 390dp phone's dock) in a thread: select-all, all selected")
        Box(Modifier.width(350.dp)) { Toolbar(count = 12, selectAll = true) }
    }
}

@Composable
private fun Toolbar(
    count: Int,
    selectAll: Boolean = false,
    hint: String? = null,
    focused: Int = -1,
    moreOpen: Boolean = false,
) {
    val has = count > 0
    val iconSize = RaftSelectionToolbarDefaults.IconSize
    fun spec(id: String, label: String, secondary: Boolean, icon: @Composable () -> Unit) =
        RaftSelectionToolbarActionSpec(id = id, label = label, onClick = {}, enabled = has, secondary = secondary, icon = icon)
    // The web's order: Forward, then the secondary Generate image, Copy Link, Copy MD.
    val actions =
        listOf(
            spec("forward", "Forward", secondary = false) { RaftGlyphIcon(RaftGlyph.Forward, size = iconSize) },
            spec("generate-image", "Generate image", secondary = true) { AppIconImage(AppIcon.Image, iconSize, LocalRaftContentColor.current) },
            spec("copy-link", "Copy Link", secondary = true) { AppIconImage(AppIcon.Link, iconSize, LocalRaftContentColor.current) },
            spec("copy-md", "Copy MD", secondary = true) { RaftGlyphIcon(RaftGlyph.Copy, size = iconSize) },
        )
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
        actions = actions,
        more = {
            State(MORE, focused) {
                RaftSelectionToolbarMore(onClick = {}, label = "More selected message actions", tooltip = "More", open = moreOpen)
            }
        },
        close = {
            State(CLOSE, focused) {
                RaftSelectionToolbarClose(onClick = {}, label = "Cancel")
            }
        },
    )
}

@Composable
private fun State(index: Int, focused: Int, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRaftFocusedPreview provides (index == focused), content = content)
}
