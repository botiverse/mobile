package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.markdown.RaftMarkdownBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * artin 2026-11-02 (Mac app): in a message body, a mouse press that starts on a link and drags
 * selects text from the press point (it selected nothing: the link's framework box took the
 * press); a click on the link still opens it, a finger tap too, and the link stays a real link
 * (annotation and click action) throughout. Drives [RaftMarkdownBody] inside the production
 * [ThreadInPlaceSelectableBody].
 */
@OptIn(ExperimentalTestApi::class)
// Real text measurement, so a character's box holds the pointer.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h600dp")
class ThreadInPlaceLinkDragSelectUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val opened = mutableListOf<String>()
    private var controller: ThreadInPlaceSelectionController? = null

    private fun ComposeUiTest.showBody() {
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        val host = ThreadInPlaceTextSelectionHost(enabled = true, copyText = {})
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalThreadInPlaceTextSelectionHost provides host,
            ) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(24.dp)) {
                        val inPlace = rememberThreadInPlaceSelectionController(messageId = "m1", enabled = true)
                        controller = inPlace
                        ThreadInPlaceSelectableBody(
                            controller = inPlace,
                            fullMessageText = BODY,
                            wholeMessageText = BODY,
                            messageMenuOpen = false,
                            diagnosticsLogger = {},
                        ) {
                            RaftMarkdownBody(
                                body = BODY,
                                modifier = Modifier.width(600.dp),
                                onOpenUrl = { opened += it },
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.textNode() = onNode(hasText("Read", substring = true), useUnmergedTree = true)

    private fun ComposeUiTest.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        textNode().fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    private fun ComposeUiTest.charCenter(index: Int): Offset = textLayout().getBoundingBox(index).center

    private fun ComposeUiTest.renderedIndexOf(words: String): Int = textLayout().layoutInput.text.text.indexOf(words)

    private fun ComposeUiTest.linkCenter(): Offset = charCenter(renderedIndexOf("the docs") + 1)

    /** The link is a real link whatever the input: a link annotation on the text, and a clickable node. */
    private fun ComposeUiTest.assertLinkSemantics() {
        val text = textLayout().layoutInput.text
        val urls = text.getLinkAnnotations(0, text.length).mapNotNull { (it.item as? LinkAnnotation.Url)?.url }
        assertEquals(listOf(LINK), urls, "the link stays a LinkAnnotation")
        assertTrue(
            onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
            "the link keeps its click action for accessibility"
        )
    }

    @Test
    fun aMouseDragThatStartsOnALinkSelectsTextAndOpensNothing() =
        runRobolectricComposeUiTest {
            showBody()
            assertLinkSemantics()
            val start = linkCenter()
            val end = charCenter(renderedIndexOf("tomorrow") + 3)
            textNode().performMouseInput {
                moveTo(start)
                press()
                moveTo(Offset((start.x + end.x) / 2f, end.y))
                moveTo(end)
                release()
            }
            waitForIdle()
            val selected = controller!!.selectedTexts().joinToString("")
            assertTrue(selected.isNotEmpty(), "a drag from the link selects")
            assertTrue(selected.startsWith("he docs") || selected.startsWith("e docs"), "from the press point: '$selected'")
            assertTrue(selected.contains("tomo"), "to the pointer: '$selected'")
            assertTrue(opened.isEmpty(), "a drag opens no link: $opened")
            assertLinkSemantics()
        }

    @Test
    fun aMouseClickOnALinkOpensIt() =
        runRobolectricComposeUiTest {
            showBody()
            textNode().performMouseInput { click(linkCenter()) }
            waitForIdle()
            assertEquals(listOf(LINK), opened)
            assertTrue(!controller!!.hasSelection, "a click selects nothing")
            assertLinkSemantics()
        }

    @Test
    fun aFingerTapOnALinkStillOpensIt() =
        runRobolectricComposeUiTest {
            showBody()
            textNode().performTouchInput { click(linkCenter()) }
            waitForIdle()
            assertEquals(listOf(LINK), opened)
        }

    private companion object {
        const val LINK = "https://example.com/docs"
        const val BODY = "Read [the docs]($LINK) before the launch review tomorrow"
    }
}
