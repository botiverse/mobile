package build.raft.app.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
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
import build.raft.app.LocalThreadInPlaceTextSelectionHost
import build.raft.app.RAFT_APP_SHELL_PAGE
import build.raft.app.RobolectricComposeActivityRule
import build.raft.app.ThreadInPlaceSelectableBody
import build.raft.app.ThreadInPlaceTextSelectionHost
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.compat.ui.LocalRaftPageActive
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.RaftAndroidSqlDatabaseContext
import build.raft.app.rememberThreadInPlaceSelectionController
import build.raft.app.runRobolectricComposeUiTest
import kotlin.test.Test
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * DIAGNOSTIC (Hands 1dd76297, 0ac26a92): "Key compose_slock_app_shell#0 was used multiple times"
 * on a wide window, a page pushed over the app shell. Each case pushes over a shell-like root
 * page and fails if the shell entry is composed twice.
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

    private fun ComposeUiTest.showShell(
        recomposeDisplayEveryFrame: Boolean = false,
        shell: @Composable () -> Unit,
    ): RaftPageStack {
        RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        val host = ThreadInPlaceTextSelectionHost(enabled = true, copyText = {})
        val stack = RaftPageStack()
        setContent {
            LaunchedEffect(ticking) {
                while (ticking) withFrameNanos { tick++ }
            }
            val frame = if (recomposeDisplayEveryFrame) tick else 0
            RaftMobilePageStackDisplay(stack) { page ->
                check(frame >= 0)
                if (page.args.pageName == RAFT_APP_SHELL_PAGE) {
                    val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
                    CompositionLocalProvider(
                        LocalActivity provides RaftPageActivity(pager),
                        LocalThreadInPlaceTextSelectionHost provides host,
                    ) {
                        RaftDesignSystemTheme { shell() }
                    }
                } else {
                    BasicText("page ${page.contentKey} $tick")
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

    /** The wide shell: a list pane beside a conversation pane (measured in a subcomposition). */
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

    private fun RaftPageStack.push(name: String = COVERING_PAGE, data: JsonObject = JsonObject(emptyMap())) =
        open(name, data, from = pages.last())

    @Test
    fun wideShellPush() =
        runRobolectricComposeUiTest {
            val stack = showShell { WideShell() }
            ticking { runOnIdle { stack.push() } }
        }

    @Test
    fun wideShellPushThenPop() =
        runRobolectricComposeUiTest {
            val stack = showShell { WideShell() }
            ticking { runOnIdle { stack.push() } }
            ticking { runOnIdle { stack.back() } }
        }

    @Test
    fun wideShellPushThenReplaceMidSlide() =
        runRobolectricComposeUiTest {
            val stack = showShell { WideShell() }
            ticking {
                runOnIdle { stack.push() }
                frames(2)
                runOnIdle { stack.push(PREVIEW_PAGE, JsonObject(mapOf(RAFT_REPLACE_CURRENT_PAGE to JsonPrimitive(true)))) }
            }
        }

    @Test
    fun wideShellPushPopPushMidSlide() =
        runRobolectricComposeUiTest {
            val stack = showShell { WideShell() }
            ticking {
                runOnIdle { stack.push() }
                frames(2)
                runOnIdle { stack.back() }
                frames(2)
                runOnIdle { stack.push(PREVIEW_PAGE) }
            }
        }

    @Test
    fun wideShellPushWhileTheDisplayRecomposesEveryFrame() =
        runRobolectricComposeUiTest {
            val stack = showShell(recomposeDisplayEveryFrame = true) { WideShell() }
            ticking { runOnIdle { stack.push() } }
            ticking { runOnIdle { stack.back() } }
        }

    @Test
    fun wideShellWithMovableContentFollowingPageActivePush() =
        runRobolectricComposeUiTest {
            val stack =
                showShell {
                    val body = remember { movableContentOf { WideShell() } }
                    if (LocalRaftPageActive.current) Box(Modifier.fillMaxSize()) { body() } else Column(Modifier.fillMaxSize()) { body() }
                }
            ticking { runOnIdle { stack.push() } }
            ticking { runOnIdle { stack.back() } }
        }

    @Test
    fun wideShellWithMovableContentFollowingMotionPush() =
        runRobolectricComposeUiTest {
            val stack =
                showShell {
                    val body = remember { movableContentOf { WideShell() } }
                    if (LocalRaftStackPageInMotion.current) Box(Modifier.fillMaxSize()) { body() } else Column(Modifier.fillMaxSize()) { body() }
                }
            ticking { runOnIdle { stack.push() } }
            ticking { runOnIdle { stack.back() } }
        }

    @Test
    fun wideShellTwoPushesInOneFrame() =
        runRobolectricComposeUiTest {
            val stack = showShell { WideShell() }
            ticking {
                runOnIdle {
                    stack.push()
                    stack.push(PREVIEW_PAGE)
                }
            }
        }

    private companion object {
        const val COVERING_PAGE = "compose_artifact_viewer"
        const val PREVIEW_PAGE = "compose_file_preview"
        const val BODY = "A selectable message body"
    }
}
