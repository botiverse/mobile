package build.raft.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
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
import build.raft.designsystem.components.RaftToastHost
import build.raft.designsystem.components.RaftToastHostState
import build.raft.designsystem.components.RaftToastIntent
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /**
     * Assert every character of [expected] is visibly laid out without vertical overflow or
     * ellipsis. Note: we intentionally avoid `TextLayoutResult.hasVisualOverflow` /
     * `didOverflowWidth` on short single-line strings because Compose's semantics layout measures
     * `multiParagraph.width` at max constraint width while `size.width` hugs the intrinsic text
     * width (`size.width < multiParagraph.width` false-positives; see `SavedRowMetaLineUiTest`).
     */
    private fun assertRenderedWhole(label: String, expected: String, layout: TextLayoutResult) {
        assertFalse(layout.didOverflowHeight, "expected $label not to overflow vertically")
        val ellipsized = (0 until layout.lineCount).any { layout.isLineEllipsized(it) }
        assertFalse(ellipsized, "expected $label not to be ellipsized")
        val visibleEnd = layout.getLineEnd(layout.lineCount - 1, visibleEnd = true)
        assertEquals(expected.length, visibleEnd, "expected all characters of $label to render visibly")
        if (layout.lineCount == 1) {
            val naturalWidth = ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth).toInt()
            assertTrue(
                layout.size.width >= naturalWidth,
                "expected single-line $label to fit its natural width (${layout.size.width}px < ${naturalWidth}px)",
            )
        }
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
            assertRenderedWhole("toast title", longTitle, titleLayout)

            val reasonLayout = reasonNode.textLayoutResult()
            assertTrue(reasonLayout.lineCount > 1, "expected refusal reason to wrap across multiple lines, got ${reasonLayout.lineCount}")
            assertRenderedWhole("refusal reason", reason, reasonLayout)

            val traceLayout = traceNode.textLayoutResult()
            assertRenderedWhole("Trace ID footnote", traceIdFootnote, traceLayout)

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
            assertRenderedWhole("title-only toast", longTitle, titleLayout)
        }

    @Test
    fun toastHostReportsDeduplicatedUnionBoundsAndNullOnEmptyAndDispose() =
        runComposeUiTest {
            val state = RaftToastHostState(limit = 2)
            var mounted by mutableStateOf(true)
            val reported = mutableListOf<Rect?>()

            setContent {
                RaftTheme(
                    appearance = RaftAppearance.Light,
                    systemDark = false,
                    scale = RaftScale.Compact,
                    pointerInput = RaftPointerInput.Touch,
                ) {
                    Box(Modifier.size(width = 390.dp, height = 844.dp)) {
                        if (mounted) {
                            RaftToastHost(
                                state = state,
                                onToastBoundsChange = { reported += it },
                            )
                        }
                    }
                }
            }

            waitForIdle()
            assertTrue(reported.isEmpty() || reported.last() == null, "no non-null bounds before any toast: $reported")

            val firstId = state.show("First toast", durationMillis = 0)
            waitForIdle()
            val singleBounds = reported.lastOrNull()
            assertTrue(singleBounds != null && !singleBounds.isEmpty, "expected non-empty bounds for visible toast, got $reported")

            val countBeforeIdle = reported.size
            waitForIdle()
            assertEquals(countBeforeIdle, reported.size, "unchanged settled bounds must not re-emit")

            state.show("Second toast", durationMillis = 0)
            waitForIdle()
            val stackedBounds = reported.lastOrNull()
            assertTrue(
                stackedBounds != null && stackedBounds.height > singleBounds.height,
                "expected union height to grow for stacked toasts: single=$singleBounds stacked=$stackedBounds",
            )

            state.dismiss(firstId)
            state.dismissAll()
            waitForIdle()
            assertEquals(null, reported.lastOrNull(), "dismissing all toasts must report null")

            val countBeforeDispose = reported.size
            mounted = false
            waitForIdle()
            assertEquals(countBeforeDispose, reported.size, "disposing an already-empty host must not emit a duplicate null")
            assertEquals(null, reported.last())
        }

    @Test
    fun toastHostEmitsNullExactlyOnceWhenDisposedWithVisibleToast() =
        runComposeUiTest {
            val state = RaftToastHostState(limit = 2)
            var mounted by mutableStateOf(true)
            val reported = mutableListOf<Rect?>()

            setContent {
                RaftTheme(
                    appearance = RaftAppearance.Light,
                    systemDark = false,
                    scale = RaftScale.Compact,
                    pointerInput = RaftPointerInput.Touch,
                ) {
                    Box(Modifier.size(width = 390.dp, height = 844.dp)) {
                        if (mounted) {
                            RaftToastHost(
                                state = state,
                                onToastBoundsChange = { reported += it },
                            )
                        }
                    }
                }
            }

            state.show("Live toast on dispose", durationMillis = 0)
            waitForIdle()
            assertTrue(reported.lastOrNull()?.isEmpty == false, "expected live toast bounds before dispose: $reported")

            val countBeforeDispose = reported.size
            mounted = false
            waitForIdle()
            assertEquals(
                countBeforeDispose + 1,
                reported.size,
                "disposing host while toast is visible must emit null exactly once: $reported",
            )
            assertEquals(null, reported.last())
        }
}
