package build.raft.app.shared

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composer
import androidx.compose.runtime.tooling.ComposeStackTraceMode
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule

/**
 * The first committed character must already use the composer's configured line height and
 * baseline; the second edit must not move them (the Kuikly-era bug applied the line-height span
 * only from the second edit on). On CMP the composer is a Compose BasicTextField, so the
 * assertions read its TextLayoutResult through semantics instead of an EditText Layout.
 */
class RaftComposerFirstCharacterBaselineTest {
    private val offMainSnapshotWrites = RaftOffMainSnapshotWriteLog()

    // #310 crash diagnostics (RaftThreadDiagnosticsTestSupport.kt). Outermost rule, so a failure
    // the compose rule raises while tearing down is dumped too.
    @get:Rule(order = 0)
    val threadDumpOnFailure: TestRule = RaftThreadDumpOnFailure(offMainSnapshotWrites)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Before
    fun startCrashDiagnostics() {
        // Composition, measure and layout exceptions then carry the composable stack with source
        // information. setDiagnosticStackTraceEnabled(true) does the same; runtime 1.12 deprecates it.
        Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.SourceInformation)
        offMainSnapshotWrites.register()
    }

    @After
    fun stopCrashDiagnostics() {
        offMainSnapshotWrites.dispose()
        Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.None)
    }

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
            assertEquals("a", first.text)
            val firstBaseline = first.firstLineBaseline
            val firstLineHeight = first.firstLineHeight
            assertTrue("configured line height should produce a visible line", firstLineHeight > 0f)

            editor.performTextInput("f")
            composeRule.waitUntil(timeoutMillis = 12_000L) { editor.editableText() == "af" }
            val second = editor.textLayout()
            assertEquals("af", second.text)
            assertEquals(firstBaseline, second.firstLineBaseline, 0.01f)
            assertEquals(firstLineHeight, second.firstLineHeight, 0.01f)
        }
    }

    private fun composerEditorCount(): Int =
        composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .size

    private class ComposerTextLayout(val text: String, val firstLineBaseline: Float, val firstLineHeight: Float)

    /**
     * The composer's laid-out text and its first line, read from the field's TextLayoutResult.
     *
     * fetchSemanticsNode() syncs with the UI, so it runs on the test thread. Invoking the field's
     * GetTextLayoutResult action (it can lay the text out again) and reading the layout are
     * Compose text layout, which is main-thread-only (AGENTS.md "Main-thread-only APIs"): both run
     * on the UI thread (#310).
     */
    private fun SemanticsNodeInteraction.textLayout(): ComposerTextLayout {
        val node = fetchSemanticsNode()
        val layout =
            composeRule.runOnUiThread {
                val results = mutableListOf<TextLayoutResult>()
                val action = node.config[SemanticsActions.GetTextLayoutResult].action
                if (action?.invoke(results) != true) return@runOnUiThread null
                val result = results.single()
                ComposerTextLayout(
                    text = result.layoutInput.text.text,
                    firstLineBaseline = result.getLineBaseline(0),
                    firstLineHeight = result.getLineBottom(0) - result.getLineTop(0),
                )
            }
        assertTrue("composer must expose its text layout", layout != null)
        return layout!!
    }
}

private fun SemanticsNodeInteraction.editableText(): String? =
    fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
