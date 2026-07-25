package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task #800 structural layer (SUPPLEMENTARY — the closing evidence for the
 * two rendering bars lives in SlockThreadAlignRightBoundsConnectedTest, which
 * measures real emulator pixels; see the review round on PR #1569 where
 * structure-only assertions went falsely green against the vendor paragraph).
 * These teeth pin the mechanism so refactors cannot silently remove it.
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

    private fun vendorParagraphSource(): String =
        sourceFile(
            "../../third_party/kuikly-compose-markdown/src/commonMain/kotlin/com/tencent/kuiklybase/markdown/compose/elements/MarkdownParagraph.kt",
            "third_party/kuikly-compose-markdown/src/commonMain/kotlin/com/tencent/kuiklybase/markdown/compose/elements/MarkdownParagraph.kt"
        )

    @Test
    fun paragraphFillIsGovernedByTheCompositionLocalDownToTheVendor() {
        // The real render path: SlockMarkdownBody provides the local, the
        // VENDOR paragraph consumes it for both its text modifier and its
        // wrapper column — the exact layer the first review round proved was
        // still filling width.
        val markdown =
            sourceFile(
                "src/commonMain/kotlin/build/raft/app/markdown/SlockMarkdown.kt",
                "compose/shared/src/commonMain/kotlin/build/raft/app/markdown/SlockMarkdown.kt"
            )
        assertContains(markdown, "LocalSlockMarkdownParagraphFillWidth provides fillWidth")
        val paragraph = vendorParagraphSource()
        assertContains(paragraph, "val paragraphFillWidth = LocalSlockMarkdownParagraphFillWidth.current")
        assertContains(paragraph, "modifier ?: if (paragraphFillWidth) Modifier.fillMaxWidth() else Modifier")
        assertContains(paragraph, "Column(modifier = if (paragraphFillWidth) Modifier.fillMaxWidth() else Modifier)")
        assertFalse(
            paragraph.contains("modifier: Modifier = Modifier.fillMaxWidth()"),
            "vendor paragraph must not hardcode a fillMaxWidth default"
        )
        // List and quote paragraphs pin the legacy fill explicitly.
        val list =
            sourceFile(
                "../../third_party/kuikly-compose-markdown/src/commonMain/kotlin/com/tencent/kuiklybase/markdown/compose/elements/MarkdownList.kt",
                "third_party/kuikly-compose-markdown/src/commonMain/kotlin/com/tencent/kuiklybase/markdown/compose/elements/MarkdownList.kt"
            )
        val quote =
            sourceFile(
                "../../third_party/kuikly-compose-markdown/src/commonMain/kotlin/com/tencent/kuiklybase/markdown/compose/elements/MarkdownBlockQuote.kt",
                "third_party/kuikly-compose-markdown/src/commonMain/kotlin/com/tencent/kuiklybase/markdown/compose/elements/MarkdownBlockQuote.kt"
            )
        assertContains(list, "modifier = Modifier.fillMaxWidth(),")
        assertContains(quote, "MarkdownParagraph(content, quote, modifier = Modifier.fillMaxWidth(), style = style)")
        val thread = threadPageSource()
        assertContains(thread, "fillWidth = !alignOwnMessageRight")
    }

    @Test
    fun sendingSpinnerSharesTheEndContentCoordinatesInFlow() {
        val thread = threadPageSource()
        // Right-aligned rows: in-flow spinner Row inside the End content
        // column — sent removes only left-side width, so the End edge cannot
        // move and no trailing reservation exists to shift it back.
        assertContains(thread, "val messageBodyBlock: @Composable () -> Unit = {")
        assertContains(thread, "if (richContentEnabled && message.isSendingLocalSend()) {")
        assertContains(thread, "ThreadMessageGeometryTintBox(geometryTints?.statusTint) {\n                                                ThreadMessageSendingIndicator()")
        assertContains(
            thread,
            "val reserveInlineTrailingSpace = richContentEnabled && message.isSendingLocalSend() && !alignOwnMessageRight"
        )
        // The row-root overlay serves only the left-aligned layout now.
        assertContains(thread, "message.isSendingLocalSend() && !alignOwnMessageRight -> {")
        // Failed/retry stays in the same End-aligned status slot.
        assertContains(thread, "alignEnd = alignOwnMessageRight")
    }

    @Test
    fun renderingEvidenceHarnessStaysWired() {
        val thread = threadPageSource()
        assertContains(thread, "@Page(\"compose_thread_align_right_visual\", supportInLocal = true)")
        // Geometry tints are the ONLY measurement channel (v2's whole-card
        // scan measured author chrome — both bands' extreme left was the same
        // 912px header pixel). Production default must stay null.
        assertContains(thread, "compositionLocalOf<ThreadMessageGeometryTints?> { null }")
        assertContains(thread, ".background(geometryTints?.capTint ?: Color.Transparent)")
        assertContains(thread, "LocalThreadMessageGeometryTints.current?.retryTint ?: Color.Transparent")
        val oracle =
            sourceFile(
                "../androidApp/src/androidTest/java/build/raft/app/shared/SlockThreadAlignRightBoundsConnectedTest.kt",
                "compose/androidApp/src/androidTest/java/build/raft/app/shared/SlockThreadAlignRightBoundsConnectedTest.kt"
            )
        assertContains(oracle, "exactBandsBetweenMarkers(expectedMarkers = 6)")
        assertContains(oracle, "assertEquals(\"exactly five fixture bands between six markers\", 5, bands.size)")
        assertFalse(
            oracle.contains("isBackground"),
            "the oracle must never scan whole-card non-background extremes again"
        )
        val workflow =
            sourceFile(
                "../../.github/workflows/test.yml",
                ".github/workflows/test.yml"
            )
        assertContains(
            workflow,
            "build.raft.app.shared.SlockThreadAlignRightBoundsConnectedTest",
            false,
            "the rendering-evidence connected test must stay in the emulator CI gate"
        )
    }

    @Test
    fun rightAlignmentNeverFlipsLayoutDirection() {
        // The decoupling bar: alignment is expressed through Alignment.End
        // parameters only — RTL body text cannot flip the actor side, and
        // gesture anchors/popovers keep LTR coordinates.
        val thread = threadPageSource()
        assertFalse(thread.contains("LocalLayoutDirection provides LayoutDirection.Rtl"))
        assertTrue(thread.contains("Arrangement.spacedBy(12.dp, Alignment.End)"))
        val paragraph = vendorParagraphSource()
        assertFalse(paragraph.contains("LayoutDirection"))
    }
}
