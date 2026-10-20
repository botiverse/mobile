package build.raft.app.shared

import android.content.Intent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Exact-value editing through the production CMP editor (a Compose BasicTextField hosted by
 * RaftCmpActivity's ComposeView).
 *
 * The Kuikly-era cases in this class exercised the Kuikly native editor views
 * (SlockKuiklyTextFieldView / SlockKuiklyTextAreaView, KRTextProps prop ordering, the
 * `getTextInputState` renderer method and the Android 14 ArrowKeyMovementMethod touch-anchor
 * workaround). Those views no longer exist, so those cases were removed with them; the page-level
 * guarantee below still applies and is driven through the real IME InputConnection of the
 * focused Compose view.
 */
class RaftMultilineTextFieldValueConnectedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun attachmentCommentRetainsCjkCompositionAndMidTextEditsAcrossRecomposition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, RaftCmpActivity::class.java)
                .putExtra("pageName", "compose_thread_comment_anchor_visual")
                .putExtra(
                    "pageData",
                    JSONObject()
                        .put("instanceId", "attachment-comment-exact-value-test")
                        .toString()
                )
                .putExtra(RaftCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<RaftCmpActivity>(intent).use { scenario ->
            composeRule.waitUntil(timeoutMillis = 12_000L) { editorCount() == 1 }
            val editor = composeRule.onNode(hasSetTextAction(), useUnmergedTree = true)
            editor.waitForSnapshot("", 0, 0)

            scenario.awaitVisualInputHostReadyForEditorFocus(
                "attachment-comment fixture editor"
            ) { _ -> true }
            editor.performSemanticsAction(SemanticsActions.RequestFocus)
            composeRule.waitForIdle()
            // The test plays the IME below; keep the real (focus-requested) keyboard out of it.
            scenario.hideSoftKeyboardAndAwaitHidden("attachment-comment fixture editor")
            scenario.raftAwaitTextInputSession("attachment-comment fixture editor")

            // Composition: setComposingText replaces the in-flight composition each time, so the
            // final text proves the composing region was retained across recompositions.
            scenario.withInputConnection { assertTrue(it.setComposingText("n", 1)) }
            editor.waitForSnapshot("n", 1, 1)
            scenario.withInputConnection { assertTrue(it.setComposingText("ni", 1)) }
            editor.waitForSnapshot("ni", 2, 2)

            val committed = "你好\nreview"
            scenario.withInputConnection { assertTrue(it.commitText(committed, 1)) }
            editor.waitForSnapshot(committed, committed.length, committed.length)

            val inserted = "你X好\nreview"
            scenario.withInputConnection {
                assertTrue(it.setSelection(1, 1))
                assertTrue(it.commitText("X", 1))
            }
            editor.waitForSnapshot(inserted, 2, 2)

            scenario.withInputConnection { assertTrue(it.deleteSurroundingText(1, 0)) }
            editor.waitForSnapshot(committed, 1, 1)

            val retyped = "你再好\nreview"
            scenario.withInputConnection { assertTrue(it.setComposingText("再", 1)) }
            editor.waitForSnapshot(retyped, 2, 2)
            scenario.withInputConnection { assertTrue(it.commitText("再", 1)) }
            editor.waitForSnapshot(retyped, 2, 2)
        }
    }

    private fun editorCount(): Int =
        composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .size

    private fun SemanticsNodeInteraction.waitForSnapshot(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
    ) {
        val expectedSelection = TextRange(selectionStart, selectionEnd)
        var last: Pair<String?, TextRange?> = null to null
        try {
            composeRule.waitUntil(timeoutMillis = 12_000L) {
                val config = fetchSemanticsNode().config
                last =
                    config.getOrNull(SemanticsProperties.EditableText)?.text to
                        config.getOrNull(SemanticsProperties.TextSelectionRange)
                last.first == text && last.second == expectedSelection
            }
        } catch (timeout: Throwable) {
            throw AssertionError(
                "editor did not reach text=$text selection=$expectedSelection; last=$last",
                timeout,
            )
        }
    }
}

/** The IME connection of the focused Compose view, i.e. what the soft keyboard talks to. */
private fun ActivityScenario<RaftCmpActivity>.withInputConnection(block: (InputConnection) -> Unit) {
    onActivity { activity ->
        val focused = activity.currentFocus
        assertNotNull("the attachment-comment editor should own window focus", focused)
        val connection = focused!!.onCreateInputConnection(EditorInfo())
        assertNotNull("the focused editor should expose an InputConnection", connection)
        block(connection!!)
    }
}
