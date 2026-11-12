package build.raft.designsystem.screenshot

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
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftPaneGridSizes
import build.raft.designsystem.components.RaftPaneGrid
import build.raft.designsystem.components.RaftPaneGridCell
import build.raft.designsystem.components.RaftPaneGridCellScope
import build.raft.designsystem.components.RaftPaneGridColumn
import build.raft.designsystem.components.RaftPaneGridColumnBounds
import build.raft.designsystem.components.RaftPaneGridMemorySizes
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * RaftPaneGrid at rest, light and dark: a main pane, a thread column (400) and a column of two
 * stacked cells (360, split 60/40), each column behind its `line-seam`; then the same with the
 * lower cell folded to its 56dp head at the bottom, no seam under it. Splitters draw nothing at
 * rest. Then the #189 ladder: four side columns clipped, and past that a row tab. The cells here are stand-ins: a page fills them with its own heads and bodies.
 */
class PaneGridScreenshotTest {
    private val appearances = listOf(RaftAppearance.Light, RaftAppearance.Dark)
    private val thread = RaftPaneGridColumnBounds(id = "thread", defaultWidthDp = 400f, minWidthDp = 360f)
    private val side = RaftPaneGridColumnBounds(id = "side", defaultWidthDp = 360f, minWidthDp = 280f)

    private fun capture(name: String, foldFiles: Boolean) =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("${name}_${appearance.name.lowercase()}", appearance) {
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
        }

    @Test
    fun paneGrid() = capture("pane_grid", foldFiles = false)

    @Test
    fun paneGridFolded() = capture("pane_grid_folded", foldFiles = true)

    private val artifact = RaftPaneGridColumnBounds(id = "artifact", defaultWidthDp = 480f, minWidthDp = 480f)
    private val profile = RaftPaneGridColumnBounds(id = "profile", defaultWidthDp = 400f, minWidthDp = 320f)

    /**
     * The #189 default four side columns (thread, Tasks over Files, Artifact, profile over details)
     * in a row [widthDp] wide: short of their minimums every column clips in proportion, its
     * content kept at its minimum and cut at its edge; past five times 160 the rightmost becomes
     * a tab in its neighbour's column, under the row-tab strip.
     */
    private fun captureRow(name: String, widthDp: Int) =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("${name}_${appearance.name.lowercase()}", appearance) {
                val sizes = remember { RaftPaneGridMemorySizes() }
                CompositionLocalProvider(LocalRaftPaneGridSizes provides sizes) {
                    Box(Modifier.size(widthDp.dp, 420.dp)) {
                        RaftPaneGrid(
                            columns =
                                listOf(
                                    RaftPaneGridColumn(thread, "Resize thread", listOf(RaftPaneGridCell("thread") { Stand("Thread") }), tabLabel = "Thread"),
                                    RaftPaneGridColumn(
                                        side,
                                        "Resize cards",
                                        listOf(RaftPaneGridCell("tasks", 0.6f) { Stand("Tasks") }, RaftPaneGridCell("files", 0.4f) { Stand("Files") }),
                                        tabLabel = "Tasks · Files",
                                    ),
                                    RaftPaneGridColumn(artifact, "Resize artifact", listOf(RaftPaneGridCell("artifact") { Stand("Artifact") }), tabLabel = "Artifact"),
                                    RaftPaneGridColumn(
                                        profile,
                                        "Resize profile",
                                        listOf(RaftPaneGridCell("profile", 0.5f) { Stand("Profile") }, RaftPaneGridCell("details", 0.5f) { Stand("Details") }),
                                        tabLabel = "Profile · Details",
                                    ),
                                ),
                            mainTabLabel = "Chat",
                        ) { MainStand("Main") }
                    }
                }
            }
        }

    @Test
    fun paneGridClipped() = captureRow("pane_grid_clipped", widthDp = 1160)

    @Test
    fun paneGridTabs() = captureRow("pane_grid_tabs", widthDp = 640)
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

@Composable
private fun MainStand(title: String) {
    Box(Modifier.fillMaxSize().padding(16.dp)) { RaftText(title, style = RaftTheme.typography.headline) }
}
