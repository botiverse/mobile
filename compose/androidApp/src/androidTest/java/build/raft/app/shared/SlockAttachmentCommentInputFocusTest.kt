package build.raft.app.shared

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
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
    fun filePreviewCommentContainerTapFocusesAndAcceptsText() {
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
                .putExtra(SlockCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
            instrumentation.waitForIdleSync()
            Thread.sleep(1_500)
            var tapX = 0f
            var tapY = 0f
            scenario.onActivity { activity ->
                val editText = activity.window.decorView.findFirstEditText()
                assertNotNull("file preview comment EditText should be present", editText)
                editText!!
                editText.clearFocus()
                val location = IntArray(2)
                editText.getLocationOnScreen(location)
                val tapInsetPx = 4f * activity.resources.displayMetrics.density
                tapX = location[0] + editText.width + tapInsetPx
                tapY = location[1] + editText.height / 2f
                assertTrue("tap must land outside the native EditText", tapX > location[0] + editText.width)
                assertTrue("tap must remain inside the window", tapX < activity.window.decorView.width)
            }
            val downTime = SystemClock.uptimeMillis()
            instrumentation.sendPointerSync(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, tapX, tapY, 0))
            instrumentation.sendPointerSync(MotionEvent.obtain(downTime, downTime + 80L, MotionEvent.ACTION_UP, tapX, tapY, 0))
            instrumentation.waitForIdleSync()
            Thread.sleep(300)
            scenario.onActivity { activity ->
                val editText = activity.window.decorView.findFirstEditText()
                assertNotNull("file preview comment EditText should remain present", editText)
                assertTrue("file preview comment container tap should focus EditText", editText!!.hasFocus())
            }
            instrumentation.sendStringSync("FocusedComment")
            instrumentation.waitForIdleSync()
            Thread.sleep(300)
            scenario.onActivity { activity ->
                val editText = activity.window.decorView.findFirstEditText()
                assertNotNull("file preview comment EditText should remain present", editText)
                assertEquals("FocusedComment", editText!!.text.toString())
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
