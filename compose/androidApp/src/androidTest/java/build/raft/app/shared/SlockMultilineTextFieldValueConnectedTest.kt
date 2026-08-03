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
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlockMultilineTextFieldValueConnectedTest {
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
