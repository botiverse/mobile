package build.raft.app.nav

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import build.raft.app.thread.LocalThreadInPlaceTextSelectionHost
import build.raft.app.RAFT_APP_SHELL_PAGE
import build.raft.app.RobolectricComposeActivityRule
import build.raft.app.thread.ThreadInPlaceSelectableBody
import build.raft.app.thread.ThreadInPlaceTextSelectionHost
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.compat.ui.LocalRaftPageActive
import build.raft.app.compat.ui.collectAsStateWhileStarted
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.RaftAndroidSqlDatabaseContext
import build.raft.app.thread.rememberThreadInPlaceSelectionController
import build.raft.app.runRobolectricComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Hands 1dd76297 (1.26.0, OPPO PLP110 unfolded) and 0ac26a92 (1.25.1, Galaxy Fold unfolded):
 * "IllegalArgumentException: Key compose_slock_app_shell#0 was used multiple times" as a page was
 * pushed over the app shell on a wide window (the image preview after attaching a photo, the
 * Artifact viewer from an Activity row). The shell was Navigation 3's root entry, composed as movable
 * content and moved into the scene of every push and pop, with all it holds on the wide layout (the
 * conversation, its lists and their selectable rows), and SaveableStateHolder found its key composed
 * twice. [RaftMobilePageStackDisplay] now composes the root page once, outside NavDisplay: it is
 * never moved, so it is never composed a second time, and it is drawn exactly where it was before.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h700dp-xhdpi")
class RaftMobileStackShellOnceUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var tick by mutableIntStateOf(0)
    private var ticking by mutableStateOf(false)

    private val tickStyle: TextStyle
        get() = TextStyle(color = if (tick % 2 == 0) Color.Black else Color.DarkGray)

    /** How often the shell was created, how many instances were composed at most at once, its node detached. */
    private var shellCreated = 0
    private var shellAlive = 0
    private var shellMostAlive = 0
    private var shellDetached = 0
    private val onShellDetached: () -> Unit = { shellDetached++ }
    private var artifactDetached = 0
    private val onArtifactDetached: () -> Unit = { artifactDetached++ }
    private val onOtherPageDetached: () -> Unit = {}
    private var shellActive: Boolean? = null
    private var shellTaps = 0
    private var shellWidth = -1
    private var pageInMotion: Boolean? = null
    private var pageWidth = -1
    private var dispatcher: OnBackPressedDispatcher? = null
    private var shellLifecycle: Lifecycle.State? = null
    private var shellSaved: String? = null

    private fun ComposeUiTest.showShell(shell: @Composable () -> Unit): RaftPageStack {
        RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        val host = ThreadInPlaceTextSelectionHost(enabled = true, copyText = {})
        val stack = RaftPageStack()
        setContent {
            dispatcher = LocalContext.current.componentActivity().onBackPressedDispatcher
            LaunchedEffect(ticking) {
                while (ticking) withFrameNanos { tick++ }
            }
            RaftMobilePageStackDisplay(stack) { page ->
                if (page.args.pageName == RAFT_APP_SHELL_PAGE) {
                    val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
                    remember { shellCreated++ }
                    DisposableEffect(Unit) {
                        shellMostAlive = maxOf(shellMostAlive, ++shellAlive)
                        onDispose { shellAlive-- }
                    }
                    shellActive = LocalRaftPageActive.current
                    shellLifecycle = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
                    shellSaved = rememberSaveable { "shell-${shellCreated}" }
                    CompositionLocalProvider(
                        LocalActivity provides RaftPageActivity(pager),
                        LocalThreadInPlaceTextSelectionHost provides host,
                    ) {
                        RaftDesignSystemTheme {
                            Box(
                                Modifier.fillMaxSize()
                                    .testTag(SHELL)
                                    .then(DetachCounter(onShellDetached))
                                    .onSizeChanged { shellWidth = it.width }
                                    .clickable { shellTaps++ },
                            ) { shell() }
                        }
                    }
                } else {
                    val detached = if (page.args.pageName == ARTIFACT_PAGE) onArtifactDetached else onOtherPageDetached
                    if (page.args.pageName == ARTIFACT_PAGE) pageInMotion = LocalRaftStackPageInMotion.current
                    Box(
                        Modifier.fillMaxSize()
                            .testTag(page.args.pageName)
                            .then(DetachCounter(detached))
                            .onSizeChanged { if (page.args.pageName == ARTIFACT_PAGE) pageWidth = it.width },
                    ) {
                        BasicText("page ${page.contentKey} $tick")
                    }
                }
            }
        }
        waitForIdle()
        return stack
    }

    @Composable
    private fun SelectableRow(id: String) {
        val controller = rememberThreadInPlaceSelectionController(messageId = id, enabled = true)
        ThreadInPlaceSelectableBody(
            controller = controller,
            fullMessageText = BODY,
            wholeMessageText = BODY,
            messageMenuOpen = false,
            diagnosticsLogger = {},
        ) {
            BasicText(BODY, style = tickStyle)
        }
    }

    /** The wide shell: the conversation list beside the open conversation, its rows changing. */
    @Composable
    private fun WideShell() {
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f)) { items(20) { BasicText("channel $it $tick") } }
            BoxWithConstraints(Modifier.weight(2f)) {
                check(maxWidth.value >= 0f)
                LazyColumn(Modifier.fillMaxSize()) { items(12) { SelectableRow("m$it") } }
            }
        }
    }

    private fun ComposeUiTest.frames(count: Int) = repeat(count) { mainClock.advanceTimeByFrame() }

    /** Runs [block] with the shell's rows changing on every frame, through its whole motion. */
    private fun ComposeUiTest.ticking(block: ComposeUiTest.() -> Unit) {
        mainClock.autoAdvance = false
        runOnIdle { ticking = true }
        frames(3)
        block()
        frames(40)
        runOnIdle { ticking = false }
        mainClock.autoAdvance = true
        waitForIdle()
    }

    private fun RaftPageStack.push(name: String = ARTIFACT_PAGE, data: JsonObject = JsonObject(emptyMap())) =
        open(name, data, from = pages.last())

    private fun assertShellComposedOnceInPlace() {
        assertEquals(1, shellCreated, "the shell was created again")
        assertEquals(1, shellMostAlive, "the shell was composed twice at once")
        assertEquals(0, shellDetached, "the shell was moved (its layout node detached)")
        assertEquals("shell-1", shellSaved)
    }

    @Test
    fun aPushAndPopOverTheWideShellComposeTheShellOnceInPlace() =
        runRobolectricComposeUiTest {
            val stack = showShell { WideShell() }
            ticking { runOnIdle { stack.push(PREVIEW_PAGE) } }
            ticking { runOnIdle { stack.back() } }
            ticking { runOnIdle { stack.push(ARTIFACT_PAGE) } }
            ticking { runOnIdle { stack.push(PREVIEW_PAGE) } }
            ticking { runOnIdle { stack.back() } }
            ticking { runOnIdle { stack.back() } }
            assertShellComposedOnceInPlace()
        }

    /**
     * A predictive back gesture from a page over the shell shows the shell under the dragged page for
     * the whole drag (the gesture holds the pop mid motion), keeps the page and the shell inactive when cancelled, and a
     * committed gesture leaves a shell that takes taps through NavDisplay's empty slot.
     */
    @Test
    fun aPredictiveBackShowsTheShellWhileDraggedAndTheShellTakesTapsAfterIt() =
        runRobolectricComposeUiTest {
            val stack = showShell { Box(Modifier.fillMaxSize()) }
            runOnIdle { stack.push() }
            waitForIdle()
            onNodeWithTag(SHELL).assertIsNotDisplayed()

            runOnIdle {
                dispatcher!!.dispatchOnBackStarted(BackEventCompat(10f, 500f, 0f, BackEventCompat.EDGE_LEFT))
                dispatcher!!.dispatchOnBackProgressed(BackEventCompat(200f, 500f, 0.4f, BackEventCompat.EDGE_LEFT))
            }
            waitForIdle()
            onNodeWithTag(SHELL).assertIsDisplayed()
            onNodeWithTag(ARTIFACT_PAGE).assertExists()

            runOnIdle { dispatcher!!.dispatchOnBackCancelled() }
            waitForIdle()
            assertEquals(2, stack.pages.size, "a cancelled gesture keeps the page")
            // The page is back on top and the shell stays inactive. (After a cancel the covered shell
            // keeps the size it had under the drag until the next stack change, as the root entry's
            // box did in the scene before; it is under the opaque page.)
            onNodeWithTag(ARTIFACT_PAGE).assertIsDisplayed()
            assertFalse(shellActive == true, "covered again after the cancel: inactive")

            runOnIdle {
                dispatcher!!.dispatchOnBackStarted(BackEventCompat(10f, 500f, 0f, BackEventCompat.EDGE_LEFT))
                dispatcher!!.dispatchOnBackProgressed(BackEventCompat(300f, 500f, 0.6f, BackEventCompat.EDGE_LEFT))
            }
            waitForIdle()
            onNodeWithTag(SHELL).assertIsDisplayed()
            runOnIdle { dispatcher!!.onBackPressed() }
            waitForIdle()
            assertEquals(1, stack.pages.size, "a released gesture pops the page")
            onNodeWithTag(SHELL).performTouchInput { click(center) }
            waitForIdle()
            assertEquals(1, shellTaps, "the shell takes a tap after the committed gesture")
            assertShellComposedOnceInPlace()
        }

    /** After a plain pop the shell, drawn under NavDisplay, takes a real tap through the empty slot. */
    @Test
    fun theShellTakesATapAfterAPop() =
        runRobolectricComposeUiTest {
            val stack = showShell { Box(Modifier.fillMaxSize()) }
            onNodeWithTag(SHELL).performTouchInput { click(center) }
            waitForIdle()
            assertEquals(1, shellTaps)
            runOnIdle { stack.push() }
            waitForIdle()
            runOnIdle { stack.back() }
            waitForIdle()
            onNodeWithTag(SHELL).performTouchInput { click(center) }
            waitForIdle()
            assertEquals(2, shellTaps, "the shell takes a tap after the pop")
        }

    /**
     * While covered by a pushed page, [collectAsStateWhileStarted] pauses upstream emissions so the
     * hidden shell does not recompose mid-transition, and catches up as soon as the pop lands back.
     */
    @Test
    fun coveredShellPausesLifecycleGatedCollectionAndCatchesUpOnPop() =
        runRobolectricComposeUiTest {
            val sharedCounter = MutableStateFlow(1)
            var observedInShell = -1
            var shellCollectorRecompositions = 0
            val stack = showShell {
                val value by sharedCounter.collectAsStateWhileStarted()
                shellCollectorRecompositions++
                observedInShell = value
                Box(Modifier.fillMaxSize())
            }
            assertEquals(1, observedInShell)

            runOnIdle { stack.push() }
            waitForIdle()
            val recompositionsWhileCovered = shellCollectorRecompositions

            runOnIdle {
                sharedCounter.value = 2
                sharedCounter.value = 3
            }
            waitForIdle()
            assertEquals(1, observedInShell, "covered shell holds last value while paused")
            assertEquals(recompositionsWhileCovered, shellCollectorRecompositions, "covered shell does not recompose on store updates")

            runOnIdle { stack.back() }
            waitForIdle()
            assertEquals(3, observedInShell, "returning shell catches up to the latest StateFlow value")
        }

    private fun Context.componentActivity(): ComponentActivity {
        var context: Context = this
        while (context !is ComponentActivity) context = (context as ContextWrapper).baseContext
        return context
    }

    private class DetachCounter(private val onDetached: () -> Unit) : ModifierNodeElement<DetachCounterNode>() {
        override fun create() = DetachCounterNode(onDetached)

        override fun update(node: DetachCounterNode) {
            node.onDetached = onDetached
        }

        override fun equals(other: Any?): Boolean = other is DetachCounter && other.onDetached === onDetached

        override fun hashCode(): Int = onDetached.hashCode()
    }

    private class DetachCounterNode(var onDetached: () -> Unit) : Modifier.Node() {
        override fun onDetach() = onDetached()
    }

    private companion object {
        const val SHELL = "shell"
        const val ARTIFACT_PAGE = "compose_artifact_viewer"
        const val PREVIEW_PAGE = "compose_file_preview"
        const val BODY = "A selectable message body"
    }
}
