package build.raft.app.shared

import android.content.Intent
import android.text.InputType
import android.text.Selection
import android.text.Spannable
import android.text.Spanned
import android.text.SpanWatcher
import android.text.method.ArrowKeyMovementMethod
import android.text.method.MetaKeyKeyListener
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.test.filters.SdkSuppress
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.kuikly.core.render.android.expand.component.KRTextProps
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SlockMultilineTextFieldValueConnectedTest {
    @Test
    @SdkSuppress(minSdkVersion = 34, maxSdkVersion = 34)
    fun android14MissingTouchAnchorCrashesEvenWithValidCurrentSelection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val editor = selectionTestEditor(multiline = false, value = "hello")
            editor.movementMethod = ArrowKeyMovementMethod.getInstance()
            editor.setSelection(2)
            editor.dispatchSelectionTouch(MotionEvent.ACTION_DOWN)
            pressSelectionShift(editor)
            assertEquals(2, editor.selectionStart)
            assertEquals(2, editor.selectionEnd)
            val failure = runCatching { editor.dispatchSelectionTouch(MotionEvent.ACTION_UP) }.exceptionOrNull()
            assertTrue("raw Android movement must reproduce the reported failure: $failure",
                failure is IndexOutOfBoundsException)
            assertEquals("setSpan (-1 ... -1) starts before 0", failure?.message)
            assertTrue(failure!!.stackTrace.any {
                it.className == "android.text.method.ArrowKeyMovementMethod" && it.methodName == "onTouchEvent"
            })
        }
    }

    @Test
    fun missingTouchAnchorPreservesDraftSelectionAndNextComposition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (multiline in listOf(false, true)) {
                for (value in listOf("", "hello")) {
                    val editor = selectionTestEditor(multiline, value)
                    assertTrue(editor.movementMethod === SlockArrowKeyMovementMethod)
                    val cursor = value.length / 2
                    editor.setSelection(cursor)
                    val connection = BaseInputConnection(editor, true)
                    if (value.isNotEmpty()) assertTrue(connection.setComposingRegion(0, 1))
                    val composingStart = BaseInputConnection.getComposingSpanStart(editor.text)
                    val composingEnd = BaseInputConnection.getComposingSpanEnd(editor.text)
                    editor.dispatchSelectionTouch(MotionEvent.ACTION_DOWN)
                    // No selecting DOWN was sent. A modifier arriving mid-gesture makes
                    // Android read a missing anchor on UP, even though the cursor is valid.
                    pressSelectionShift(editor)
                    assertTrue(editor.dispatchSelectionTouch(MotionEvent.ACTION_UP))
                    assertEquals(value, editor.text.toString())
                    assertEquals(cursor, editor.selectionStart)
                    assertEquals(cursor, editor.selectionEnd)
                    assertTrue(editor.isFocused)
                    assertEquals(0, MetaKeyKeyListener.getMetaState(editor.text, KeyEvent.META_SHIFT_ON))
                    assertEquals(composingStart, BaseInputConnection.getComposingSpanStart(editor.text))
                    assertEquals(composingEnd, BaseInputConnection.getComposingSpanEnd(editor.text))
                    assertTrue(connection.finishComposingText())
                    assertTrue(connection.setComposingText("中", 1))
                    assertEquals(value.take(cursor) + "中" + value.drop(cursor), editor.text.toString())
                    assertEquals(cursor, BaseInputConnection.getComposingSpanStart(editor.text))
                    assertEquals(cursor + 1, BaseInputConnection.getComposingSpanEnd(editor.text))
                }
            }
        }
    }

    @Test
    fun negativeSpanFailureInsideSelectionCallbackStillPropagates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val editor = selectionTestEditor(multiline = false, value = "hello world")
            editor.setSelection(editor.length())
            pressSelectionShift(editor)
            editor.dispatchSelectionTouch(MotionEvent.ACTION_DOWN, 2f)
            var callbackFailure: IndexOutOfBoundsException? = null
            val watcher = object : SpanWatcher {
                override fun onSpanAdded(text: Spannable, what: Any, start: Int, end: Int) = Unit
                override fun onSpanRemoved(text: Spannable, what: Any, start: Int, end: Int) = Unit
                override fun onSpanChanged(
                    text: Spannable, what: Any, oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int
                ) {
                    if (what !== Selection.SELECTION_START || callbackFailure != null) return
                    try {
                        text.setSpan(Any(), -1, -1, Spanned.SPAN_POINT_POINT)
                    } catch (error: IndexOutOfBoundsException) {
                        callbackFailure = error
                        throw error
                    }
                }
            }
            editor.text.setSpan(watcher, 0, editor.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            val failure = try {
                runCatching { editor.dispatchSelectionTouch(MotionEvent.ACTION_UP, 90f) }.exceptionOrNull()
            } finally {
                editor.text.removeSpan(watcher)
            }
            assertNotNull("the valid native selection must invoke the application watcher", callbackFailure)
            assertEquals("setSpan (-1 ... -1) starts before 0", callbackFailure?.message)
            assertSame("an application callback failure must not be treated as the missing anchor", callbackFailure, failure)
        }
    }

    @Test
    fun ordinaryShiftTouchSelectionAndSubsequentTapKeepNativeBehavior() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (multiline in listOf(false, true)) {
                val editor = selectionTestEditor(multiline, "hello world")
                pressSelectionShift(editor)
                editor.dispatchSelectionTouch(MotionEvent.ACTION_DOWN, 2f)
                val expectedStart = editor.getOffsetForPosition(2f, 10f)
                val expectedEnd = editor.getOffsetForPosition(90f, 10f)
                assertTrue("fixture must span distinct characters", expectedStart < expectedEnd)
                editor.dispatchSelectionTouch(MotionEvent.ACTION_UP, 90f)
                assertEquals(expectedStart, editor.selectionStart)
                assertEquals(expectedEnd, editor.selectionEnd)
                MetaKeyKeyListener.resetMetaState(editor.text)
                // Exercise TextView's normal dispatch too; no custom touch replay is involved.
                val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 30f, 10f, 0)
                val up = MotionEvent.obtain(0L, 1L, MotionEvent.ACTION_UP, 30f, 10f, 0)
                try {
                    editor.dispatchTouchEvent(down)
                    editor.dispatchTouchEvent(up)
                } finally {
                    down.recycle()
                    up.recycle()
                }
                assertEquals("hello world", editor.text.toString())
                assertTrue(editor.selectionStart in 0..editor.length())
                assertEquals(editor.selectionStart, editor.selectionEnd)
            }
        }
    }

    private fun selectionTestEditor(multiline: Boolean, value: String): SlockKuiklyTextFieldView {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val editor = if (multiline) SlockKuiklyTextAreaView(context, null)
            else SlockKuiklyTextFieldView(context, null)
        val parent = FrameLayout(context)
        parent.addView(editor, FrameLayout.LayoutParams(400, 120))
        editor.setPadding(0, 0, 0, 0)
        editor.textSize = 20f
        editor.setText(value)
        parent.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, 400, 120)
        assertTrue(editor.requestFocus())
        assertNotNull(editor.layout)
        return editor
    }

    private fun pressSelectionShift(editor: EditText) {
        val listener = object : MetaKeyKeyListener() {}
        listener.onKeyDown(editor, editor.text, KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT))
        assertEquals(1, MetaKeyKeyListener.getMetaState(editor.text, KeyEvent.META_SHIFT_ON))
    }

    private fun EditText.dispatchSelectionTouch(action: Int, x: Float = 10f): Boolean {
        val event = MotionEvent.obtain(0L, 1L, action, x, 10f, 0)
        return try { movementMethod.onTouchEvent(this, text, event) } finally { event.recycle() }
    }

    @Test
    fun returnKeyTypeChangesPreserveLiveSelectionWithoutTransientZeroCallback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            listOf("@", "#").forEach { trigger ->
                val editor = SlockKuiklyTextAreaView(instrumentation.targetContext, null)

                // Composer uses maxLines=4. CoreTextField applies that line count before it
                // registers editing callbacks, but it does not emit returnKeyTypeNone for a
                // multiline field. The first return-key prop arrives only when cold @/#
                // suggestions become visible, so there must be no synthetic initial "none" here.
                assertTrue(editor.setProp(KRTextProps.PROP_KEY_NUMBER_OF_LINES, 4))

                val textInputStateEvents = mutableListOf<Any?>()
                val legacyTextEvents = mutableListOf<Any?>()
                val selectionEvents = mutableListOf<Any?>()
                val textInputStateCallback: KuiklyRenderCallback = { result ->
                    textInputStateEvents += result
                }
                val legacyTextCallback: KuiklyRenderCallback = { result ->
                    legacyTextEvents += result
                }
                val selectionCallback: KuiklyRenderCallback = { result ->
                    selectionEvents += result
                }
                assertTrue(editor.setProp("textInputStateChange", textInputStateCallback))
                assertTrue(editor.setProp("textDidChange", legacyTextCallback))
                assertTrue(editor.setProp("selectionChange", selectionCallback))

                editor.setText(trigger)
                editor.setSelection(editor.length())
                textInputStateEvents.clear()
                legacyTextEvents.clear()
                selectionEvents.clear()

                assertTrue(editor.setProp("returnKeyType", "send"))

                assertEquals(trigger, editor.text.toString())
                assertEquals(editor.length(), editor.selectionStart)
                assertEquals(editor.length(), editor.selectionEnd)
                assertNoEditingCallbacks(
                    "$trigger cold None -> Send",
                    textInputStateEvents,
                    legacyTextEvents,
                    selectionEvents
                )
                assertEquals(
                    EditorInfo.IME_ACTION_SEND,
                    editor.imeOptions and EditorInfo.IME_MASK_ACTION
                )

                val multilineText = "$trigger draft\nnext"
                editor.setText(multilineText)
                editor.setSelection(2, 5)
                textInputStateEvents.clear()
                legacyTextEvents.clear()
                selectionEvents.clear()

                assertTrue(editor.setProp("returnKeyType", "none"))

                assertEquals(multilineText, editor.text.toString())
                assertEquals(2, editor.selectionStart)
                assertEquals(5, editor.selectionEnd)
                assertNoEditingCallbacks(
                    "$trigger Send -> None with ranged selection",
                    textInputStateEvents,
                    legacyTextEvents,
                    selectionEvents
                )
                assertEquals(
                    EditorInfo.IME_ACTION_NONE,
                    editor.imeOptions and EditorInfo.IME_MASK_ACTION
                )
            }
        }
    }

    @Test
    fun attachmentCommentRetainsCjkCompositionAndMidTextEditsAcrossRecomposition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", "compose_thread_comment_anchor_visual")
                .putExtra(
                    "pageData",
                    JSONObject()
                        .put("instanceId", "attachment-comment-exact-value-test")
                        .toString()
                )
                .putExtra(SlockKuiklyActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
            scenario.waitForEditorSnapshot("", 0, 0, -1, -1)

            scenario.awaitVisualInputHostReadyForEditorFocus(
                "attachment-comment fixture editor"
            ) { activity -> activity.window.decorView.findOnlyEditText() != null }
            scenario.onActivity { activity ->
                val editor = activity.window.decorView.requireOnlyEditText()
                assertTrue("attachment-comment editor should accept focus", editor.requestFocus())
                assertTrue(editor.requireInputConnection().setComposingText("n", 1))
            }
            scenario.waitForEditorSnapshot("n", 1, 1, 0, 1)

            scenario.onActivity { activity ->
                val editor = activity.window.decorView.requireOnlyEditText()
                assertTrue(editor.requireInputConnection().setComposingText("ni", 1))
            }
            scenario.waitForEditorSnapshot("ni", 2, 2, 0, 2)

            val committed = "你好\nreview"
            scenario.onActivity { activity ->
                val editor = activity.window.decorView.requireOnlyEditText()
                assertTrue(editor.requireInputConnection().commitText(committed, 1))
            }
            scenario.waitForEditorSnapshot(committed, committed.length, committed.length, -1, -1)

            val inserted = "你X好\nreview"
            scenario.onActivity { activity ->
                val editor = activity.window.decorView.requireOnlyEditText()
                editor.setSelection(1)
                assertTrue(editor.requireInputConnection().commitText("X", 1))
            }
            scenario.waitForEditorSnapshot(inserted, 2, 2, -1, -1)

            scenario.onActivity { activity ->
                val editor = activity.window.decorView.requireOnlyEditText()
                assertTrue(editor.requireInputConnection().deleteSurroundingText(1, 0))
            }
            scenario.waitForEditorSnapshot(committed, 1, 1, -1, -1)

            val retyped = "你再好\nreview"
            scenario.onActivity { activity ->
                val editor = activity.window.decorView.requireOnlyEditText()
                assertTrue(editor.requireInputConnection().setComposingText("再", 1))
            }
            scenario.waitForEditorSnapshot(retyped, 2, 2, 1, 2)

            scenario.onActivity { activity ->
                val editor = activity.window.decorView.requireOnlyEditText()
                assertTrue(editor.requireInputConnection().commitText("再", 1))
            }
            scenario.waitForEditorSnapshot(retyped, 2, 2, -1, -1)
        }
    }

    @Test
    fun returnKeySendPreferenceDecidesLineModeInEveryPropArrivalOrder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            // The line mode must be a function of the preference, not of prop arrival order.
            // Measured device order is numberOfLines -> systemNewlineAction -> imeActionRequested,
            // the reverse of the modifier chain, and numberOfLines is what triggers the structural
            // pass — so the preference always lands after the first pass has run on the default.
            //
            // An earlier version of this test fed one assumed order and therefore proved the
            // assumption rather than the behaviour: it stayed green while the device rendered Done.
            // Permuting every order is the direct correction — a single order can only show that
            // one path works, whereas agreement across all of them shows order does not matter.
            listOf(false, true).forEach { sendsOnReturn ->
                val expectedMultiLine = !sendsOnReturn
                permutationsOfThree().forEach { order ->
                    val editor = SlockKuiklyTextAreaView(instrumentation.targetContext, null)
                    order.forEach { prop ->
                        val applied = when (prop) {
                            PropUnderTest.NUMBER_OF_LINES ->
                                editor.setProp(KRTextProps.PROP_KEY_NUMBER_OF_LINES, 4)
                            PropUnderTest.SYSTEM_NEWLINE_ACTION ->
                                editor.setProp(SLOCK_SYSTEM_NEWLINE_ACTION_PROP, 1)
                            PropUnderTest.IME_ACTION_REQUESTED ->
                                editor.setProp(
                                    SLOCK_IME_ACTION_REQUESTED_PROP,
                                    if (sendsOnReturn) 1 else 0
                                )
                        }
                        assertTrue("setProp $prop should be handled", applied)
                    }
                    // Assert on what the IME is actually handed, not on the view field. HuaTuo
                    // measures EditorInfo via dumpsys, so reading the same struct keeps CI and
                    // device on one criterion instead of two that can drift apart.
                    val outAttrs = EditorInfo()
                    editor.onCreateInputConnection(outAttrs)

                    val multiLine = outAttrs.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
                    assertEquals(
                        "sendsOnReturn=$sendsOnReturn order=$order must converge on the same line mode",
                        expectedMultiLine,
                        multiLine
                    )

                    // 1.9 carried IME_FLAG_NO_ENTER_ACTION alongside the multi-line bit, and both
                    // were needed: the first lets the field hold a newline, the second stops the
                    // IME claiming Enter as an action key. Restoring only the first can leave a
                    // keyboard that shows Enter but does not insert one.
                    val noEnterAction =
                        outAttrs.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
                    assertEquals(
                        "sendsOnReturn=$sendsOnReturn order=$order must match on IME_FLAG_NO_ENTER_ACTION",
                        expectedMultiLine,
                        noEnterAction
                    )
                }
            }

            // Callers that never send the preference keep the previous behaviour, so migrating one
            // screen cannot silently change every other text area.
            val legacyEditor = SlockKuiklyTextAreaView(instrumentation.targetContext, null)
            assertTrue(legacyEditor.setProp(KRTextProps.PROP_KEY_NUMBER_OF_LINES, 4))
            val legacyAttrs = EditorInfo()
            legacyEditor.onCreateInputConnection(legacyAttrs)
            assertEquals(
                "an unmigrated caller must keep the single-line action-key behaviour",
                0,
                legacyAttrs.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE
            )
        }
    }

    @Test
    fun newlineModeKeepsBothBitsWhenASuggestionPanelRaisesTheImeAction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            // With "return key sends" off, an open @/# panel still drives imeAction to Send in the
            // shared layer, which pushes an explicit action down here. Android normally adds
            // IME_FLAG_NO_ENTER_ACTION only when a multi-line field has no explicit action, so
            // this is the path where the field could stay multi-line while the IME still claims
            // Enter — the "key looks right, pressing it does nothing" shape.
            //
            // artin's contract says newline mode inserts a newline even with the panel open, so
            // both bits must survive. Proving it here beats spending a device round on it.
            val editor = SlockKuiklyTextAreaView(instrumentation.targetContext, null)
            assertTrue(editor.setProp(KRTextProps.PROP_KEY_NUMBER_OF_LINES, 4))
            assertTrue(editor.setProp(SLOCK_SYSTEM_NEWLINE_ACTION_PROP, 1))
            assertTrue(editor.setProp(SLOCK_IME_ACTION_REQUESTED_PROP, 0))
            assertTrue(editor.setProp("returnKeyType", "send"))

            val outAttrs = EditorInfo()
            editor.onCreateInputConnection(outAttrs)
            assertTrue(
                "newline mode must stay multi-line even while a suggestion panel raises the action",
                outAttrs.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
            )
            // This flag is supplied by the framework, not by us — nothing in this module writes
            // imeOptions flags. So the assertion records that the platform currently adds it on
            // this path; it is not a guarantee we control. If an upstream or OEM change stops
            // adding it, newline mode breaks silently and this test is what would catch it. The
            // structurally safe alternative is 1.9's: never raise Send in newline mode, so the
            // flag is never needed. Worth revisiting if this ever goes red.
            assertTrue(
                "newline mode must keep IME_FLAG_NO_ENTER_ACTION so Enter is not claimed as an action",
                outAttrs.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
            )

            // Records which of two accounts of this behaviour is true, because they differ in
            // residual risk even though they agree on the outcome. If the Send action is gone,
            // dismissing the panel withdrew it. If it is still here, nothing withdrew it and the
            // newline only survives because the framework suppresses it — which is a dependency
            // on platform behaviour, not a property we hold.
            //
            // Kuikly re-sends returnKeyType only for a single-line field, so on a multi-line
            // editor no "none" ever arrives to undo the Send. Asserting the stale action documents
            // that, and turns red if that ever changes.
            //
            // This assertion pins current behaviour, not a requirement. If newline mode is ever
            // changed to stop raising Send at all — 1.9's structure, the fix for the framework
            // dependency described above — there will be no Send action here and this goes red.
            // That red is the expected consequence of that change, not a regression it caught:
            // update this assertion rather than reverting the change.
            assertEquals(
                "the Send action is not withdrawn when the panel closes; the newline survives only " +
                    "because NO_ENTER_ACTION suppresses it",
                EditorInfo.IME_ACTION_SEND,
                outAttrs.imeOptions and EditorInfo.IME_MASK_ACTION
            )
        }
    }

    @Test
    fun returnKeyModeStopsFollowingThePreferenceOnceTheEditorIsConnected() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val editor = SlockKuiklyTextAreaView(instrumentation.targetContext, null)
            assertTrue(editor.setProp(KRTextProps.PROP_KEY_NUMBER_OF_LINES, 4))
            assertTrue(editor.setProp(SLOCK_SYSTEM_NEWLINE_ACTION_PROP, 1))
            assertTrue(editor.setProp(SLOCK_IME_ACTION_REQUESTED_PROP, 1))
            assertEquals(
                "send mode should hold before the editor is connected",
                0,
                editor.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE
            )

            // Connecting to the IME closes the window: flipping isSingleLine from here on resets
            // the editor structure and publishes a transient same-text 0..0 selection, which is
            // the cursor-jump this guard exists to prevent. A late preference must be ignored
            // rather than applied, so the reconfiguration stays bounded to setup.
            assertNotNull(editor.onCreateInputConnection(EditorInfo()))
            assertTrue(editor.setProp(SLOCK_IME_ACTION_REQUESTED_PROP, 0))
            assertEquals(
                "a preference arriving after the editor is connected must not flip the line mode",
                0,
                editor.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE
            )
        }
    }
}

private const val SLOCK_IME_ACTION_REQUESTED_PROP = "slockImeActionRequested"
private const val SLOCK_SYSTEM_NEWLINE_ACTION_PROP = "slockSystemNewlineAction"

private enum class PropUnderTest { NUMBER_OF_LINES, SYSTEM_NEWLINE_ACTION, IME_ACTION_REQUESTED }

private fun permutationsOfThree(): List<List<PropUnderTest>> {
    val all = PropUnderTest.entries
    return all.flatMap { first ->
        all.filter { it != first }.flatMap { second ->
            all.filter { it != first && it != second }.map { third -> listOf(first, second, third) }
        }
    }
}

private fun assertNoEditingCallbacks(
    transition: String,
    textInputStateEvents: List<Any?>,
    legacyTextEvents: List<Any?>,
    selectionEvents: List<Any?>
) {
    assertTrue(
        "$transition must not publish complete editing state: $textInputStateEvents",
        textInputStateEvents.isEmpty()
    )
    assertTrue(
        "$transition must not publish legacy text: $legacyTextEvents",
        legacyTextEvents.isEmpty()
    )
    assertTrue(
        "$transition must not publish selection: $selectionEvents",
        selectionEvents.isEmpty()
    )
}

private fun ActivityScenario<SlockKuiklyActivity>.waitForEditorSnapshot(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    compositionStart: Int,
    compositionEnd: Int,
    timeoutMs: Long = 12_000L
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        instrumentation.waitForIdleSync()
        var matches = false
        onActivity { activity ->
            activity.window.decorView.findOnlyEditText()?.let { editor ->
                matches =
                    editor.text.toString() == text &&
                        editor.selectionStart == selectionStart &&
                        editor.selectionEnd == selectionEnd &&
                        BaseInputConnection.getComposingSpanStart(editor.text) == compositionStart &&
                        BaseInputConnection.getComposingSpanEnd(editor.text) == compositionEnd
            }
        }
        if (matches) return
        Thread.sleep(100L)
    }
    throw AssertionError(
        "editor did not reach text=$text selection=$selectionStart..$selectionEnd " +
            "composition=$compositionStart..$compositionEnd"
    )
}

private fun EditText.requireInputConnection(): InputConnection {
    val connection = onCreateInputConnection(EditorInfo())
    assertNotNull("attachment-comment editor should expose an InputConnection", connection)
    return connection!!
}

private fun View.requireOnlyEditText(): EditText =
    requireNotNull(findOnlyEditText()) { "attachment-comment visual page should contain one EditText" }

private fun View.findOnlyEditText(): EditText? {
    if (this is EditText) return this
    if (this is ViewGroup) {
        for (index in 0 until childCount) {
            getChildAt(index).findOnlyEditText()?.let { return it }
        }
    }
    return null
}

class SlockComposerFinishComposingOnStateReadConnectedTest {
    /**
     * Send reads the editor through the renderer's `getTextInputState` method. While an IME still
     * holds an unfinished composition that read returned text the user had not committed, which is
     * how voice input could be sent mid-dictation.
     *
     * Asserting on the composing span rather than on the returned JSON keeps this bound to the
     * behaviour rather than to the renderer's payload shape.
     *
     * DO NOT DELETE AS REDUNDANT. The production code matches the renderer method by string
     * literal, because `KRTextFieldView.METHOD_GET_TEXT_INPUT_STATE` is private upstream. If a
     * Kuikly bump renames that method, the interception stops running and **nothing else fails**:
     * no compile error, no crash, Send simply goes back to reading uncommitted text. This test is
     * the only thing standing between that rename and a silently reintroduced defect.
     */
    @Test
    fun stateReadFinishesInFlightCompositionBeforeReturningText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val editor = SlockKuiklyTextAreaView(instrumentation.targetContext, null)
            assertTrue(editor.setProp(KRTextProps.PROP_KEY_NUMBER_OF_LINES, 4))
            val connection = editor.requireInputConnection()

            assertTrue(connection.setComposingText("ni", 1))
            // Positive control: without it a passing assertion below could simply mean the
            // composition never started, which is exactly the false green this test exists to
            // avoid.
            assertTrue(
                "composition should be in flight before the state read",
                BaseInputConnection.getComposingSpanStart(editor.text) >= 0
            )

            var responded = false
            editor.call(GET_TEXT_INPUT_STATE_METHOD, null) { responded = true }

            assertTrue("renderer should still answer the state read", responded)
            assertEquals(
                "state read must leave no composing region",
                -1,
                BaseInputConnection.getComposingSpanStart(editor.text)
            )
            assertEquals("committed text must survive", "ni", editor.text.toString())
        }
    }

    /**
     * The barrier must not trust the connection it was handed.
     *
     * A connection whose IME is gone returns `false` from `finishComposingText()` — the same
     * answer it gives when there was simply nothing to finish — and a view that has been destroyed
     * has no connection at all. Both used to let the read proceed with the composition still
     * marked, which is a silent regression to the original defect: the caller sees a normal reply
     * and cannot tell the barrier did nothing.
     *
     * Kuikly pins and reuses editor views across mounts, so a dropped connection outliving its IME
     * is the ordinary case rather than a corner one.
     */
    @Test
    fun stateReadEndsCompositionEvenAfterTheInputConnectionIsDropped() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val editor = SlockKuiklyTextAreaView(instrumentation.targetContext, null)
            assertTrue(editor.setProp(KRTextProps.PROP_KEY_NUMBER_OF_LINES, 4))
            val connection = editor.requireInputConnection()
            assertTrue(connection.setComposingText("ni", 1))

            // Drops the retained connection the way leaving the window does.
            editor.onDestroy()

            // Positive control, and the reason this scenario matters: the editor keeps the
            // composing region after the connection is gone, so there is still something for the
            // barrier to end.
            assertTrue(
                "composition should survive the connection being dropped",
                BaseInputConnection.getComposingSpanStart(editor.text) >= 0
            )

            var responded = false
            editor.call(GET_TEXT_INPUT_STATE_METHOD, null) { responded = true }

            assertTrue("renderer should still answer the state read", responded)
            assertEquals(
                "state read must leave no composing region even without a connection",
                -1,
                BaseInputConnection.getComposingSpanStart(editor.text)
            )
            assertEquals("committed text must survive", "ni", editor.text.toString())
        }
    }

    /** An unrelated method must not finish composition — the interception has to be targeted. */
    @Test
    fun unrelatedRendererMethodLeavesCompositionInFlight() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val editor = SlockKuiklyTextAreaView(instrumentation.targetContext, null)
            assertTrue(editor.setProp(KRTextProps.PROP_KEY_NUMBER_OF_LINES, 4))
            val connection = editor.requireInputConnection()
            assertTrue(connection.setComposingText("ni", 1))

            editor.call("getInnerContentHeight", null) {}

            assertTrue(
                "only the state read should end composition",
                BaseInputConnection.getComposingSpanStart(editor.text) >= 0
            )
        }
    }
}

private const val GET_TEXT_INPUT_STATE_METHOD = "getTextInputState"
