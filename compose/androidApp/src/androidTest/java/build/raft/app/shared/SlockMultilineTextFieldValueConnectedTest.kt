package build.raft.app.shared

import android.content.Intent
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

                // CoreTextField applies line count and the initial keyboard options before it
                // registers any editing callbacks. Mirror that production creation order so the
                // one-time Android multiline + IME-action structure setup happens off-channel.
                assertTrue(editor.setProp(KRTextProps.PROP_KEY_NUMBER_OF_LINES, Int.MAX_VALUE))
                assertTrue(editor.setProp("returnKeyType", "none"))

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
