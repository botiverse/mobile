package build.raft.app

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.TextStyle
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.compat.ui.LocalRaftPageActive
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.RaftAndroidSqlDatabaseContext
import build.raft.app.markdown.RaftMarkdownBody
import build.raft.app.nav.RaftMobilePageStackDisplay
import build.raft.app.nav.RaftPageStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
 * [ThreadInPlaceSelectableBody] keeps a body selectable only on the active page for that reason.
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

    @Test
    fun aRowStyleChangingDuringThePushDoesNotCrash() =
        runRobolectricComposeUiTest {
            val stack = showChat {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { SelectableBody("m1") { BasicText(BODY, style = tickStyle) } }
                }
            }
            pushWithRowChange(stack)
        }

    @Test
    fun aRowStyleChangingDuringThePopDoesNotCrash() =
        runRobolectricComposeUiTest {
            val stack = showChat {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { SelectableBody("m1") { BasicText(BODY, style = tickStyle) } }
                }
            }
            runOnIdle { stack.open(COVERING_PAGE, JsonObject(emptyMap()), from = stack.pages.last()) }
            waitForIdle()
            popWithRowChange(stack)
        }

    /** Control: same change, no lazy list, so the row shares the moved composition. */
    @Test
    fun controlNoLazyListRowStyleChangingDuringThePush() =
        runRobolectricComposeUiTest {
            val stack = showChat {
                Column(Modifier.fillMaxSize()) { SelectableBody("m1") { BasicText(BODY, style = tickStyle) } }
            }
            pushWithRowChange(stack)
        }

    /** Control: same change, not selectable: a plain text node reads no CompositionLocal in update. */
    @Test
    fun controlNotSelectableRowStyleChangingDuringThePush() =
        runRobolectricComposeUiTest {
            val stack = showChat {
                LazyColumn(Modifier.fillMaxSize()) { item { BasicText(BODY, style = tickStyle) } }
            }
            pushWithRowChange(stack)
        }

    /**
     * The framework's own behaviour, with no app selection code: an always selectable text in the
     * same place. When a Compose upgrade makes this stop throwing, the gate in
     * [ThreadInPlaceSelectableBody] and this test can go.
     */
    @Test
    fun aPlainSelectionContainerStillThrowsTheFrameworkCrash() {
        var crash: Throwable? = null
        // After the crash the tree is broken: tearing the activity down throws a follow-up error.
        runCatching {
            runRobolectricComposeUiTest {
                val stack = showChat {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item { SelectionContainer { BasicText(BODY, style = tickStyle) } }
                    }
                }
                crash = runCatching { pushWithRowChange(stack) }.exceptionOrNull()
            }
        }
        val thrown = assertIs<IllegalStateException>(crash)
        assertEquals(
            "Cannot read CompositionLocal because the Modifier node is not currently attached.",
            thrown.message,
        )
        val frames = thrown.stackTrace.map { "${it.className}.${it.methodName}" }
        for (frame in listOf("SelectableTextAnnotatedStringNode.retrievePinnableContainer", "LayoutNode.attach", "CopyNodesToNewAnchorLocation")) {
            assertTrue(frames.any { frame in it }, "$frame in $frames")
        }
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

    /** Control: nothing else changes in the frame of the move. */
    @Test
    fun controlPushAndPopWithNoRowChange() =
        runRobolectricComposeUiTest {
            val stack = showChat {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { SelectableBody("m1") { BasicText(BODY, style = TextStyle(color = Color.Black)) } }
                }
            }
            runOnIdle { stack.open(COVERING_PAGE, JsonObject(emptyMap()), from = stack.pages.last()) }
            waitForIdle()
            runOnIdle { stack.back() }
            waitForIdle()
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

    /** As above, with a link: rendered through the link-aware text path. */
    @Test
    fun aMarkdownRowWithALinkRecomposingDuringThePushDoesNotCrash() =
        runRobolectricComposeUiTest {
            val stack = showChat {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        val rowTick = tick
                        SelectableBody("m1") {
                            RaftMarkdownBody(body = LINK_BODY, onOpenUrl = { check(rowTick >= 0) })
                        }
                    }
                }
            }
            pushWithRowChange(stack)
        }

    /** Control: the row recomposes with a stable callback, so the Markdown text keeps its element. */
    @Test
    fun controlMarkdownRowWithAStableCallbackRecomposingDuringThePush() =
        runRobolectricComposeUiTest {
            val onOpenUrl: (String) -> Unit = {}
            val stack = showChat {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        val rowTick = tick
                        BasicText("row $rowTick")
                        SelectableBody("m1") { RaftMarkdownBody(body = LINK_BODY, onOpenUrl = onOpenUrl) }
                    }
                }
            }
            pushWithRowChange(stack)
        }

    /**
     * Hands c561c66a / b40c0cf8 ("LayoutNode should be attached to an owner" in the list's measure
     * pass): switching a body between selectable and plain must not move it to another parent. It
     * used to move (movable content) between the selection container and its parent, in the frame
     * a selection dropped as a reply thread opened, or a page settled after its push.
     */
    @Test
    fun aPageActivitySwitchKeepsTheBodyInItsParentWithItsState() =
        runRobolectricComposeUiTest {
            RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
            val host = ThreadInPlaceTextSelectionHost(enabled = true, copyText = {})
            var pageActive by mutableStateOf(true)
            var controller: ThreadInPlaceSelectionController? = null
            val bodyParents = mutableListOf<LayoutCoordinates?>()
            val bodyStates = mutableListOf<Any>()
            setContent {
                CompositionLocalProvider(
                    LocalThreadInPlaceTextSelectionHost provides host,
                    LocalRaftPageActive provides pageActive,
                ) {
                    val inPlace = rememberThreadInPlaceSelectionController(messageId = "m1", enabled = true)
                    controller = inPlace
                    ThreadInPlaceSelectableBody(
                        controller = inPlace,
                        fullMessageText = BODY,
                        wholeMessageText = BODY,
                        messageMenuOpen = false,
                        diagnosticsLogger = {},
                    ) {
                        val state = remember { Any() }
                        if (bodyStates.none { it === state }) bodyStates += state
                        Column(Modifier.onGloballyPositioned { bodyParents += it.parentLayoutCoordinates }) {
                            BasicText(BODY)
                        }
                    }
                }
            }
            runOnIdle { controller!!.selectAll() }
            waitForIdle()
            assertTrue(controller!!.hasSelection, "selects on the active page")

            // Covered: the selection is dropped in the frame the page goes inactive (RaftDismissOnPageCover).
            runOnIdle {
                pageActive = false
                controller!!.clear()
            }
            waitForIdle()
            runOnIdle { pageActive = true }
            waitForIdle()
            runOnIdle { controller!!.selectAll() }
            waitForIdle()
            assertTrue(controller!!.hasSelection, "selects again once active")

            assertEquals(1, bodyStates.size, "the body keeps its state")
            assertTrue(bodyParents.isNotEmpty(), "the body was placed")
            assertTrue(bodyParents.all { it === bodyParents.first() }, "the body never changes parent: $bodyParents")
        }

    /**
     * The production order of c561c66a: a long, collapsible Markdown row in the list, selected
     * whole, and a reply thread pushed over the page while rows change on every frame.
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
