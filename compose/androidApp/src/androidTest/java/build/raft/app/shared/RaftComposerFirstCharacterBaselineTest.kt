package build.raft.app.shared

import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The first committed character must already use the composer's configured line height and
 * baseline; the second edit must not move them (the Kuikly-era bug applied the line-height span
 * only from the second edit on). On CMP the composer is a Compose BasicTextField, so the
 * assertions read its TextLayoutResult through semantics instead of an EditText Layout.
 */
class RaftComposerFirstCharacterBaselineTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun firstCharacterUsesConfiguredLineHeightBeforeSecondEdit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, RaftCmpActivity::class.java)
                .putExtra("pageName", RaftPages.THREAD_COMPOSER_VISUAL)
                .putExtra(
                    "pageData",
                    JSONObject()
                        .put("instanceId", "composer-first-character-baseline-test")
                        .put("visualFixture", "empty")
                        .toString()
                )
                .putExtra(RaftCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<RaftCmpActivity>(intent).use { scenario ->
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

            composeRule.waitUntil(timeoutMillis = 12_000L) {
                composerEditorCount() == 1
            }
            // Window focus only: semantics queries must not run on the main thread (onActivity).
            scenario.awaitVisualInputHostReadyForEditorFocus(
                "first-character fixture composer"
            ) { _ -> true }

            val editor = composeRule.onNode(hasSetTextAction(), useUnmergedTree = true)
            assertEquals("", editor.editableText())
            editor.performTextInput("a")
            composeRule.waitUntil(timeoutMillis = 12_000L) { editor.editableText() == "a" }
            val first = editor.textLayout()
            assertEquals("a", first.layoutInput.text.text)
            val firstBaseline = first.getLineBaseline(0)
            val firstLineHeight = first.getLineBottom(0) - first.getLineTop(0)
            assertTrue("configured line height should produce a visible line", firstLineHeight > 0f)

            editor.performTextInput("f")
            composeRule.waitUntil(timeoutMillis = 12_000L) { editor.editableText() == "af" }
            val second = editor.textLayout()
            assertEquals("af", second.layoutInput.text.text)
            assertEquals(firstBaseline, second.getLineBaseline(0), 0.01f)
            assertEquals(firstLineHeight, second.getLineBottom(0) - second.getLineTop(0), 0.01f)
        }
    }

    private fun composerEditorCount(): Int =
        composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .size
}

private fun SemanticsNodeInteraction.editableText(): String? =
    fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text

private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    val action = fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action
    assertTrue("composer must expose its text layout", action?.invoke(results) == true)
    return results.single()
}
