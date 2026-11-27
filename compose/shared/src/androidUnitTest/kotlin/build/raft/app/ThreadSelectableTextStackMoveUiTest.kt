package build.raft.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.TextStyle
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.RaftAndroidSqlDatabaseContext
import build.raft.app.markdown.RaftMarkdownBody
import build.raft.app.nav.RaftMobilePageStackDisplay
import build.raft.app.nav.RaftPageStack
import build.raft.app.thread.LocalThreadInPlaceTextSelectionHost
import build.raft.app.thread.RaftLongMessageCollapseController
import build.raft.app.thread.RaftLongMessageCollapsibleBody
import build.raft.app.thread.ThreadInPlaceSelectableBody
import build.raft.app.thread.ThreadInPlaceSelectionController
import build.raft.app.thread.ThreadInPlaceTextSelectionHost
import build.raft.app.thread.raftLongMessageCollapseViewport
import build.raft.app.thread.rememberThreadInPlaceSelectionController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Android crash "Cannot read CompositionLocal because the Modifier node is not currently attached"
 * (Hands 11b1a052, a975d7ca, 6cfdfcde). Navigation 3 moves a stack entry between scenes as movable
 * content. A message row lives in its lazy list's own composition, so when it recomposes in the
 * frame of the move its modifier update reaches a detached LayoutNode and is applied on re-attach,
 * where SelectableTextAnnotatedStringNode.update reads a CompositionLocal unconditionally.
 * Robolectric runs the Android foundation artifact, and the app pins the patched
 * androidx.compose.foundation:foundation-android 1.12.1-raft.1 (third_party/compose-foundation-1.12.1:
 * the node returns early while it is detached, and onAttach sets its pinnable container again), so
 * these tests exercise the patched classes: [ThreadInPlaceSelectableBody] keeps a body in its
 * selection container on every page, and every move below must complete without the crash.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
class ThreadSelectableTextStackMoveUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var tick by mutableIntStateOf(0)
    private var ticking by mutableStateOf(false)

    private val tickStyle: TextStyle
        get() = TextStyle(color = if (tick % 2 == 0) Color.Black else Color.DarkGray)

    private fun ComposeUiTest.showChat(body: @Composable () -> Unit): RaftPageStack {
        RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        val host = ThreadInPlaceTextSelectionHost(enabled = true, copyText = {})
        val stack = RaftPageStack()
        setContent {
            // Outside the moved page. NavDisplay's scene changes a frame or more after the stack
            // (SeekableTransitionState.animateTo runs in an effect), so a row changes every frame.
            LaunchedEffect(ticking) {
                while (ticking) withFrameNanos { tick++ }
            }
            RaftMobilePageStackDisplay(stack) { page ->
                if (page.args.pageName == CHAT) {
                    val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
                    CompositionLocalProvider(
                        LocalActivity provides RaftPageActivity(pager),
                        LocalThreadInPlaceTextSelectionHost provides host,
                    ) {
                        RaftDesignSystemTheme { body() }
                    }
                }
            }
        }
        runOnIdle { stack.open(CHAT, JsonObject(emptyMap()), from = stack.pages.first()) }
        waitForIdle()
        return stack
    }

    @Composable
    private fun SelectableBody(id: String, content: @Composable () -> Unit) {
        val controller = rememberThreadInPlaceSelectionController(messageId = id, enabled = true)
        ThreadInPlaceSelectableBody(
            controller = controller,
            fullMessageText = BODY,
            wholeMessageText = BODY,
            messageMenuOpen = false,
            diagnosticsLogger = {},
            content = content,
        )
    }

    /** Runs [change] with the rows changing on every frame around it, through its whole motion. */
    private fun ComposeUiTest.withRowChangingEveryFrame(change: () -> Unit) {
        mainClock.autoAdvance = false
        runOnIdle { ticking = true }
        repeat(FRAMES_BEFORE) { mainClock.advanceTimeByFrame() }
        runOnIdle(change)
        repeat(FRAMES_AFTER) { mainClock.advanceTimeByFrame() }
        runOnIdle { ticking = false }
        mainClock.autoAdvance = true
        waitForIdle()
    }

    private fun ComposeUiTest.pushWithRowChange(stack: RaftPageStack) =
        withRowChangingEveryFrame { stack.open(COVERING_PAGE, JsonObject(emptyMap()), from = stack.pages.last()) }

    private fun ComposeUiTest.popWithRowChange(stack: RaftPageStack) = withRowChangingEveryFrame { stack.back() }

    /**
     * The framework's own behaviour, with no app selection code: an always selectable text in the
     * same place. On the official foundation 1.12.1 this push threw "Cannot read CompositionLocal
     * because the Modifier node is not currently attached." from
     * SelectableTextAnnotatedStringNode.retrievePinnableContainer, under LayoutNode.attach as the
     * moved row's nodes went to their new place. With the patched foundation the push completes.
     */
    @Test
    fun aPlainSelectionContainerCompletesTheStackMoveWithThePatchedFoundation() {
        var crash: Throwable? = null
        var topPage: String? = null
        // Were the crash back, tearing the broken tree down would throw a follow-up error: keep the first.
        runCatching {
            runRobolectricComposeUiTest {
                val stack = showChat {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item { SelectionContainer { BasicText(BODY, style = tickStyle) } }
                    }
                }
                crash = runCatching { pushWithRowChange(stack) }.exceptionOrNull()
                topPage = stack.pages.last().args.pageName
            }
        }.onFailure { if (crash == null) crash = it }
        assertNull(crash, "the stack move threw: ${crash?.stackTraceToString()}")
        assertEquals(COVERING_PAGE, topPage, "the pushed page is on top once the move has run")
    }

    /** A selection is dropped when the page is covered, and the body selects again once back. */
    @Test
    fun aSelectionIsClearedWhenCoveredAndTheBodySelectsAgainAfterThePop() =
        runRobolectricComposeUiTest {
            var controller: ThreadInPlaceSelectionController? = null
            val stack = showChat {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        val inPlace = rememberThreadInPlaceSelectionController(messageId = "m1", enabled = true)
                        controller = inPlace
                        ThreadInPlaceSelectableBody(
                            controller = inPlace,
                            fullMessageText = BODY,
                            wholeMessageText = BODY,
                            messageMenuOpen = false,
                            diagnosticsLogger = {},
                        ) {
                            BasicText(BODY)
                        }
                    }
                }
            }
            runOnIdle { controller!!.selectAll() }
            waitForIdle()
            assertTrue(controller!!.hasSelection, "selects on the active page")

            runOnIdle { stack.open(COVERING_PAGE, JsonObject(emptyMap()), from = stack.pages.last()) }
            waitForIdle()
            assertFalse(controller!!.hasSelection, "covering the page drops the selection")

            runOnIdle { stack.back() }
            waitForIdle()
            runOnIdle { controller!!.selectAll() }
            waitForIdle()
            assertTrue(controller!!.hasSelection, "selects again after the pop")
        }

    /**
     * Same body and style: only the row recomposes (a reply chip's count, say), with a fresh
     * callback as ThreadPage's row passes. Does the real Markdown text then update its element?
     */
    @Test
    fun aMarkdownRowRecomposingDuringThePushDoesNotCrash() =
        runRobolectricComposeUiTest {
            val stack = showChat {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        val rowTick = tick
                        SelectableBody("m1") { RaftMarkdownBody(body = BODY, onOpenUrl = { check(rowTick >= 0) }) }
                    }
                }
            }
            pushWithRowChange(stack)
        }

    /**
     * The production order of Hands c561c66a / b40c0cf8 ("LayoutNode should be attached to an owner"
     * in the list's measure pass, from when a body still moved in and out of its selection container
     * as its page changed activity): a long, collapsible Markdown row in the list, selected whole,
     * and a reply thread pushed over the page while rows change on every frame.
     */
    @Test
    fun aSelectedCollapsibleMarkdownRowDuringThePushAndPopDoesNotCrash() =
        runRobolectricComposeUiTest {
            var controller: ThreadInPlaceSelectionController? = null
            val collapse = RaftLongMessageCollapseController()
            val stack = showChat {
                LazyColumn(Modifier.fillMaxSize().raftLongMessageCollapseViewport(collapse, verticalContentPaddingPx = 0)) {
                    item {
                        val rowTick = tick
                        RaftLongMessageCollapsibleBody(
                            controller = collapse,
                            messageId = "m1",
                            contentKey = "m1:${LONG_BODY.length}",
                            alignEnd = false,
                            onToggle = {},
                        ) {
                            val inPlace = rememberThreadInPlaceSelectionController(messageId = "m1", enabled = true)
                            controller = inPlace
                            ThreadInPlaceSelectableBody(
                                controller = inPlace,
                                fullMessageText = LONG_BODY,
                                wholeMessageText = LONG_BODY,
                                messageMenuOpen = false,
                                diagnosticsLogger = {},
                            ) {
                                RaftMarkdownBody(body = LONG_BODY, onOpenUrl = { check(rowTick >= 0) })
                            }
                        }
                    }
                }
            }
            runOnIdle { controller!!.selectAll() }
            waitForIdle()
            assertTrue(controller!!.hasSelection, "selects on the active page")

            pushWithRowChange(stack)
            assertFalse(controller!!.hasSelection, "covering the page drops the selection")
            popWithRowChange(stack)
            runOnIdle { controller!!.selectAll() }
            waitForIdle()
            assertTrue(controller!!.hasSelection, "selects again after the pop")
        }

    private companion object {
        val LONG_BODY = (1..40).joinToString("\n\n") { "Paragraph $it of a long message, with [a link](https://raft.build/docs)." }
        const val FRAMES_BEFORE = 3
        const val FRAMES_AFTER = 40
        const val CHAT = "compose_thread"
        const val COVERING_PAGE = "compose_artifact_viewer"
        const val BODY = "A selectable message body"
        const val LINK_BODY = "Read [the docs](https://raft.build/docs) for details"
    }
}
