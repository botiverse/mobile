package build.raft.app.shared

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.ComposeView
import build.raft.app.R
import build.raft.app.RaftWindowFocusScope
import build.raft.app.RaftWindowInputFocusController
import build.raft.designsystem.focus.RaftFocusCoordinator
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Hands b0d1ed9f (#wg-mobile-feedback:#310): losing Android window focus to an external window
 * (such as a system floating window) clears stale Compose/View editor focus in Raft without ever
 * calling `hideSoftInputFromWindow`, while preserving focus when an in-app keyboard-retaining
 * modal (`keepsHostKeyboard = true`) owns window focus.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RaftWindowFocusLossInputClearTest {
    private val controllers = mutableListOf<ActivityController<out ComponentActivity>>()

    @After
    fun tearDownActivities() {
        val looper = shadowOf(Looper.getMainLooper())
        controllers.asReversed().forEach { controller ->
            controller.pause().stop().destroy()
            looper.idle()
        }
        controllers.clear()
        looper.idleFor(Duration.ofMillis(34))
    }

    @Test
    fun decisionMatrixClearsOnlyExternalWindowFocusLossWithFocusedTextInput() {
        assertFalse(
            shouldClearInputFocusOnWindowFocusLoss(
                hasWindowFocus = true,
                isFinishingOrDestroyed = false,
                retainsInputFocusForInAppOverlay = false,
                trackedTextInputFocused = true,
                composerFocused = true,
                otherHostTextFieldFocused = false,
                nativeTextEditorFocused = false,
            ).shouldClear,
        )
        assertFalse(
            shouldClearInputFocusOnWindowFocusLoss(
                hasWindowFocus = false,
                isFinishingOrDestroyed = true,
                retainsInputFocusForInAppOverlay = false,
                trackedTextInputFocused = true,
                composerFocused = true,
                otherHostTextFieldFocused = false,
                nativeTextEditorFocused = false,
            ).shouldClear,
        )
        val retained =
            shouldClearInputFocusOnWindowFocusLoss(
                hasWindowFocus = false,
                isFinishingOrDestroyed = false,
                retainsInputFocusForInAppOverlay = true,
                trackedTextInputFocused = true,
                composerFocused = true,
                otherHostTextFieldFocused = false,
                nativeTextEditorFocused = false,
            )
        assertFalse(retained.shouldClear)
        assertEquals("in_app_keyboard_retained_modal", retained.reason)

        val unfocused =
            shouldClearInputFocusOnWindowFocusLoss(
                hasWindowFocus = false,
                isFinishingOrDestroyed = false,
                retainsInputFocusForInAppOverlay = false,
                trackedTextInputFocused = false,
                composerFocused = false,
                otherHostTextFieldFocused = false,
                nativeTextEditorFocused = false,
            )
        assertFalse(unfocused.shouldClear)
        assertEquals("no_focused_text_input", unfocused.reason)

        assertTrue(
            shouldClearInputFocusOnWindowFocusLoss(
                hasWindowFocus = false,
                isFinishingOrDestroyed = false,
                retainsInputFocusForInAppOverlay = false,
                trackedTextInputFocused = true,
                composerFocused = false,
                otherHostTextFieldFocused = false,
                nativeTextEditorFocused = false,
            ).shouldClear,
        )
        assertTrue(
            shouldClearInputFocusOnWindowFocusLoss(
                hasWindowFocus = false,
                isFinishingOrDestroyed = false,
                retainsInputFocusForInAppOverlay = false,
                trackedTextInputFocused = false,
                composerFocused = true,
                otherHostTextFieldFocused = false,
                nativeTextEditorFocused = false,
            ).shouldClear,
        )
        assertTrue(
            shouldClearInputFocusOnWindowFocusLoss(
                hasWindowFocus = false,
                isFinishingOrDestroyed = false,
                retainsInputFocusForInAppOverlay = false,
                trackedTextInputFocused = false,
                composerFocused = false,
                otherHostTextFieldFocused = true,
                nativeTextEditorFocused = false,
            ).shouldClear,
        )
        assertTrue(
            shouldClearInputFocusOnWindowFocusLoss(
                hasWindowFocus = false,
                isFinishingOrDestroyed = false,
                retainsInputFocusForInAppOverlay = false,
                trackedTextInputFocused = false,
                composerFocused = false,
                otherHostTextFieldFocused = false,
                nativeTextEditorFocused = true,
            ).shouldClear,
        )
    }

    @Test
    fun externalWindowFocusLossClearsComposeEditorToRootSinkAndPreventsRefocusKeyboardFlash() {
        val controller = Robolectric.buildActivity(TestRaftWindowFocusHostActivity::class.java).setup()
        controllers += controller
        val activity = controller.get()
        activity.window.decorView.dispatchWindowFocusChanged(true)
        drainFrames()

        val rootSink = requireNotNull(activity.findViewById<View>(R.id.raft_root))
        assertTrue("composer should start focused in host", activity.editorFocused)
        assertTrue(activity.windowInputFocusController.hasFocusedTextInput)

        // Losing window focus to an external window (real AOSP order: DecorView -> Activity -> AndroidComposeView)
        // clears Raft's stale Compose + View focus onto the non-editor root focus sink.
        activity.window.decorView.dispatchWindowFocusChanged(false)
        drainFrames()

        assertFalse("composer Compose focus must clear on external window focus loss", activity.editorFocused)
        assertFalse(activity.windowInputFocusController.hasFocusedTextInput)
        assertSame(
            "root focus sink must absorb View focus instead of bouncing to ComposeView",
            rootSink,
            activity.window.decorView.findFocus(),
        )
        assertEquals(1, activity.logs.size)
        assertTrue(activity.logs.single().startsWith("window_focus_loss_clear_input_focus "))

        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        var showImeCallbacks = 0
        shadowOf(imm).setSoftInputVisibilityHandler { visible ->
            if (visible) showImeCallbacks++
        }

        // Path A: returning window focus leaves composer unfocused and must NOT trigger showSoftInput.
        activity.window.decorView.dispatchWindowFocusChanged(true)
        drainFrames()
        assertFalse("returning to window must leave composer unfocused (Path A)", activity.editorFocused)
        assertSame(rootSink, activity.window.decorView.findFocus())
        assertEquals("returning window focus must not flash the soft keyboard (Path A)", 0, showImeCallbacks)
        assertFalse(shadowOf(imm).isSoftInputVisible)

        // Losing focus again while no editor is focused must be a silent no-op and leave any other window's IME alone.
        var hideImeCallbacks = 0
        imm.showSoftInput(rootSink, 0)
        shadowOf(imm).setSoftInputVisibilityHandler { visible ->
            if (!visible) hideImeCallbacks++
        }
        activity.window.decorView.dispatchWindowFocusChanged(false)
        drainFrames()
        assertEquals(1, activity.logs.size)
        assertEquals("no-op focus loss must never call hideSoftInputFromWindow", 0, hideImeCallbacks)

        // Path B: returning window focus and tapping back into the composer focuses cleanly.
        activity.window.decorView.dispatchWindowFocusChanged(true)
        activity.focusEditor()
        drainFrames()
        assertTrue("tapping composer after return must focus cleanly (Path B)", activity.editorFocused)
        assertTrue(activity.windowInputFocusController.hasFocusedTextInput)
    }

    @Test
    fun retainedInAppKeyboardModalPreservesComposeFocusAcrossWindowFocusLoss() {
        val controller = Robolectric.buildActivity(TestRaftWindowFocusHostActivity::class.java).setup()
        controllers += controller
        val activity = controller.get()
        activity.window.decorView.dispatchWindowFocusChanged(true)
        drainFrames()

        assertTrue(activity.editorFocused)
        val menuRetentionToken = Any()
        activity.windowInputFocusController.retainInputFocusOnWindowFocusLoss(menuRetentionToken)

        // In-app Modal(keepsHostKeyboard = true) shifts Android window focus away from the host Activity.
        activity.window.decorView.dispatchWindowFocusChanged(false)
        drainFrames()

        assertTrue("in-app keyboard-retaining menu must keep composer focused", activity.editorFocused)
        assertTrue(activity.windowInputFocusController.hasFocusedTextInput)
        assertTrue("must not log clear when retention token is active", activity.logs.isEmpty())

        // Once the menu closes and releases retention, external window focus loss clears cleanly.
        activity.windowInputFocusController.releaseInputFocusRetention(menuRetentionToken)
        activity.window.decorView.dispatchWindowFocusChanged(true)
        drainFrames()
        assertTrue(activity.editorFocused)

        activity.window.decorView.dispatchWindowFocusChanged(false)
        drainFrames()
        assertFalse(activity.editorFocused)
        assertEquals(1, activity.logs.size)
    }

    @Test
    fun externalWindowFocusLossClearsNativeEditTextToRootSinkWithoutHidingIme() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controllers += controller
        val activity = controller.get()
        activity.setContentView(R.layout.activity_raft)
        val rootSink = requireNotNull(activity.findViewById<View>(R.id.raft_root))
        val container = requireNotNull(activity.findViewById<ViewGroup>(R.id.raft_container))
        val editText = EditText(activity)
        container.addView(editText)
        assertTrue(editText.requestFocus())
        assertSame(editText, activity.window.decorView.findFocus())

        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        var hideImeCallbacks = 0
        imm.showSoftInput(editText, 0)
        assertTrue(shadowOf(imm).isSoftInputVisible)
        shadowOf(imm).setSoftInputVisibilityHandler { visible ->
            if (!visible) hideImeCallbacks++
        }

        val logs = mutableListOf<String>()
        val result =
            clearAndroidHostInputFocusOnWindowFocusLoss(
                activity = activity,
                windowFocusCoordinator = RaftFocusCoordinator(),
                windowInputFocusController = RaftWindowInputFocusController(),
                pageName = "thread",
                log = logs::add,
            )

        assertTrue(result.cleared)
        assertFalse(editText.isFocused)
        assertSame(rootSink, activity.window.decorView.findFocus())
        assertEquals(0, hideImeCallbacks)
        assertTrue(shadowOf(imm).isSoftInputVisible)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("nativeEditFocused=true"))
    }

    private fun drainFrames() {
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(34)) }
    }

    class TestRaftWindowFocusHostActivity : ComponentActivity() {
        val windowFocusCoordinator = RaftFocusCoordinator()
        val windowInputFocusController = RaftWindowInputFocusController()
        val editorFocusRequester = FocusRequester()
        val logs = mutableListOf<String>()
        private val editorOwner = Any()
        var editorFocused: Boolean = false
            private set

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_raft)
            val container = findViewById<ViewGroup>(R.id.raft_container)
            val composeView =
                ComposeView(this).apply {
                    setContent {
                        RaftWindowFocusScope(
                            coordinator = windowFocusCoordinator,
                            inputFocusController = windowInputFocusController,
                        ) {
                            val state = rememberTextFieldState()
                            BasicTextField(
                                state = state,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .focusRequester(editorFocusRequester)
                                        .onFocusChanged { focusState ->
                                            editorFocused = focusState.isFocused
                                            windowInputFocusController.setTextInputFocused(editorOwner, focusState.isFocused)
                                        },
                            )
                            LaunchedEffect(Unit) {
                                editorFocusRequester.requestFocus()
                            }
                        }
                    }
                }
            container.addView(
                composeView,
                0,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }

        fun focusEditor() {
            editorFocusRequester.requestFocus()
        }

        override fun onWindowFocusChanged(hasFocus: Boolean) {
            super.onWindowFocusChanged(hasFocus)
            if (!hasFocus) {
                clearAndroidHostInputFocusOnWindowFocusLoss(
                    activity = this,
                    windowFocusCoordinator = windowFocusCoordinator,
                    windowInputFocusController = windowInputFocusController,
                    pageName = RaftCmpActivity.PAGE_THREAD,
                    log = logs::add,
                )
            }
        }
    }
}
