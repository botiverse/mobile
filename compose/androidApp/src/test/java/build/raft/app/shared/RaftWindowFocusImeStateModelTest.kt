package build.raft.app.shared

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import build.raft.app.R
import build.raft.app.RaftWindowFocusScope
import build.raft.designsystem.focus.RaftFocusCoordinator
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Verifies the Android host side of the 3-axis IME state model
 * (`Window Focus` × `Editor Focus & Cursor` × `Explicit Keyboard-Show Intent`):
 *
 * - With `KeyboardOptions(showKeyboardOnFocus = false)` (the mobile default in `BasicWidget.TextField`),
 *   non-tap focus and window focus loss/regain (`isWindowFocused: true -> false -> true`) keep editor
 *   focus, draft text, and cursor intact while never starting a platform input session or calling
 *   `showSoftInput` / `hideSoftInputFromWindow`.
 * - Arming `showKeyboardOnFocus = true` starts the platform input session and opens the IME; disarming
 *   it back to `false` leaves the running input session alive (`onCreateInputConnection != null`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RaftWindowFocusImeStateModelTest {
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
    fun windowFocusLossAndRegainPreservesDraftCursorAndNeverStartsInputSessionWhenShowKeyboardOnFocusFalse() {
        val controller = Robolectric.buildActivity(TestImeStateModelHostActivity::class.java).setup()
        controllers += controller
        val activity = controller.get()
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        var showImeCallbacks = 0
        var hideImeCallbacks = 0
        shadowOf(imm).setSoftInputVisibilityHandler { visible ->
            if (visible) showImeCallbacks++ else hideImeCallbacks++
        }

        controller.windowFocusChanged(true)
        settle(activity)

        val composeRoot = findComposeRoot(activity.window.decorView) as? View
        assertNotNull("ComposeView root must be mounted", composeRoot)
        assertTrue("window should report focused in LocalWindowInfo", activity.windowFocused)
        assertTrue("composer should be focused", activity.editorFocused)
        assertEquals("hello world", activity.textState.text.toString())
        assertEquals(TextRange(5), activity.textState.selection)
        assertFalse(
            "showKeyboardOnFocus = false must not start an input session on non-tap focus",
            composeRoot!!.onCheckIsTextEditor(),
        )
        assertNull(composeRoot.onCreateInputConnection(EditorInfo()))
        assertEquals(0, showImeCallbacks)

        // External window focus loss (floating window or notification shade): never wipes focus,
        // draft text, or cursor, and never calls hideSoftInputFromWindow.
        controller.windowFocusChanged(false)
        settle(activity)
        assertFalse("window should report unfocused in LocalWindowInfo", activity.windowFocused)
        assertTrue("window focus loss must preserve editor focus", activity.editorFocused)
        assertEquals("hello world", activity.textState.text.toString())
        assertEquals(TextRange(5), activity.textState.selection)
        assertEquals(0, hideImeCallbacks)

        // Window focus regain + keyboardOptions recomposition (e.g. imeAction change on resume):
        // updateNode calls startInputSession(fromTap = false), which returns early with zero IME.
        controller.windowFocusChanged(true)
        activity.imeAction = ImeAction.Send
        settle(activity)
        assertTrue("window should report focused again in LocalWindowInfo", activity.windowFocused)
        assertTrue(activity.editorFocused)
        assertEquals("hello world", activity.textState.text.toString())
        assertEquals(TextRange(5), activity.textState.selection)
        assertFalse(composeRoot.onCheckIsTextEditor())
        assertNull(composeRoot.onCreateInputConnection(EditorInfo()))
        assertEquals("window focus regain must not flash soft keyboard", 0, showImeCallbacks)
    }

    @Test
    fun armingExplicitKeyboardShowIntentStartsInputSessionAndDisarmingKeepsSessionAlive() {
        val controller = Robolectric.buildActivity(TestImeStateModelHostActivity::class.java).setup()
        controllers += controller
        val activity = controller.get()
        controller.windowFocusChanged(true)
        settle(activity)

        val composeRoot = requireNotNull(findComposeRoot(activity.window.decorView) as? View)
        assertTrue(activity.windowFocused)
        assertTrue(activity.editorFocused)
        assertFalse(composeRoot.onCheckIsTextEditor())
        assertNull(composeRoot.onCreateInputConnection(EditorInfo()))

        // Explicit programmatic keyboard-show intent arms showKeyboardOnFocus = true on an
        // already-focused editor -> updateNode starts the platform text input session.
        activity.showKeyboardOnFocus = true
        settle(activity)
        assertTrue("armed showKeyboardOnFocus must start input session", composeRoot.onCheckIsTextEditor())
        assertNotNull(composeRoot.onCreateInputConnection(EditorInfo()))

        // Disarming showKeyboardOnFocus back to false must NOT dispose the active input session.
        activity.showKeyboardOnFocus = false
        settle(activity)
        assertTrue("disarming showKeyboardOnFocus must keep active session alive", composeRoot.onCheckIsTextEditor())
        assertNotNull(composeRoot.onCreateInputConnection(EditorInfo()))
        assertEquals("hello world", activity.textState.text.toString())
        assertEquals(TextRange(5), activity.textState.selection)
    }

    @Test
    fun externalWindowFocusLossParksActivePlatformSessionWithoutWipingFocusAndShowWakesIt() {
        val controller = Robolectric.buildActivity(TestImeStateModelHostActivity::class.java).setup()
        controllers += controller
        val activity = controller.get()
        controller.windowFocusChanged(true)
        settle(activity)

        val composeRoot = requireNotNull(findComposeRoot(activity.window.decorView) as? View)
        // Keep showKeyboardOnFocus = true (the production default) while active, across window
        // focus loss, window focus regain, and imeAction recomposition.
        activity.showKeyboardOnFocus = true
        settle(activity)
        assertTrue(composeRoot.onCheckIsTextEditor())
        assertNotNull(composeRoot.onCreateInputConnection(EditorInfo()))

        // External window focus loss (floating window) parks ONLY the platform input session
        // (onCheckIsTextEditor == false) while keeping editor focus, draft, and non-end cursor.
        controller.windowFocusChanged(false)
        settle(activity)
        assertFalse(activity.windowFocused)
        assertTrue("window focus loss must preserve editor focus", activity.editorFocused)
        assertEquals("hello world", activity.textState.text.toString())
        assertEquals(TextRange(5), activity.textState.selection)
        assertFalse(
            "external window focus loss must clear onCheckIsTextEditor for Android IMMS",
            composeRoot.onCheckIsTextEditor(),
        )
        assertNull(composeRoot.onCreateInputConnection(EditorInfo()))

        // Window focus regain + keyboardOptions recomposition (even with showKeyboardOnFocus == true)
        // must keep the session parked so Android IMMS never flashes the keyboard.
        controller.windowFocusChanged(true)
        activity.imeAction = ImeAction.Send
        settle(activity)
        assertTrue(activity.windowFocused)
        assertTrue(activity.editorFocused)
        assertEquals("hello world", activity.textState.text.toString())
        assertEquals(TextRange(5), activity.textState.selection)
        assertFalse(
            "imeAction recomposition after window focus regain must stay parked",
            composeRoot.onCheckIsTextEditor(),
        )
        assertNull(composeRoot.onCreateInputConnection(EditorInfo()))

        // Explicit SoftwareKeyboardController.show() (tap on composer) wakes & restarts the session.
        requireNotNull(activity.scopedKeyboardController).show()
        settle(activity)
        assertTrue(
            "SoftwareKeyboardController.show() must wake parked platform input session",
            composeRoot.onCheckIsTextEditor(),
        )
        val wokenConnection = composeRoot.onCreateInputConnection(EditorInfo())
        assertNotNull(wokenConnection)
        assertEquals("hello world", activity.textState.text.toString())
        assertEquals(TextRange(5), activity.textState.selection)
    }

    @Test
    fun activeSessionWithShowKeyboardOnFocusFalseSurvivesShowAndParkWakeUntilBlur() {
        val controller = Robolectric.buildActivity(TestImeStateModelHostActivity::class.java).setup()
        controllers += controller
        val activity = controller.get()
        controller.windowFocusChanged(true)
        settle(activity)

        val composeRoot = requireNotNull(findComposeRoot(activity.window.decorView) as? View)
        // Idle: focused with showKeyboardOnFocus = false starts zero platform sessions.
        assertTrue(activity.editorFocused)
        assertEquals(0, activity.platformSessionStarts)
        assertEquals(0, activity.platformSessionDisposals)
        assertFalse(composeRoot.onCheckIsTextEditor())

        // Establish a live session, then return showKeyboardOnFocus to false (same state as a
        // direct tap after focusWithoutKeyboard): session remains live with zero disposals.
        activity.showKeyboardOnFocus = true
        settle(activity)
        activity.showKeyboardOnFocus = false
        settle(activity)
        assertEquals(1, activity.platformSessionStarts)
        assertEquals(0, activity.platformSessionDisposals)
        assertTrue(composeRoot.onCheckIsTextEditor())
        assertNotNull(composeRoot.onCreateInputConnection(EditorInfo()))

        // Calling SoftwareKeyboardController.show() while active and showKeyboardOnFocus = false
        // must keep the exact same platform session running (no dispose or restart).
        requireNotNull(activity.scopedKeyboardController).show()
        settle(activity)
        assertEquals(1, activity.platformSessionStarts)
        assertEquals(0, activity.platformSessionDisposals)
        assertTrue(composeRoot.onCheckIsTextEditor())

        // External window focus loss parks, regain stays parked, and show() wakes the exact same
        // outer session with zero disposals.
        controller.windowFocusChanged(false)
        settle(activity)
        assertFalse(composeRoot.onCheckIsTextEditor())
        assertEquals(1, activity.platformSessionStarts)
        assertEquals(0, activity.platformSessionDisposals)

        controller.windowFocusChanged(true)
        settle(activity)
        assertFalse(composeRoot.onCheckIsTextEditor())

        requireNotNull(activity.scopedKeyboardController).show()
        settle(activity)
        assertTrue(composeRoot.onCheckIsTextEditor())
        assertNotNull(composeRoot.onCreateInputConnection(EditorInfo()))
        assertEquals(1, activity.platformSessionStarts)
        assertEquals(0, activity.platformSessionDisposals)

        // Moving focus off the editor disposes the active host session cleanly.
        activity.nonTextFocusRequester.requestFocus()
        settle(activity)
        assertFalse(activity.editorFocused)
        assertFalse(composeRoot.onCheckIsTextEditor())
        assertEquals(1, activity.platformSessionDisposals)
    }

    private fun settle(activity: ComponentActivity) {
        Snapshot.sendApplyNotifications()
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
        findComposeRoot(activity.window.decorView)?.measureAndLayoutForTest()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun findComposeRoot(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findComposeRoot(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    @OptIn(ExperimentalComposeUiApi::class)
    class TestImeStateModelHostActivity : ComponentActivity() {
        val windowFocusCoordinator = RaftFocusCoordinator()
        val editorFocusRequester = FocusRequester()
        val nonTextFocusRequester = FocusRequester()
        val textState = TextFieldState(initialText = "hello world", initialSelection = TextRange(5))
        var showKeyboardOnFocus by mutableStateOf(false)
        var imeAction by mutableStateOf(ImeAction.Default)
        var editorFocused: Boolean = false
            private set
        var windowFocused: Boolean = false
            private set
        var scopedKeyboardController: SoftwareKeyboardController? = null
            private set
        var platformSessionStarts: Int = 0
            private set
        var platformSessionDisposals: Int = 0
            private set

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_raft)
            val container = findViewById<ViewGroup>(R.id.raft_container)
            val composeView =
                ComposeView(this).apply {
                    setContent {
                        val isWindowFocused = LocalWindowInfo.current.isWindowFocused
                        SideEffect { windowFocused = isWindowFocused }
                        RaftWindowFocusScope(coordinator = windowFocusCoordinator) {
                            val keyboardController = LocalSoftwareKeyboardController.current
                            SideEffect { scopedKeyboardController = keyboardController }
                            val countingInterceptor =
                                remember {
                                    object : PlatformTextInputInterceptor {
                                        override suspend fun interceptStartInputMethod(
                                            request: PlatformTextInputMethodRequest,
                                            nextHandler: PlatformTextInputSession,
                                        ): Nothing {
                                            platformSessionStarts += 1
                                            try {
                                                nextHandler.startInputMethod(request)
                                            } finally {
                                                platformSessionDisposals += 1
                                            }
                                        }
                                    }
                                }
                            Column(Modifier.fillMaxWidth()) {
                                Box(
                                    Modifier
                                        .size(1.dp)
                                        .focusRequester(nonTextFocusRequester)
                                        .focusable(),
                                )
                                InterceptPlatformTextInput(interceptor = countingInterceptor) {
                                    BasicTextField(
                                        state = textState,
                                        keyboardOptions =
                                            KeyboardOptions(
                                                imeAction = imeAction,
                                                showKeyboardOnFocus = showKeyboardOnFocus,
                                            ),
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .focusRequester(editorFocusRequester)
                                                .onFocusChanged { focusState ->
                                                    editorFocused = focusState.isFocused
                                                },
                                    )
                                }
                            }
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
    }
}
