package build.raft.app.shared

import android.content.Intent
import android.os.Bundle
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.kuikly.core.render.android.expand.component.text.HRLineHeightSpan
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlockComposerFirstCharacterBaselineTest {
    @Test
    fun firstCharacterUsesConfiguredLineHeightBeforeSecondEdit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", SlockKuiklyPages.THREAD_COMPOSER_VISUAL)
                .putExtra(
                    "pageData",
                    JSONObject()
                        .put("instanceId", "composer-first-character-baseline-test")
                        .put("visualFixture", "empty")
                        .toString()
                )
                .putExtra(SlockKuiklyActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
            // Recover emulator-owned focus before waitForIdleSync/editor checks. A launcher
            // ANR can leave this activity rendered but unable to receive window focus.
            // Keep the host readiness and first-character assertions below unchanged.
            val recovery = AndroidActiveWindowRecovery.awaitActiveWindow(context.packageName)
            val recoveryReceipt = "FIRST_CHARACTER_WINDOW_RECOVERY ${recovery.describe()}"
            instrumentation.sendStatus(
                0,
                Bundle().apply { putString("firstCharacterWindowRecovery", recoveryReceipt) },
            )
            println(recoveryReceipt)
            assertTrue("fixture did not acquire the active window; $recoveryReceipt", recovery.acquired)

            waitUntil("composer EditText should attach and finish its empty layout") {
                var ready = false
                scenario.onActivity { activity ->
                    val editText = activity.window.decorView.findComposerEditText()
                    ready = editText != null && editText.layout?.text?.isEmpty() == true
                }
                ready
            }

            scenario.awaitVisualInputHostReadyForEditorFocus(
                "first-character fixture composer"
            ) { activity -> activity.window.decorView.findComposerEditText() != null }
            scenario.onActivity { activity ->
                val editText = activity.window.decorView.findComposerEditText()
                assertNotNull("composer EditText should be present", editText)
                editText!!
                val emptySpan = editText.requireLineHeightSpan()
                assertEquals(0, editText.text.getSpanStart(emptySpan))
                assertEquals(0, editText.text.getSpanEnd(emptySpan))
                assertEquals(
                    Spanned.SPAN_INCLUSIVE_INCLUSIVE,
                    editText.text.getSpanFlags(emptySpan) and Spanned.SPAN_POINT_MARK_MASK
                )
                assertTrue("composer EditText should accept focus", editText.requestFocus())
                editText.commitImeText("a")
            }

            waitUntil("first character should reach the laid-out editor") {
                var ready = false
                scenario.onActivity { activity ->
                    val editText = activity.window.decorView.findComposerEditText()
                    ready = editText?.text?.toString() == "a" && editText.layout?.text?.toString() == "a"
                }
                ready
            }

            var firstBaseline = Int.MIN_VALUE
            var firstLineHeight = Int.MIN_VALUE
            scenario.onActivity { activity ->
                val editText = activity.window.decorView.findComposerEditText()!!
                assertEquals("a", editText.text.toString())
                val firstSpan = editText.requireLineHeightSpan()
                assertEquals(0, editText.text.getSpanStart(firstSpan))
                assertEquals(1, editText.text.getSpanEnd(firstSpan))
                firstBaseline = editText.layout.getLineBaseline(0)
                firstLineHeight = editText.layout.getLineBottom(0) - editText.layout.getLineTop(0)
                assertTrue("configured line height should produce a visible line", firstLineHeight > 0)
                editText.commitImeText("f")
            }

            waitUntil("second character should reach the laid-out editor") {
                var ready = false
                scenario.onActivity { activity ->
                    val editText = activity.window.decorView.findComposerEditText()
                    ready = editText?.text?.toString() == "af" && editText.layout?.text?.toString() == "af"
                }
                ready
            }

            scenario.onActivity { activity ->
                val editText = activity.window.decorView.findComposerEditText()!!
                assertEquals("af", editText.text.toString())
                val secondSpan = editText.requireLineHeightSpan()
                assertEquals(0, editText.text.getSpanStart(secondSpan))
                assertEquals(2, editText.text.getSpanEnd(secondSpan))
                assertEquals(firstBaseline, editText.layout.getLineBaseline(0))
                assertEquals(
                    firstLineHeight,
                    editText.layout.getLineBottom(0) - editText.layout.getLineTop(0)
                )
            }
        }
    }
}

private fun EditText.commitImeText(value: String) {
    val inputConnection = onCreateInputConnection(EditorInfo())
    assertNotNull("composer should expose an InputConnection", inputConnection)
    assertTrue("IME commit should succeed for '$value'", inputConnection!!.commitText(value, 1))
}

private fun waitUntil(
    message: String,
    timeoutMs: Long = 12_000L,
    stepMs: Long = 100L,
    condition: () -> Boolean
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        instrumentation.waitForIdleSync()
        if (condition()) {
            return
        }
        Thread.sleep(stepMs)
    }
    throw AssertionError(message)
}

private fun EditText.requireLineHeightSpan(): HRLineHeightSpan {
    val spans = text.getSpans(0, text.length, HRLineHeightSpan::class.java)
    assertEquals("composer should have exactly one line-height span", 1, spans.size)
    return spans.single()
}

private fun View.findComposerEditText(): EditText? {
    if (this is EditText) return this
    if (this is ViewGroup) {
        for (index in 0 until childCount) {
            getChildAt(index).findComposerEditText()?.let { return it }
        }
    }
    return null
}
