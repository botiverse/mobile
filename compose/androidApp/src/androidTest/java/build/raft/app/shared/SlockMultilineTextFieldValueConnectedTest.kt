package build.raft.app.shared

import android.content.Intent
import android.text.InputType
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
import org.junit.Assert.assertTrue
import org.junit.Test

class SlockMultilineTextFieldValueConnectedTest {
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
