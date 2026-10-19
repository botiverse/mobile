package build.raft.app.shared

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Read-only view of a Compose text field (a SetText-capable semantics node) in a CMP page.
 *
 * CMP pages render their editors as Compose BasicTextFields inside SlockCmpActivity's ComposeView,
 * so connected tests that used to find an Android EditText read the field's semantics instead.
 * [id] is the semantics node id: it survives recomposition and changes only if the text field is
 * re-created, which makes it the counterpart of the old EditText instance identity.
 *
 * These helpers read the host's own SemanticsOwner on the main thread rather than going through a
 * Compose test rule, so the page keeps its production frame clock (a test rule swaps in a
 * test-driven recomposer that only advances when the test synchronizes with it, which would
 * freeze pixel and IME oracles that observe the real window).
 */
internal data class RaftTextFieldSnapshot(
    val id: Int,
    val text: String,
    val selection: TextRange?,
    val focused: Boolean,
    val boundsInWindow: Rect,
)

/** Text fields of the activity's Compose roots, in semantics tree order. */
internal fun ActivityScenario<SlockCmpActivity>.raftTextFields(): List<RaftTextFieldSnapshot> {
    var fields: List<RaftTextFieldSnapshot> = emptyList()
    onActivity { activity ->
        fields = activity.raftTextFieldNodes().map { node -> node.toRaftTextFieldSnapshot() }
    }
    return fields
}

/** The first text field of the page (the composer on composer pages), or null when none is attached. */
internal fun ActivityScenario<SlockCmpActivity>.raftFirstTextField(): RaftTextFieldSnapshot? =
    raftTextFields().firstOrNull()

/** Main-thread check that the page has attached a text field (for activity-level readiness probes). */
internal fun SlockCmpActivity.raftHasTextField(): Boolean = raftTextFieldNodes().isNotEmpty()

/** Requests focus for text field [id] through its semantics action, as accessibility would. */
internal fun ActivityScenario<SlockCmpActivity>.raftRequestTextFieldFocus(id: Int): Boolean =
    raftPerformOnTextField(id) { config ->
        config.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke() == true
    }

/** Places the caret of text field [id] at [start]..[end] through its semantics action. */
internal fun ActivityScenario<SlockCmpActivity>.raftSetTextFieldSelection(id: Int, start: Int, end: Int): Boolean =
    raftPerformOnTextField(id) { config ->
        config.getOrNull(SemanticsActions.SetSelection)?.action?.invoke(start, end, false) == true
    }

/** The IME connection of the focused view, i.e. what the soft keyboard talks to. */
internal fun SlockCmpActivity.raftFocusedInputConnection(): InputConnection? =
    currentFocus?.onCreateInputConnection(EditorInfo())

/**
 * Waits until the focused view has an active text input session, i.e. would hand the IME an
 * InputConnection. A Compose text field starts its input session asynchronously after it gains
 * focus, so "semantics says focused" is not yet "the IME can type".
 */
internal fun ActivityScenario<SlockCmpActivity>.raftAwaitTextInputSession(
    description: String,
    timeoutMs: Long = 12_000L,
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (SystemClock.uptimeMillis() < deadline) {
        instrumentation.waitForIdleSync()
        var active = false
        onActivity { activity -> active = activity.currentFocus?.onCheckIsTextEditor() == true }
        if (active) return
        SystemClock.sleep(50L)
    }
    throw AssertionError("Timed out waiting for a text input session on $description")
}

private fun ActivityScenario<SlockCmpActivity>.raftPerformOnTextField(
    id: Int,
    action: (SemanticsConfiguration) -> Boolean,
): Boolean {
    var performed = false
    onActivity { activity ->
        val node = activity.raftTextFieldNodes().firstOrNull { it.id == id }
        performed = node != null && action(node.config)
    }
    return performed
}

private fun SlockCmpActivity.raftTextFieldNodes(): List<SemanticsNode> =
    window.decorView.raftComposeRoots().flatMap { root ->
        root.semanticsOwner
            .getAllSemanticsNodes(mergingEnabled = false)
            .filter { node -> node.config.getOrNull(SemanticsActions.SetText) != null }
    }

private fun View.raftComposeRoots(): List<ViewRootForTest> {
    if (this is ViewRootForTest) return listOf(this)
    if (this !is ViewGroup) return emptyList()
    return (0 until childCount).flatMap { index -> getChildAt(index).raftComposeRoots() }
}

private fun SemanticsNode.toRaftTextFieldSnapshot(): RaftTextFieldSnapshot =
    RaftTextFieldSnapshot(
        id = id,
        text = config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty(),
        selection = config.getOrNull(SemanticsProperties.TextSelectionRange),
        focused = config.getOrNull(SemanticsProperties.Focused) == true,
        boundsInWindow = boundsInWindow,
    )
