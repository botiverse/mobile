package build.raft.app.shared

import android.os.SystemClock
import android.view.WindowInsets
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry

internal fun ActivityScenario<SlockCmpActivity>.awaitVisualInputHostReadyForEditorFocus(
    description: String,
    timeoutMs: Long = 12_000L,
    inputReady: (SlockCmpActivity) -> Boolean,
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
 * re-composing the word at the cursor - which races the test's own InputConnection calls. Hiding
 * the keyboard through the same WindowInsetsController that requested it cancels a pending show
 * (requests are applied in order) and, once the IME reports hidden, the IME's input view is
 * finished, so it no longer reacts to the editor's updates. Focus and the editor's input session
 * are untouched.
 */
internal fun ActivityScenario<SlockCmpActivity>.hideSoftKeyboardAndAwaitHidden(
    description: String,
    timeoutMs: Long = 12_000L,
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    onActivity { activity -> activity.window.insetsController?.hide(WindowInsets.Type.ime()) }
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

private fun ActivityScenario<SlockCmpActivity>.visualInputHostReadiness(
    inputReady: (SlockCmpActivity) -> Boolean,
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
