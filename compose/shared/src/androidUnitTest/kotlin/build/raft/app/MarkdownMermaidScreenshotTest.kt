package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.base.BridgeModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IModuleCreator
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PagerManager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.markdown.RaftMarkdownBody
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A Mermaid diagram in a message is laid out with the font it is drawn in: narrow and wide labels
 * fit their boxes, and a bold word follows the words before it. Latin only (the host has no CJK
 * font). Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownMermaidScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    // The diagram card copies its source through the page's bridge: a live, registered page.
    private val livePager =
        object : Pager() {}.apply {
            pagerId = "markdown-mermaid-screenshot"
            pageData = PageData(JSONObject())
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest fun registerPager() = PagerManager.register(livePager)

    @AfterTest fun unregisterPager() = PagerManager.unregister(livePager)

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h600dp-xxhdpi")
    fun labelsFitTheirBoxes() =
        RaftRobolectricScreenshot.capture("markdown.mermaid.phone", RaftAppAppearance.Light, reportDir = "build/reports/home-screenshots", activities = activities) {
            DiagramMessage(DiagramBody)
        }

    // Gantt dates in a dayjs format and a `vert` marker: a code block before mermaid-native 0.1.21.
    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h600dp-xxhdpi")
    fun ganttWithDayjsDatesAndMarker() =
        RaftRobolectricScreenshot.capture("markdown.mermaid.gantt.phone", RaftAppAppearance.Light, reportDir = "build/reports/home-screenshots", activities = activities) {
            DiagramMessage(GanttBody)
        }

    // Block shapes and thick / dotted links: a code block before mermaid-native 0.1.21.
    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h600dp-xxhdpi")
    fun blockShapesAndLinks() =
        RaftRobolectricScreenshot.capture("markdown.mermaid.block.phone", RaftAppAppearance.Light, reportDir = "build/reports/home-screenshots", activities = activities) {
            DiagramMessage(BlockBody)
        }

    @Composable
    private fun DiagramMessage(body: String) {
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(livePager)) {
            RaftDesignSystemTheme {
                Column(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(12.dp)) {
                    RaftMarkdownBody(
                        body = body,
                        modifier = Modifier.fillMaxWidth(),
                        isTagValid = { true },
                    )
                }
            }
        }
    }

    private companion object {
        const val DiagramBody =
            "```mermaid\n" +
                "flowchart TD\n" +
                "subgraph S [\"`How to fix **it**`\"]\n" +
                "A[Build a little list] -->|\"`if it **fails**`\"| B[\"`Retry the **first** step`\"]\n" +
                "end\n" +
                "B --> C[WWW MMM]\n" +
                "B --> D[illicit]\n" +
                "```"

        const val GanttBody =
            "```mermaid\n" +
                "gantt\n" +
                "title Release week\n" +
                "dateFormat DD/MM/YYYY\n" +
                "axisFormat %d %b\n" +
                "section Library\n" +
                "Fix parsers :a1, 23/11/2026, 2d\n" +
                "Publish :a2, after a1, 1d\n" +
                "Freeze :vert, v1, 25/11/2026, 0d\n" +
                "section App\n" +
                "Upgrade :b1, after a2, 1d\n" +
                "```"

        const val BlockBody =
            "```mermaid\n" +
                "block-beta\n" +
                "columns 3\n" +
                "A([\"Request\"]) space B{{\"Check\"}}\n" +
                "space:3\n" +
                "space:2 C((\"Done\"))\n" +
                "A ==> B\n" +
                "B -.-> C\n" +
                "```"
    }
}
