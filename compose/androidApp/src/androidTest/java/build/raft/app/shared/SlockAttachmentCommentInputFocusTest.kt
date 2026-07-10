package build.raft.app.shared

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlockAttachmentCommentInputFocusTest {
    @Test
    fun filePreviewCommentInputAcceptsFocusAndText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", "compose_thread_comment_anchor_visual")
                .putExtra(
                    "pageData",
                    JSONObject()
                        .put("instanceId", "file-preview-comment-input-focus-test")
                        .toString()
                )
                .putExtra(SlockKuiklyActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
            instrumentation.waitForIdleSync()
            Thread.sleep(1_500)
            scenario.onActivity { activity ->
                val editText = activity.window.decorView.findFirstEditText()
                assertNotNull("file preview comment EditText should be present", editText)
                editText!!
                assertTrue("file preview comment EditText should accept focus", editText.requestFocus())
                editText.setText("Focused file preview comment")
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(300)
            scenario.onActivity { activity ->
                val editText = activity.window.decorView.findFirstEditText()
                assertNotNull("file preview comment EditText should remain present", editText)
                assertEquals("Focused file preview comment", editText!!.text.toString())
                assertTrue("file preview comment EditText should remain focused", editText.hasFocus())
            }
        }
    }
}

private fun View.findFirstEditText(): EditText? {
    if (this is EditText) return this
    if (this is ViewGroup) {
        for (index in 0 until childCount) {
            getChildAt(index).findFirstEditText()?.let { return it }
        }
    }
    return null
}
