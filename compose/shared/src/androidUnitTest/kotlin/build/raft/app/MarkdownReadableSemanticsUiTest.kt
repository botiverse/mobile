package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.markdown.RaftMarkdownBody
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Message text carries layout-only characters (the web line-break marks after a hyphen and a
 * slash, word joiners in chips); screen readers and semantics finders get the text as written.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h600dp")
class MarkdownReadableSemanticsUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun theTextNodeReadsTheSourceWithoutBreakMarks() =
        runRobolectricComposeUiTest {
            setContent {
                val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                    RaftDesignSystemTheme {
                        Column(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(24.dp)) {
                            RaftMarkdownBody(
                                body = "Reuse the **read-state** fact for traversal/layout/draw now",
                                modifier = Modifier.width(420.dp),
                                isTagValid = { true },
                            )
                        }
                    }
                }
            }
            waitForIdle()
            val node =
                onNode(hasText("read-state fact for traversal/layout/draw", substring = true), useUnmergedTree = true)
                    .fetchSemanticsNode()
            val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
            assertTrue(texts.contains("Reuse the read-state fact for traversal/layout/draw now"), texts)
            assertFalse(texts.any { it == '​' || it == '⁠' || it == '￼' }, "layout-only marks in: $texts")
        }
}
