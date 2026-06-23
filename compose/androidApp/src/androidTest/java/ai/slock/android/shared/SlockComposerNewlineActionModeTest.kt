package ai.slock.android.shared

import ai.slock.android.core.data.diagnostics.SanitizedDiagnosticsLogBuffer
import android.content.Intent
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlockComposerNewlineActionModeTest {
    @Test
    fun longPressComposerInstallsNewlineActionModeItem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", SlockKuiklyPages.THREAD_COMPOSER_VISUAL)
                .putExtra(
                    "pageData",
                    JSONObject()
                        .put("instanceId", "composer-newline-action-mode-test")
                        .put("visualFixture", "states")
                        .toString()
                )
                .putExtra(SlockKuiklyActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        SanitizedDiagnosticsLogBuffer.clear()
        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(1_500)
            scenario.onActivity { activity ->
                val editText = activity.window.decorView.findFirstEditText()
                assertNotNull("composer EditText should be present", editText)
                editText!!
                editText.requestFocus()
                editText.setText("test composer text")
                editText.setSelection(editText.text.length)
                assertTrue(
                    "composer EditText long-click should be handled",
                    editText.performLongClick(editText.width / 2f, editText.height / 2f)
                )
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(500)
        }

        val messages =
            SanitizedDiagnosticsLogBuffer.recent()
                .filter { it.tag == "SlockComposerNewline" }
                .joinToString("\n") { it.message }
        assertTrue(messages, messages.contains("setProp systemNewlineAction raw=Int:1 enabled=true"))
        assertTrue(messages, messages.contains("setProp numberOfLines raw=Int:4 parsed=4"))
        assertTrue(messages, messages.contains("applyActionMode installed=true"))
        assertTrue(messages, messages.contains("performLongClick"))
        assertTrue(messages, messages.contains("onCreateActionMode after"))
        assertTrue(messages, messages.contains("ensureNewlineMenuItem added"))
    }
}

private fun android.view.View.findFirstEditText(): EditText? {
    if (this is EditText) return this
    if (this is android.view.ViewGroup) {
        for (index in 0 until childCount) {
            getChildAt(index).findFirstEditText()?.let { return it }
        }
    }
    return null
}
