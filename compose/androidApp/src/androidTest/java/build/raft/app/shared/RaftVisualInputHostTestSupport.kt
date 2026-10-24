package build.raft.app.shared

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun ActivityScenario<RaftCmpActivity>.awaitVisualInputHostReadyForEditorFocus(
    description: String,
    timeoutMs: Long = 12_000L,
    inputReady: (RaftCmpActivity) -> Boolean,
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    var lastReadiness = VisualInputHostReadiness(windowFocused = false, inputReady = false)

    while (SystemClock.uptimeMillis() < deadline) {
        instrumentation.waitForIdleSync()
        lastReadiness = visualInputHostReadiness(inputReady)
        if (lastReadiness.ready) {
            // The first window-focus cleanup posts one more clearFocus(). Require another idle
            // boundary before the harness is allowed to focus the editor.
            instrumentation.waitForIdleSync()
            lastReadiness = visualInputHostReadiness(inputReady)
            if (lastReadiness.ready) return
        }
        SystemClock.sleep(50L)
    }

    throw AssertionError(
        "Timed out waiting for $description before editor focus: " +
            "windowFocused=${lastReadiness.windowFocused} inputReady=${lastReadiness.inputReady}"
    )
}

/**
 * Makes the test the only party editing through the focused editor's InputConnection.
 *
 * A Compose text field asks for the soft keyboard whenever it gains focus, so the real IME
 * (Gboard on the CI image) starts an input view on the same editor. A visible IME watches every
 * selection/composition update it did not cause and "repairs" it - finishing the composition or
 * re-composing the word at the cursor - which races the test's own InputConnection calls.
 *
 * Hiding is not finished when the window insets say the IME is hidden: right after focus the
 * show request may not have reached the IME yet, so the insets already read "hidden" while the
 * IME is still about to start its input view and then, on the hide, finish it. Finishing the
 * input view ends the IME's input session, and Gboard commits any composing text when it does
 * (`finishComposingText`). Arriving after the test's first `setComposingText`, that turned a
 * composing "n" into committed text, so the next `setComposingText("ni")` appended: "nni".
 *
 * So the hide goes through [InputMethodManager.hideSoftInputFromWindow] with a [ResultReceiver]:
 * the system forwards the hide to the IME after any pending show (both in request order), and
 * the IME answers only after it has finished its input view, so every edit the IME made while
 * closing has been sent to the editor before the answer. The answer is posted to the main thread
 * behind those edits, and the idle wait after it lets them land before the test types. Focus and
 * the editor's input session are untouched.
 */
internal fun ActivityScenario<RaftCmpActivity>.hideSoftKeyboardAndAwaitHidden(
    description: String,
    timeoutMs: Long = 12_000L,
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    val imeAnswered = CountDownLatch(1)
    var hideForwardedToIme = false
    onActivity { activity ->
        val imm = activity.getSystemService(InputMethodManager::class.java)
        hideForwardedToIme =
            imm.hideSoftInputFromWindow(
                activity.window.decorView.windowToken,
                0,
                object : ResultReceiver(Handler(Looper.getMainLooper())) {
                    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                        imeAnswered.countDown()
                    }
                },
            )
        // Keep the window's own requested IME visibility in step, so the insets controller does
        // not re-request the keyboard that the focus asked for.
        activity.window.insetsController?.hide(WindowInsets.Type.ime())
    }
    if (hideForwardedToIme && !imeAnswered.await(timeoutMs, TimeUnit.MILLISECONDS)) {
        throw AssertionError("Timed out waiting for the IME to finish its input view for $description")
    }
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    var imeVisible = true
    while (SystemClock.uptimeMillis() < deadline) {
        instrumentation.waitForIdleSync()
        onActivity { activity ->
            imeVisible = activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != false
        }
        if (!imeVisible) return
        SystemClock.sleep(50L)
    }
    throw AssertionError("Timed out waiting for the soft keyboard to hide for $description")
}

private fun ActivityScenario<RaftCmpActivity>.visualInputHostReadiness(
    inputReady: (RaftCmpActivity) -> Boolean,
): VisualInputHostReadiness {
    var readiness = VisualInputHostReadiness(windowFocused = false, inputReady = false)
    onActivity { activity ->
        readiness =
            VisualInputHostReadiness(
                windowFocused = activity.hasWindowFocus(),
                inputReady = inputReady(activity),
            )
    }
    return readiness
}

private data class VisualInputHostReadiness(
    val windowFocused: Boolean,
    val inputReady: Boolean,
) {
    val ready: Boolean
        get() = windowFocused && inputReady
}
