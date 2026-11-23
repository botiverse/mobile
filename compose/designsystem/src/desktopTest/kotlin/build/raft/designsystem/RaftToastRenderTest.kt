package build.raft.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftToast
import build.raft.designsystem.components.RaftToastIntent
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Rendering tests for [RaftToast] (`#proj-mobile:#319`):
 * - Long titles wrap across multiple lines without truncation (`maxLines = Int.MAX_VALUE`),
 *   both on error toasts with description + footnote and on title-only toasts.
 * - Error toasts render title, full wrapping reason (`description`), dedicated Trace ID
 *   (`footnote`), and Copy action stacked in order.
 */
@OptIn(ExperimentalTestApi::class)
class RaftToastRenderTest {
    private fun SemanticsNodeInteraction.textLayoutResult(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val action = fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action
        assertTrue(action?.invoke(results) == true && results.isNotEmpty(), "expected text layout result")
        return results.first()
    }

    @Test
    fun errorToastWrapsLongTitleWithoutTruncationAboveFullReasonAndTraceIdFootnote() =
        runComposeUiTest {
            val longTitle = "Alpha Workspace, Beta Workspace, Gamma Workspace, and Delta Workspace weren't connected"
            val reason =
                "This channel has 151 messages, which is more than can be moved to a shared channel right now. Nothing was changed. Start a new shared channel instead."
            val traceIdFootnote = "Trace ID 62ac7fa7…"

            setContent {
                RaftTheme(
                    appearance = RaftAppearance.Light,
                    systemDark = false,
                    scale = RaftScale.Compact,
                    pointerInput = RaftPointerInput.Touch,
                ) {
                    Box(Modifier.width(360.dp)) {
                        RaftToast(
                            title = longTitle,
                            description = reason,
                            intent = RaftToastIntent.Error,
                            actionLabel = "Copy",
                            onAction = {},
                            footnote = traceIdFootnote,
                        )
                    }
                }
            }

            val titleNode = onNodeWithText(longTitle).assertIsDisplayed()
            val reasonNode = onNodeWithText(reason).assertIsDisplayed()
            val traceNode = onNodeWithText(traceIdFootnote).assertIsDisplayed()
            onNodeWithText("Copy").assertIsDisplayed()

            val titleLayout = titleNode.textLayoutResult()
            assertTrue(titleLayout.lineCount > 1, "expected long toast title to wrap across multiple lines, got ${titleLayout.lineCount}")
            assertFalse(titleLayout.hasVisualOverflow, "expected toast title not to truncate or clip")

            val reasonLayout = reasonNode.textLayoutResult()
            assertTrue(reasonLayout.lineCount > 1, "expected refusal reason to wrap across multiple lines, got ${reasonLayout.lineCount}")
            assertFalse(reasonLayout.hasVisualOverflow, "expected refusal reason not to truncate or clip")

            val traceLayout = traceNode.textLayoutResult()
            assertFalse(traceLayout.hasVisualOverflow, "expected Trace ID footnote not to clip")

            val titleBounds = titleNode.getBoundsInRoot()
            val reasonBounds = reasonNode.getBoundsInRoot()
            val traceBounds = traceNode.getBoundsInRoot()
            assertTrue(titleBounds.bottom <= reasonBounds.top, "expected reason below title: $titleBounds vs $reasonBounds")
            assertTrue(reasonBounds.bottom <= traceBounds.top, "expected Trace ID footnote below reason: $reasonBounds vs $traceBounds")
        }

    @Test
    fun titleOnlyToastAlsoWrapsLongTitleWithoutTruncation() =
        runComposeUiTest {
            val longTitle =
                "This channel has 151 messages, which is more than can be moved to a shared channel right now."

            setContent {
                RaftTheme(
                    appearance = RaftAppearance.Light,
                    systemDark = false,
                    scale = RaftScale.Compact,
                    pointerInput = RaftPointerInput.Touch,
                ) {
                    Box(Modifier.width(360.dp)) {
                        RaftToast(title = longTitle)
                    }
                }
            }

            val titleNode = onNodeWithText(longTitle).assertIsDisplayed()
            val titleLayout = titleNode.textLayoutResult()
            assertTrue(titleLayout.lineCount > 1, "expected title-only toast to wrap across multiple lines, got ${titleLayout.lineCount}")
            assertFalse(titleLayout.hasVisualOverflow, "expected title-only toast not to truncate or clip")
        }
}
