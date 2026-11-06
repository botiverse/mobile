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
import kotlin.test.Test
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
    fun lazyRowStyleChangesInThePushFrame() =
        runRobolectricComposeUiTest {
            val stack = showChat {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { SelectableBody("m1") { BasicText(BODY, style = tickStyle) } }
                }
            }
            pushWithRowChange(stack)
        }

    @Test
    fun lazyRowStyleChangesInThePopFrame() =
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
    fun controlNoLazyListColumnRowStyleChangesInThePushFrame() =
        runRobolectricComposeUiTest {
            val stack = showChat {
                Column(Modifier.fillMaxSize()) { SelectableBody("m1") { BasicText(BODY, style = tickStyle) } }
            }
            pushWithRowChange(stack)
        }

    /** Control: same change, not selectable: a plain text node reads no CompositionLocal in update. */
    @Test
    fun controlNotSelectableLazyRowStyleChangesInThePushFrame() =
        runRobolectricComposeUiTest {
            val stack = showChat {
                LazyColumn(Modifier.fillMaxSize()) { item { BasicText(BODY, style = tickStyle) } }
            }
            pushWithRowChange(stack)
        }

    /** Control: plain SelectionContainer, no app selection code: is it the framework alone? */
    @Test
    fun frameworkSelectionContainerLazyRowStyleChangesInThePushFrame() =
        runRobolectricComposeUiTest {
            val stack = showChat {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { SelectionContainer { BasicText(BODY, style = tickStyle) } }
                }
            }
            pushWithRowChange(stack)
        }

    /** Control: nothing else changes in the frame of the move. */
    @Test
    fun controlLazyRowPushAndPopWithNoOtherChange() =
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
    fun lazyRowMarkdownOnlyRecomposesInThePushFrame() =
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
    fun lazyRowMarkdownWithLinkOnlyRecomposesInThePushFrame() =
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

    /** The row's text colour-free recomposition with a stable callback: Markdown's own stability. */
    @Test
    fun lazyRowMarkdownStableCallbackRecomposesInThePushFrame() =
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

    private companion object {
        const val FRAMES_BEFORE = 3
        const val FRAMES_AFTER = 40
        const val CHAT = "compose_thread"
        const val COVERING_PAGE = "compose_artifact_viewer"
        const val BODY = "A selectable message body"
        const val LINK_BODY = "Read [the docs](https://raft.build/docs) for details"
    }
}
