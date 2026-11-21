package build.raft.app

import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.compat.ui.RaftProvidePageActive
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.RaftAndroidSqlDatabaseContext
import build.raft.app.nav.RaftMobilePageStackDisplay
import build.raft.app.nav.RaftPageStack
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * in TextFieldCoreModifierNode.startCursorJob (main f467aed8, task #wg-mobile-sync:#197): a text
 * field whose caret shows for the first time in the frame Navigation 3 moves its page between
 * scenes. Its row lives in a composition of its own (here a lazy list's row), so its modifier
 * update reaches the moved, detached LayoutNode and is applied on re-attach, before the node counts
 * as attached; the caret's first showing reads LocalCursorBlinkEnabled there. The app's text fields
 * show no caret until their page has settled ([raftPageGatedCursorBrush], RaftPageMoveSafety.kt).
 *
 * Which frame the page moves in depends on NavDisplay (a frame or more after the stack change), so
 * each case focuses a fresh field (a node whose caret never showed) at every frame from the change on.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
class RaftTextFieldCaretStackMoveUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    /** One field node per attempt: a caret shows "for the first time" once per node. */
    private var attempt by mutableIntStateOf(0)
    private val requester = FocusRequester()

    /** Fields that gained focus (the requests land: a failure then says what did not happen). */
    private var focusGains = 0

    private fun ComposeUiTest.showChat(field: @Composable (FocusRequester) -> Unit): RaftPageStack {
        RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        val stack = RaftPageStack()
        var composeView: View? = null
        setContent {
            composeView = LocalView.current
            RaftMobilePageStackDisplay(stack) { page ->
                if (page.args.pageName == CHAT) {
                    val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
                    CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                        RaftDesignSystemTheme {
                            LazyColumn(Modifier.fillMaxSize()) {
                                item { key(attempt) { field(requester) } }
                            }
                        }
                    }
                }
            }
        }
        // A field shows its caret only in a focused window (BasicTextField: focused && window
        // focused), and Robolectric's activity window never gains focus on its own
        // (ThreadComposerImeCompositionTest).
        runOnIdle { checkNotNull(composeView).rootView.dispatchWindowFocusChanged(true) }
        runOnIdle { stack.open(CHAT, JsonObject(emptyMap()), from = stack.pages.first()) }
        waitForIdle()
        return stack
    }

    /** The composer's editor: the app's [TextField], marked as a conversation composer. */
    @Composable
    private fun AppField(requester: FocusRequester) {
        CompositionLocalProvider(LocalRaftEditorIsConversationComposer provides true) {
            TextField(
                modifier = Modifier.onFocusChanged { if (it.isFocused) focusGains++ },
                value = "",
                autoFocus = false,
                onValueChange = {},
                focusRequester = requester,
            )
        }
    }

    /** Foundation's text field with a caret, and no app code around it. */
    @Composable
    private fun PlainField(requester: FocusRequester) {
        BasicTextField(
            state = rememberTextFieldState(),
            cursorBrush = SolidColor(Color.Black),
            modifier = Modifier.focusRequester(requester).onFocusChanged { if (it.isFocused) focusGains++ },
        )
    }

    /**
     * For each frame from 0 to [FOCUS_FRAMES] after [change]: [prepare] the stack, a fresh field,
     * [change], the field focused that many frames later, the motion run to its end, then [restore].
     */
    private fun ComposeUiTest.focusAtEachFrameOf(prepare: () -> Unit, change: () -> Unit, restore: () -> Unit) {
        for (frame in 0..FOCUS_FRAMES) {
            runOnIdle {
                prepare()
                attempt++
            }
            waitForIdle()
            mainClock.autoAdvance = false
            runOnIdle(change)
            repeat(frame) { mainClock.advanceTimeByFrame() }
            runOnIdle { requester.requestFocus() }
            repeat(FRAMES_AFTER) { mainClock.advanceTimeByFrame() }
            mainClock.autoAdvance = true
            waitForIdle()
            runOnIdle(restore)
            waitForIdle()
        }
    }

    private fun ComposeUiTest.focusDuringPush(stack: RaftPageStack) =
        focusAtEachFrameOf(
            prepare = {},
            change = { stack.open(COVERING_PAGE, JsonObject(emptyMap()), from = stack.pages.last()) },
            restore = { stack.back() },
        )

    private fun ComposeUiTest.focusDuringPop(stack: RaftPageStack) =
        focusAtEachFrameOf(
            prepare = { stack.open(COVERING_PAGE, JsonObject(emptyMap()), from = stack.pages.last()) },
            change = { stack.back() },
            restore = {},
        )

    @Test
    fun aComposerFocusedAsItsPageIsCoveredDoesNotCrash() =
        runRobolectricComposeUiTest {
            focusDuringPush(showChat { AppField(it) })
            assertTrue(focusGains > 0, "the field never took focus: the scan proved nothing")
        }

    @Test
    fun aComposerFocusedAsItsPageIsRevealedDoesNotCrash() =
        runRobolectricComposeUiTest {
            focusDuringPop(showChat { AppField(it) })
            assertTrue(focusGains > 0, "the field never took focus: the scan proved nothing")
        }

    /**
     * The framework's own behaviour, with no app code: foundation's text field in the same place.
     * When a Compose upgrade makes this stop throwing, [raftPageGatedCursorBrush] and this test can go.
     */
    @Test
    fun aPlainTextFieldStillThrowsTheFrameworkCrash() {
        var crash: Throwable? = null
        // After the crash the tree is broken: tearing the activity down throws a follow-up error.
        runCatching {
            runRobolectricComposeUiTest {
                val stack = showChat { PlainField(it) }
                crash = runCatching { focusDuringPush(stack) }.exceptionOrNull()
            }
        }
        assertTrue(focusGains > 0, "no field ever took focus: the scan cannot have shown a caret")
        val thrown = assertIs<IllegalStateException>(crash)
        assertEquals(
            "Cannot read CompositionLocal because the Modifier node is not currently attached.",
            thrown.message,
        )
        val frames = thrown.stackTrace.map { "${it.className}.${it.methodName}" }
        for (frame in listOf("TextFieldCoreModifierNode.startCursorJob", "LayoutNode.attach", "CopyNodesToNewAnchorLocation")) {
            assertTrue(frames.any { frame in it }, "$frame in $frames")
        }
    }

    /**
     * Per composition, whatever frame the test clock lands it on: a caret on a page first composed
     * active, none in the composition the page goes inactive, and back only frames after it is
     * active again.
     */
    @Test
    fun aFieldShowsItsCaretOnlyFramesAfterItsPageBecameActiveAndHidesItAtOnce() =
        runRobolectricComposeUiTest {
            var pageActive by mutableStateOf(true)
            val compositions = mutableListOf<Pair<Boolean, Boolean>>()
            setContent {
                RaftProvidePageActive(pageActive) {
                    compositions += pageActive to raftPageGatedCursorBrush(SolidColor(Color.Black)).showsCaret()
                }
            }
            waitForIdle()
            assertEquals(true to true, compositions.last(), "first composed on an active page: a caret at once")

            val beforeDeactivation = compositions.size
            runOnIdle { pageActive = false }
            waitForIdle()
            val inactive = compositions.drop(beforeDeactivation)
            assertTrue(inactive.isNotEmpty() && inactive.all { (active, _) -> !active }, "recomposed inactive: $compositions")
            assertTrue(inactive.none { (_, caret) -> caret }, "no caret in the composition the page goes inactive: $compositions")

            val beforeActivation = compositions.size
            runOnIdle { pageActive = true }
            waitForIdle()
            val active = compositions.drop(beforeActivation)
            assertEquals(true to false, active.first(), "still no caret in the composition the page becomes active: $compositions")
            assertEquals(true to true, active.last(), "a caret once the page has been active for a few frames: $compositions")
        }

    private fun Brush.showsCaret(): Boolean = this !is SolidColor || value.isSpecified

    private companion object {
        const val FOCUS_FRAMES = 8
        const val FRAMES_AFTER = 40
        const val CHAT = "compose_thread"
        const val COVERING_PAGE = "compose_artifact_viewer"
    }
}
