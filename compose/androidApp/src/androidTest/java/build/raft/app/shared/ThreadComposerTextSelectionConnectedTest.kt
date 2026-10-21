package build.raft.app.shared

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Region
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Text selection in the production thread composer (the CMP BasicTextField on the composer
 * visual fixture), driven by real touch events injected into the window.
 *
 * The composer relies on androidx foundation's own selection gestures and handles (double tap
 * selects a word, triple tap a paragraph - foundation 1.10+; the floating toolbar's Select all).
 * Whatever selection a gesture produces, the field must show both the start and the end handle
 * and dragging either handle must move that edge of the selection instead of cancelling it.
 * Handles are popup windows; they are found through foundation's `SelectionHandleInfo`
 * semantics, which carries the handle kind and whether its icon is shown.
 *
 * The compose rule drives the page's recomposer, so every wait syncs through
 * `composeRule.waitForIdle()`; a bare `waitForIdleSync()` never lets the committed draft or
 * the gesture's selection recompose.
 */
class ThreadComposerTextSelectionConnectedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun doubleTapSelectsWordWithBothHandlesAndHandleDragsResizeIt() =
        withComposer("composer-selection-double-tap") { scenario ->
            tapWordCenter(scenario, "beta", taps = 2)
            awaitSelection(scenario, "double tap should select the word", TextRange(6, 10))
            awaitBothHandles("double tap", scenario)

            // End handle onto "gamma": the selection grows, it is not cancelled.
            dragHandle(scenario, HandleKind.End, toWord = "gamma")
            val grown = awaitSelectionMatching(scenario, "dragging the end handle should extend the selection") {
                it.min == 6 && it.max > 10
            }
            awaitBothHandles("after the end-handle drag (selection=$grown)", scenario)

            // Start handle onto "alpha": the selection grows to the left.
            dragHandle(scenario, HandleKind.Start, toWord = "alpha")
            val extended = awaitSelectionMatching(scenario, "dragging the start handle should extend the selection") {
                it.min < 6 && it.max == grown.max
            }
            awaitBothHandles("after the start-handle drag (selection=$extended)", scenario)
            captureScreen("double-tap-after-handle-drags")
        }

    @Test
    fun tripleTapSelectsParagraphWithBothHandlesAndEndHandleDragShrinksIt() =
        withComposer("composer-selection-triple-tap") { scenario ->
            tapWordCenter(scenario, "beta", taps = 3)
            // Paragraph selection covers the line up to (and including) its newline.
            val paragraph = awaitSelectionMatching(scenario, "triple tap should select the first paragraph") {
                it.min == 0 && (it.max == FirstParagraph.length || it.max == FirstParagraph.length + 1)
            }
            awaitBothHandles("triple tap", scenario)
            captureScreen("triple-tap")

            dragHandle(scenario, HandleKind.End, toWord = "beta")
            val shrunk = awaitSelectionMatching(scenario, "dragging the end handle should shrink the paragraph selection") {
                it.min == 0 && it.max in 1 until paragraph.max
            }
            awaitBothHandles("after shrinking the paragraph selection (selection=$shrunk)", scenario)
        }

    @Test
    fun toolbarSelectAllShowsBothHandlesAndEndHandleDragShrinksIt() =
        withComposer("composer-selection-select-all") { scenario ->
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            tapWordCenter(scenario, "beta", taps = 2)
            awaitSelection(scenario, "double tap should select the word", TextRange(6, 10))
            // A narrow screen moves Select all into the floating toolbar's overflow menu.
            val selectAll =
                device.wait(Until.findObject(By.text(SelectAllLabel)), ToolbarWaitMs)
                    ?: device.findObject(By.desc(ToolbarOverflowDescription))?.let { overflow ->
                        overflow.click()
                        device.wait(Until.findObject(By.text(SelectAllLabel)), ToolbarWaitMs)
                    }
            requireNotNull(selectAll) {
                "the selection toolbar (or its overflow) should offer '$SelectAllLabel'"
            }.click()
            awaitSelection(scenario, "Select all should select the whole draft", TextRange(0, ComposerText.length))
            awaitBothHandles("Select all", scenario)
            captureScreen("select-all")

            dragHandle(scenario, HandleKind.End, toWord = "epsilon")
            val shrunk = awaitSelectionMatching(scenario, "dragging the end handle should shrink the select-all range") {
                it.min == 0 && it.max in (FirstParagraph.length + 1) until ComposerText.length
            }
            awaitBothHandles("after shrinking select all (selection=$shrunk)", scenario)
        }

    private fun withComposer(instanceId: String, block: (ActivityScenario<RaftCmpActivity>) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent =
            Intent(instrumentation.targetContext, RaftCmpActivity::class.java)
                .putExtra("pageName", RaftPages.THREAD_COMPOSER_VISUAL)
                .putExtra(
                    "pageData",
                    JSONObject()
                        .put("instanceId", instanceId)
                        .put("visualFixture", "empty")
                        .toString()
                )
                .putExtra(RaftCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)
        ActivityScenario.launch<RaftCmpActivity>(intent).use { scenario ->
            // The fixture draws the composer edge to edge from the top of the window, so the draft's
            // first line sits under the status bar on tall-inset devices (CI: y=119 under a 145px
            // bar), and SystemUI, not the app, owns those touches. Hide the status bar for the
            // test; awaitAppWindowOwnsPoint confirms the app owns each injected point.
            scenario.onActivity { activity ->
                WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.statusBars())
                }
            }
            waitUntil({ "empty composer should attach" }) { scenario.raftFirstTextField()?.text == "" }
            scenario.awaitVisualInputHostReadyForEditorFocus("selection fixture composer") { activity ->
                activity.raftHasTextField()
            }
            val editor = requireNotNull(scenario.raftFirstTextField())
            assertTrue("composer should accept focus", scenario.raftRequestTextFieldFocus(editor.id))
            scenario.hideSoftKeyboardAndAwaitHidden("selection fixture composer")
            scenario.raftAwaitTextInputSession("selection fixture composer")
            scenario.onActivity { activity ->
                val inputConnection = requireNotNull(activity.raftFocusedInputConnection())
                assertTrue("IME should commit the draft", inputConnection.commitText(ComposerText, 1))
            }
            waitUntil({ "composer should hold the draft; fields=${scenario.raftTextFields()}" }) {
                scenario.raftFirstTextField()?.text == ComposerText
            }
            // Let the tap that follows start a fresh gesture (no pending double-tap window).
            SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout() + 200L)
            block(scenario)
        }
    }

    private fun tapWordCenter(scenario: ActivityScenario<RaftCmpActivity>, word: String, taps: Int) {
        val point = wordPointOnScreen(scenario, word)
        awaitAppWindowOwnsPoint(point, "tap on '$word'")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var downTime = SystemClock.uptimeMillis()
        repeat(taps) {
            instrumentation.sendPointerSync(
                MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, point.x, point.y, 0)
            )
            instrumentation.sendPointerSync(
                MotionEvent.obtain(downTime, downTime + TapDurationMs, MotionEvent.ACTION_UP, point.x, point.y, 0)
            )
            downTime += TapDurationMs + TapGapMs
            SystemClock.sleep(TapGapMs)
        }
        composeRule.waitForIdle()
    }

    /** Drags the [kind] handle horizontally/vertically onto the center of [toWord]. */
    private fun dragHandle(scenario: ActivityScenario<RaftCmpActivity>, kind: HandleKind, toWord: String) {
        val handle = handles().single { it.kind == kind && it.visible }
        val start = handleGrabPoint(handle)
        val target = wordPointOnScreen(scenario, toWord)
        // The handle hangs below its line; move it by the distance between its anchor line and the
        // target word so it keeps its offset to the text.
        val anchorLineCenter = handleLineCenterOnScreen(scenario, kind)
        val dx = target.x - anchorLineCenter.x
        val dy = target.y - anchorLineCenter.y
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, start.x, start.y, 0))
        for (step in 1..DragSteps) {
            val fraction = step / DragSteps.toFloat()
            instrumentation.sendPointerSync(
                MotionEvent.obtain(
                    downTime,
                    downTime + (DragDurationMs * fraction).toLong(),
                    MotionEvent.ACTION_MOVE,
                    start.x + dx * fraction,
                    start.y + dy * fraction,
                    0,
                )
            )
        }
        instrumentation.sendPointerSync(
            MotionEvent.obtain(downTime, downTime + DragDurationMs, MotionEvent.ACTION_UP, start.x + dx, start.y + dy, 0)
        )
        composeRule.waitForIdle()
    }

    /**
     * Waits until the topmost touchable window under [point] belongs to this app.
     *
     * `sendPointerSync` injects a targeted event: when another uid's window (IME, system bar,
     * a system dialog) is the touch target at that point, the platform rejects it with "Targeted
     * input event injection ... not directed at a window owned by uid". Window placement after
     * the keyboard hides or a popup closes has no completion callback the test can observe, so
     * this polls the accessibility window list (top layer first) under a short bounded deadline
     * and fails with the windows at the point instead of the platform's bare exception.
     */
    private fun awaitAppWindowOwnsPoint(point: Offset, description: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appPackage = instrumentation.targetContext.packageName
        val x = point.x.toInt()
        val y = point.y.toInt()
        val automation = instrumentation.uiAutomation
        // The window list is empty unless the UiAutomation service asks for interactive windows.
        automation.serviceInfo =
            automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        var last = "none"
        val deadline = SystemClock.uptimeMillis() + WindowOwnershipWaitMs
        while (SystemClock.uptimeMillis() < deadline) {
            composeRule.waitForIdle()
            val windows = automation.windows.sortedByDescending { it.layer }
            val atPoint =
                windows.filter { window ->
                    if (Build.VERSION.SDK_INT >= 33) {
                        Region().also(window::getRegionInScreen).contains(x, y)
                    } else {
                        android.graphics.Rect().also(window::getBoundsInScreen).contains(x, y)
                    }
                }
            val owner = atPoint.firstOrNull()
            if (owner != null && owner.root?.packageName?.toString() == appPackage) return
            last =
                "atPoint=" +
                    atPoint.joinToString { window -> window.summary() }.ifEmpty { "none" } +
                    " all=" + windows.joinToString { window -> window.summary() }.ifEmpty { "none" }
            SystemClock.sleep(PollMs)
        }
        captureScreen("window-at-point-${description.filter { it.isLetterOrDigit() }}")
        throw AssertionError(
            "$description: point ($x, $y) is not owned by an $appPackage window within " +
                "${WindowOwnershipWaitMs}ms; windows (top first): $last"
        )
    }

    private fun AccessibilityWindowInfo.summary(): String {
        val bounds = android.graphics.Rect().also(::getBoundsInScreen)
        return "[type=$type layer=$layer title=$title bounds=$bounds package=${root?.packageName}]"
    }

    private fun handleLineCenterOnScreen(scenario: ActivityScenario<RaftCmpActivity>, kind: HandleKind): Offset {
        val selection = requireNotNull(scenario.raftFirstTextField()?.selection)
        val offset = if (kind == HandleKind.Start) selection.min else selection.max
        val geometry = editorGeometry(scenario)
        val layout = geometry.layout
        val x = layout.getHorizontalPosition(offset, usePrimaryDirection = true)
        val line = layout.getLineForOffset(offset)
        val y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
        return geometry.toScreen(Offset(x, y))
    }

    private fun wordPointOnScreen(scenario: ActivityScenario<RaftCmpActivity>, word: String): Offset {
        val index = ComposerText.indexOf(word)
        require(index >= 0) { "'$word' is not in the draft" }
        val geometry = editorGeometry(scenario)
        val first = geometry.layout.getBoundingBox(index)
        val last = geometry.layout.getBoundingBox(index + word.length - 1)
        val box = Rect(first.left, first.top, last.right, last.bottom)
        return geometry.toScreen(box.center)
    }

    private class EditorGeometry(val layout: TextLayoutResult, val textOriginOnScreen: Offset) {
        fun toScreen(local: Offset): Offset = textOriginOnScreen + local
    }

    private fun editorGeometry(scenario: ActivityScenario<RaftCmpActivity>): EditorGeometry {
        val node = composeRule.onNode(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        val gotLayout = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) == true
        assertTrue("composer should expose its text layout", gotLayout && layouts.isNotEmpty())
        val layout = layouts.first()
        // The composer's decoration box centers the inner text vertically (CenterStart).
        val verticalInset = ((node.size.height - layout.size.height) / 2f).coerceAtLeast(0f)
        val origin = node.positionOnScreen + Offset(0f, verticalInset)
        check(scenario.raftFirstTextField() != null)
        return EditorGeometry(layout, origin)
    }

    private enum class HandleKind { Start, End, Cursor }

    private data class HandleSnapshot(
        val kind: HandleKind,
        val visible: Boolean,
        val touchPointOnScreen: Offset,
        val boundsOnScreen: Rect,
    )

    /**
     * A point on [handle] where the handle's own popup window is the topmost window, so the drag
     * grabs the handle.
     *
     * With TextFieldState (CMP 1.12 / androidx foundation 1.12) the floating text toolbar is
     * anchored to the selected line only; the value-based field added the handle height
     * (TextFieldSelectionManager.getContentRect: `+ 25.dp`). This fixture draws the composer at
     * the very top of the window, so there is no room for the toolbar above the line: Android
     * places it just below, over the lower part of the handles, and a touch on the handle's
     * centre lands on Cut / Copy instead (Cut deleted the selected word in #3439's CI run).
     * Scan the handle top-down at its centre line for the first point owned by the handle window;
     * fail with the windows found when the handle is fully covered.
     */
    private fun handleGrabPoint(handle: HandleSnapshot): Offset {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo =
            automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        val x = handle.touchPointOnScreen.x
        var last = "none"
        val deadline = SystemClock.uptimeMillis() + WindowOwnershipWaitMs
        while (SystemClock.uptimeMillis() < deadline) {
            composeRule.waitForIdle()
            val windows = automation.windows.sortedByDescending { it.layer }
            fun boundsOf(window: AccessibilityWindowInfo) = android.graphics.Rect().also(window::getBoundsInScreen)
            // The handle's popup: the smallest window holding the handle node's centre (the popup
            // bounds may differ from the node's by a pixel of rounding).
            val handleWindow =
                windows
                    .filter { window ->
                        boundsOf(window).contains(handle.touchPointOnScreen.x.toInt(), handle.touchPointOnScreen.y.toInt())
                    }
                    .minByOrNull { window -> boundsOf(window).let { it.width().toLong() * it.height() } }
            if (handleWindow != null) {
                var y = handle.boundsOnScreen.top + 1f
                while (y < handle.boundsOnScreen.bottom) {
                    val top = windows.firstOrNull { window -> boundsOf(window).contains(x.toInt(), y.toInt()) }
                    if (top != null && top.id == handleWindow.id) return Offset(x, y)
                    y += HandleScanStepPx
                }
            }
            last =
                "handle=${handle.boundsOnScreen} handleWindow=${handleWindow?.summary()} " +
                    "windows=" + windows.joinToString { window -> window.summary() }
            SystemClock.sleep(PollMs)
        }
        throw AssertionError("the ${handle.kind} handle is covered by another window at every point; $last")
    }

    private fun handles(): List<HandleSnapshot> =
        composeRule
            .onAllNodes(SemanticsMatcher("is a selection handle") { it.selectionHandleInfo() != null }, useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .mapNotNull { node ->
                // Foundation's internal SelectionHandleInfo(handle, position, anchor, visible).
                val info = node.selectionHandleInfo().toString()
                val kind =
                    when {
                        "handle=SelectionStart" in info -> HandleKind.Start
                        "handle=SelectionEnd" in info -> HandleKind.End
                        "handle=Cursor" in info -> HandleKind.Cursor
                        else -> return@mapNotNull null
                    }
                val visible = !info.contains("visible=false")
                val center = node.positionOnScreen + Offset(node.size.width / 2f, node.size.height / 2f)
                HandleSnapshot(kind, visible, center, Rect(node.positionOnScreen, node.size.toSize()))
            }

    private fun SemanticsNode.selectionHandleInfo(): Any? =
        config.firstOrNull { it.key.name == "SelectionHandleInfo" }?.value

    private fun awaitBothHandles(description: String, scenario: ActivityScenario<RaftCmpActivity>) {
        var last: List<HandleSnapshot> = emptyList()
        val deadline = SystemClock.uptimeMillis() + HandleWaitMs
        while (SystemClock.uptimeMillis() < deadline) {
            composeRule.waitForIdle()
            last = handles()
            if (last.any { it.kind == HandleKind.Start && it.visible } && last.any { it.kind == HandleKind.End && it.visible }) {
                return
            }
            SystemClock.sleep(PollMs)
        }
        captureScreen("missing-handles-${description.filter { it.isLetterOrDigit() }}")
        throw AssertionError(
            "$description: both selection handles should be shown; handles=$last " +
                "selection=${scenario.raftFirstTextField()?.selection}"
        )
    }

    private fun awaitSelection(scenario: ActivityScenario<RaftCmpActivity>, message: String, expected: TextRange) {
        val actual = awaitSelectionMatching(scenario, message) { it == expected }
        assertEquals(message, expected, actual)
    }

    private fun awaitSelectionMatching(
        scenario: ActivityScenario<RaftCmpActivity>,
        message: String,
        predicate: (TextRange) -> Boolean,
    ): TextRange {
        var last: TextRange? = null
        val deadline = SystemClock.uptimeMillis() + SelectionWaitMs
        while (SystemClock.uptimeMillis() < deadline) {
            composeRule.waitForIdle()
            last = scenario.raftFirstTextField()?.selection
            if (last != null && predicate(last)) return last
            SystemClock.sleep(PollMs)
        }
        captureScreen("selection-${message.filter { it.isLetterOrDigit() }.take(40)}")
        throw AssertionError("$message; selection=$last text=${scenario.raftFirstTextField()?.text}")
    }

    private fun waitUntil(message: () -> String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + SelectionWaitMs
        while (SystemClock.uptimeMillis() < deadline) {
            composeRule.waitForIdle()
            if (condition()) return
            SystemClock.sleep(PollMs)
        }
        throw AssertionError(message())
    }

    /** Screen capture (handles are separate popup windows) kept as evidence on the device. */
    private fun captureScreen(name: String) {
        val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = instrumentation.targetContext.getExternalFilesDir("composer-selection") ?: return
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val FirstParagraph = "alpha beta gamma"
        const val ComposerText = "$FirstParagraph\ndelta epsilon zeta"
        const val SelectAllLabel = "Select all"
        const val ToolbarOverflowDescription = "More options"
        const val HandleScanStepPx = 4f
        const val TapDurationMs = 40L
        const val TapGapMs = 60L
        const val DragSteps = 16
        const val DragDurationMs = 480L
        const val ToolbarWaitMs = 5_000L
        const val HandleWaitMs = 5_000L
        const val SelectionWaitMs = 8_000L
        const val WindowOwnershipWaitMs = 5_000L
        const val PollMs = 100L
    }
}
