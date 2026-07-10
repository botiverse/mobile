package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class ThreadLongMessageCollapseTest {

    @Test
    fun shortMessageIsNotCollapsible() {
        val body = "Short message\nwith two lines."

        val plan = threadLongMessageRenderPlan(body)

        assertFalse(plan.collapsible)
        assertEquals(body, plan.collapsedBody)
        assertEquals(body, plan.collapsedDisplayText)
        assertEquals(2, plan.lineCount)
    }

    @Test
    fun eightLineMessageIsNotCollapsible() {
        val body = (1..8).joinToString("\n") { line -> "line $line" }

        val plan = threadLongMessageRenderPlan(body)

        assertFalse(plan.collapsible)
        assertEquals(body, plan.collapsedDisplayText)
        assertEquals(8, plan.lineCount)
    }

    @Test
    fun defaultCollapsedPreviewKeepsEightLinesAndAddsEllipsis() {
        val body = (1..12).joinToString("\n") { line -> "log line $line" }

        val plan = threadLongMessageRenderPlan(body)

        assertTrue(plan.collapsible)
        assertEquals((1..8).joinToString("\n") { line -> "log line $line" }, plan.collapsedBody)
        assertEquals((1..7).joinToString("\n", postfix = "\nlog line 8…") { line -> "log line $line" }, plan.collapsedDisplayText)
    }

    @Test
    fun longLogKeepsOnlyConfiguredLeadingLines() {
        val body = (1..15).joinToString("\n") { line -> "log line $line" }

        val plan = threadLongMessageRenderPlan(body, lineLimit = 12, charLimit = 10_000)

        assertTrue(plan.collapsible)
        assertEquals((1..12).joinToString("\n") { line -> "log line $line" }, plan.collapsedBody)
        assertEquals((1..11).joinToString("\n", postfix = "\nlog line 12…") { line -> "log line $line" }, plan.collapsedDisplayText)
        assertTrue(plan.lineCount > 12)
    }

    @Test
    fun longSingleLineUsesCharacterFallback() {
        val body = "x".repeat(1_201)

        val plan = threadLongMessageRenderPlan(body)

        assertTrue(plan.collapsible)
        assertEquals(ThreadLongMessagePreviewCharLimit, plan.collapsedBody.length)
        assertEquals(ThreadLongMessagePreviewCharLimit + 1, plan.collapsedDisplayText.length)
        assertTrue(plan.collapsedDisplayText.endsWith("…"))
    }

    @Test
    fun characterFallbackDoesNotSplitSurrogatePair() {
        val body = "a".repeat(9) + "\uD83D\uDE80" + "tail"

        val plan = threadLongMessageRenderPlan(body, lineLimit = 10, charLimit = 10)

        assertTrue(plan.collapsible)
        assertEquals("a".repeat(9), plan.collapsedBody)
        assertFalse(plan.collapsedBody.last().isHighSurrogate())
    }

    @Test
    fun collapsedMarkdownClosesOpenCodeFence() {
        val body = buildString {
            appendLine("```text")
            repeat(14) { index -> appendLine("log $index") }
        }

        val plan = threadLongMessageRenderPlan(body, lineLimit = 5, charLimit = 10_000)

        assertTrue(plan.collapsible)
        assertTrue(plan.collapsedBody.startsWith("```text\nlog 0"))
        assertTrue(plan.collapsedBody.endsWith("\n```"))
    }

    @Test
    fun collapsedMarkdownDoesNotAddFenceWhenAlreadyClosed() {
        val body = "```text\nlog\n```\n" + (1..12).joinToString("\n") { "line $it" }

        val plan = threadLongMessageRenderPlan(body, lineLimit = 6, charLimit = 10_000)

        assertTrue(plan.collapsible)
        assertEquals(2, plan.collapsedBody.windowed(3).count { it == "```" })
    }

    @Test
    fun escapedNewlinesUseRenderedLineThreshold() {
        val body = (1..13).joinToString("\\n") { line -> "log line $line" }

        val plan = threadLongMessageRenderPlan(body, lineLimit = 12, charLimit = 10_000)

        assertTrue(plan.collapsible)
        assertEquals(13, plan.lineCount)
        assertEquals((1..12).joinToString("\n") { line -> "log line $line" }, plan.collapsedBody)
    }

    @Test
    fun expandedIdsToggleByMessageIdentityWithoutMutatingInput() {
        val original = setOf("m1")

        val expanded = threadExpandedLongMessageIdsAfterToggle(original, "m2")
        val collapsed = threadExpandedLongMessageIdsAfterToggle(expanded, "m1")

        assertNotSame(original, expanded)
        assertEquals(setOf("m1"), original)
        assertEquals(setOf("m1", "m2"), expanded)
        assertEquals(setOf("m2"), collapsed)
    }

    @Test
    fun collapseAnchorClampsOffsetInsideShrunkFirstVisibleMessage() {
        assertEquals(
            299,
            threadLongMessageCollapseAnchorOffset(
                firstVisibleItemIndex = 4,
                toggledItemIndex = 4,
                firstVisibleItemScrollOffset = 900,
                collapsedItemHeight = 300
            )
        )
        assertEquals(
            120,
            threadLongMessageCollapseAnchorOffset(
                firstVisibleItemIndex = 4,
                toggledItemIndex = 4,
                firstVisibleItemScrollOffset = 120,
                collapsedItemHeight = 300
            )
        )
    }

    @Test
    fun collapseAnchorDoesNotCreateScrollForOtherRows() {
        assertEquals(
            null,
            threadLongMessageCollapseAnchorOffset(
                firstVisibleItemIndex = 3,
                toggledItemIndex = 4,
                firstVisibleItemScrollOffset = 120,
                collapsedItemHeight = 300
            )
        )
    }
}
