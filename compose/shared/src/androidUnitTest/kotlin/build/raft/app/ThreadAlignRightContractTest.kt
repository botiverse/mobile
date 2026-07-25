package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task #800: with "align own messages right" enabled, short content must hug
 * the End edge, and the sending/loading affordances must mirror to the
 * bubble's left — implemented by extending the existing `alignOwnMessageRight`
 * parameterization, never by flipping layout direction (BiDi text, gesture
 * anchors, and the Kuikly render hosts are not RTL-safe). These teeth pin the
 * four product bars ZhangYi froze in #proj-chat:ba4e52e4.
 */
class ThreadAlignRightContractTest {
    private fun sourceFile(vararg candidates: String): String =
        candidates.map(::File).firstOrNull(File::isFile)?.readText()?.replace("\r\n", "\n")
            ?: error("Unable to locate any of: ${candidates.joinToString()}")

    private fun threadPageSource(): String =
        sourceFile(
            "src/commonMain/kotlin/build/raft/app/ThreadPage.kt",
            "compose/shared/src/commonMain/kotlin/build/raft/app/ThreadPage.kt"
        )

    private fun markdownSource(): String =
        sourceFile(
            "src/commonMain/kotlin/build/raft/app/markdown/SlockMarkdown.kt",
            "compose/shared/src/commonMain/kotlin/build/raft/app/markdown/SlockMarkdown.kt"
        )

    @Test
    fun shortContentWrapsSoASingleCharacterHugsTheEndEdge() {
        val thread = threadPageSource()
        // The rich body opts out of fill-width exactly when the row is
        // right-aligned; the parent column's End alignment then pulls a short
        // paragraph to the End edge.
        assertContains(thread, "fillWidth = !alignOwnMessageRight")
        assertContains(thread, "val contentAlignment = if (alignOwnMessageRight) Alignment.End else Alignment.Start")
        val markdown = markdownSource()
        // SlockMarkdownBody is the last public entry point in the file; the
        // suffix from its declaration is the scope these assertions pin.
        val bodyFn = markdown.substringAfter("fun SlockMarkdownBody(")
        assertContains(bodyFn, "fillWidth: Boolean = true")
        // Fill-width must be conditional everywhere in the body root — an
        // unconditional fillMaxWidth() would regress the wrap behavior.
        assertContains(bodyFn, "if (fillWidth) it.fillMaxWidth() else it")
        assertFalse(
            Regex("modifier\\s*\\n?\\s*\\.fillMaxWidth\\(\\)").containsMatchIn(bodyFn),
            "SlockMarkdownBody root must not unconditionally fillMaxWidth"
        )
    }

    @Test
    fun sendingReservationKeepsTheEndEdgeStableOnRightAlignedRows() {
        val thread = threadPageSource()
        // The 30dp spinner reservation moves to the START side when right
        // aligned, so the bubble's End edge does not shift when the message
        // transitions sending → sent.
        assertContains(thread, "start = if (reserveInlineTrailingSpace && alignOwnMessageRight) 30.dp else 0.dp")
        assertContains(thread, "end = if (reserveInlineTrailingSpace && !alignOwnMessageRight) 30.dp else 0.dp")
        // And the spinner itself mirrors to the bubble's left.
        assertContains(thread, "if (alignOwnMessageRight) Alignment.TopStart else Alignment.TopEnd")
    }

    @Test
    fun sendingAndFailureShareOneMirroredStatusSlot() {
        val thread = threadPageSource()
        val status =
            thread.substringAfter("private fun ThreadMessageLocalSendStatus(")
                .substringBefore("private fun ThreadMessageSendingIndicator(")
        // One composable renders both the sending and failed/retry states, and
        // its alignment follows the same alignEnd parameter the row passes.
        assertContains(status, "ThreadMessageDeliveryFailed")
        assertContains(status, "Sending...")
        assertContains(status, "onRetry")
        assertContains(status, "if (alignEnd) Alignment.End else Alignment.Start")
        assertContains(thread, "alignEnd = alignOwnMessageRight")
    }

    @Test
    fun rightAlignmentNeverFlipsLayoutDirection() {
        // The decoupling bar: alignment is expressed through Alignment.End
        // parameters only. Flipping LayoutDirection would mirror BiDi text
        // rendering, gesture anchors, and popover placement — none of which
        // are RTL-audited in the Kuikly hosts.
        val thread = threadPageSource()
        assertFalse(
            thread.contains("LocalLayoutDirection provides LayoutDirection.Rtl"),
            "message alignment must not be implemented via RTL layout direction"
        )
        val markdown = markdownSource()
        assertFalse(
            markdown.contains("LocalLayoutDirection provides LayoutDirection.Rtl"),
            "markdown body must not flip layout direction for alignment"
        )
        assertTrue(thread.contains("Arrangement.spacedBy(12.dp, Alignment.End)"))
    }
}
