package build.raft.designsystem.vcase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftPaneGridSizes
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftPaneGrid
import build.raft.designsystem.components.RaftPaneGridCell
import build.raft.designsystem.components.RaftPaneGridCellScope
import build.raft.designsystem.components.RaftPaneGridColumn
import build.raft.designsystem.components.RaftPaneGridColumnBounds
import build.raft.designsystem.components.RaftPaneGridColumnHead
import build.raft.designsystem.components.RaftPaneGridExpand
import build.raft.designsystem.components.RaftPaneGridHeadAction
import build.raft.designsystem.components.RaftPaneGridHeadLabels
import build.raft.designsystem.components.RaftPaneGridMemorySizes
import build.raft.designsystem.components.raftPaneGridNoFade
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import dev.vcase.VCase

// RaftPaneGrid at rest, light and dark: a main pane, a thread column (400) and a column of two
// stacked cells (360, split 60/40), each column behind its `line-seam`; then the same with the
// lower cell folded to its 56dp head at the bottom, no seam under it. Splitters draw nothing at
// rest. Then the #189 ladder: four side columns clipped, and past that a row tab. The cells here are stand-ins: a page fills them with its own heads and bodies;
// the last three give them the design system's column head (#189 step 5): its order, the row tabs in its title's
// place, and a column expanded.
// These replace PaneGridScreenshotTest (each _light/_dark).

private val thread = RaftPaneGridColumnBounds(id = "thread", defaultWidthDp = 400f, minWidthDp = 360f)
private val side = RaftPaneGridColumnBounds(id = "side", defaultWidthDp = 360f, minWidthDp = 280f)

@Composable
private fun PaneGridCase(foldFiles: Boolean) {
    val sizes = remember { RaftPaneGridMemorySizes().apply { if (foldFiles) setCollapsed("files", true) } }
    CompositionLocalProvider(LocalRaftPaneGridSizes provides sizes) {
        Box(Modifier.size(1160.dp, 420.dp)) {
            RaftPaneGrid(
                columns =
                    listOf(
                        RaftPaneGridColumn(thread, resizeLabel = "Resize thread", cells = listOf(RaftPaneGridCell("thread") { Stand("Thread") })),
                        RaftPaneGridColumn(
                            side,
                            resizeLabel = "Resize cards",
                            cells =
                                listOf(
                                    RaftPaneGridCell("tasks", defaultShare = 0.6f) { Stand("Tasks") },
                                    RaftPaneGridCell("files", defaultShare = 0.4f) { Stand("Files") },
                                ),
                        ),
                    ),
            ) { MainStand("Main") }
        }
    }
}

@VCase("ds.pane-grid.gallery")
@Composable
fun PaneGridGallery() {
    PaneGridCase(foldFiles = false)
}

@VCase("ds.pane-grid-folded.gallery")
@Composable
fun PaneGridFoldedGallery() {
    PaneGridCase(foldFiles = true)
}

private val artifact = RaftPaneGridColumnBounds(id = "artifact", defaultWidthDp = 480f, minWidthDp = 480f)
private val profile = RaftPaneGridColumnBounds(id = "profile", defaultWidthDp = 400f, minWidthDp = 320f)

/**
 * The #189 default four side columns (thread, Tasks over Files, Artifact, profile over details)
 * in a row [widthDp] wide: short of their minimums every column clips in proportion, its
 * content kept at its minimum and cut at its edge; past five times 160 the rightmost becomes
 * a tab in its neighbour's column, under the row-tab strip.
 */
@Composable
private fun PaneGridRowCase(widthDp: Int, heads: Boolean = false, expanded: String? = null, media: Boolean = false) {
    val sizes = remember { RaftPaneGridMemorySizes() }
    val rowExpand = remember { RaftPaneGridExpand().apply { if (expanded != null) expand(expanded) } }
    // The cells' heads: stand-ins, or the design system's column head (#189 step 5).
    val cell: @Composable RaftPaneGridCellScope.(String) -> Unit = { title ->
        if (heads) HeadStand(title) else if (media) MediaStand(title) else Stand(title)
    }
    CompositionLocalProvider(LocalRaftPaneGridSizes provides sizes) {
        Box(Modifier.size(widthDp.dp, 420.dp)) {
            RaftPaneGrid(
                columns =
                    listOf(
                        RaftPaneGridColumn(thread, "Resize thread", listOf(RaftPaneGridCell("thread") { cell("Thread") }), tabLabel = "Thread", headCarriesTabs = heads),
                        RaftPaneGridColumn(
                            side,
                            "Resize cards",
                            listOf(RaftPaneGridCell("tasks", 0.6f) { cell("Tasks") }, RaftPaneGridCell("files", 0.4f) { cell("Files") }),
                            tabLabel = "Tasks · Files",
                            headCarriesTabs = heads,
                        ),
                        RaftPaneGridColumn(artifact, "Resize artifact", listOf(RaftPaneGridCell("artifact") { cell("Artifact") }), tabLabel = "Artifact", headCarriesTabs = heads),
                        RaftPaneGridColumn(
                            profile,
                            "Resize profile",
                            listOf(RaftPaneGridCell("profile", 0.5f) { cell("Profile") }, RaftPaneGridCell("details", 0.5f) { cell("Details") }),
                            tabLabel = "Profile · Details",
                            headCarriesTabs = heads,
                        ),
                    ),
                mainTabLabel = "Chat",
                expand = rowExpand,
            ) { MainStand("Main") }
        }
    }
}

@VCase("ds.pane-grid-clipped.gallery")
@Composable
fun PaneGridClippedGallery() {
    PaneGridRowCase(widthDp = 1160)
}

@VCase("ds.pane-grid-tabs.gallery")
@Composable
fun PaneGridTabsGallery() {
    PaneGridRowCase(widthDp = 640)
}

/**
 * The clipped row again, every cell with text and a dark full-bleed block (an image) wider than what shows: the text
 * fades into the column's surface at its end (`layer-canvas`, light or dark, never to transparent), the block is cut
 * hard at the edge ([raftPaneGridNoFade]): no band of the surface glowing over it.
 */
@VCase("ds.pane-grid-clipped-media.gallery")
@Composable
fun PaneGridClippedMediaGallery() {
    PaneGridRowCase(widthDp = 1160, media = true)
}

/**
 * #189 step 5: every cell with the column head: the title, its own action (New), ⋯ (Move Left / Move Right), Expand
 * and Close; a stacked cell's fold chevron first.
 */
@VCase("ds.pane-grid-heads.gallery")
@Composable
fun PaneGridHeadsGallery() {
    PaneGridRowCase(widthDp = 1800, heads = true)
}

/** The row tabs in the head of the column that holds them, in the title's place (rule 4). */
@VCase("ds.pane-grid-head-tabs.gallery")
@Composable
fun PaneGridHeadTabsGallery() {
    PaneGridRowCase(widthDp = 640, heads = true)
}

/** Tasks expanded (rule 5): its column fills the row, every other column at 160 and clipped; its Expand is Restore. */
@VCase("ds.pane-grid-expanded.gallery")
@Composable
fun PaneGridExpandedGallery() {
    PaneGridRowCase(widthDp = 1400, heads = true, expanded = "tasks")
}

/** A cell with the design system's column head: [title], a New action, ⋯ with Move Left / Move Right, Expand, Close. */
@Composable
private fun RaftPaneGridCellScope.HeadStand(title: String) {
    val chevron: (@Composable () -> Unit)? =
        if (collapsible) {
            { RaftGlyphIcon(if (collapsed) RaftGlyph.ChevronRight else RaftGlyph.ChevronDown, size = RaftTheme.controls.barIconSize) }
        } else {
            null
        }
    Column(Modifier.fillMaxSize()) {
        RaftPaneGridColumnHead(
            title = title,
            labels = RaftPaneGridHeadLabels(more = "More Actions", expand = "Expand", restore = "Restore", tabs = "Columns"),
            cellId = cellId,
            closeLabel = "Close $title",
            onClose = {},
            lead = chevron,
            ownActions = listOf(RaftPaneGridHeadAction(id = "new", label = "New", onSelect = {}, glyph = RaftGlyph.Plus)),
            menuItems =
                listOf(
                    RaftPaneGridHeadAction(id = "move-left", label = "Move Left", onSelect = {}, glyph = RaftGlyph.MoveLeft),
                    RaftPaneGridHeadAction(id = "move-right", label = "Move Right", onSelect = {}, glyph = RaftGlyph.MoveRight, enabled = false),
                ),
        )
    }
}

/** A cell stand-in: its 56dp head line, folded or not, and the body under it. */
@Composable
private fun RaftPaneGridCellScope.Stand(title: String) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
            RaftText(if (collapsible && collapsed) "$title (folded)" else title, style = RaftTheme.typography.headline)
        }
    }
}

/** A cell stand-in with content to its minimum's edge: text, a dark full-bleed block (an image), text. */
@Composable
private fun RaftPaneGridCellScope.MediaStand(title: String) {
    val line = "$title: a line of text long enough to run past the edge of a clipped column"
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
            RaftText(title, style = RaftTheme.typography.headline)
        }
        RaftText(line, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 16.dp))
        Box(Modifier.padding(vertical = 12.dp).raftPaneGridNoFade().fillMaxWidth().height(96.dp).background(MediaInk))
        RaftText(line, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

/** The stand-in image: dark in both themes, as a photo or a dark page is. */
private val MediaInk = Color(0xFF1B2430)

@Composable
private fun MainStand(title: String) {
    Box(Modifier.fillMaxSize().padding(16.dp)) { RaftText(title, style = RaftTheme.typography.headline) }
}
