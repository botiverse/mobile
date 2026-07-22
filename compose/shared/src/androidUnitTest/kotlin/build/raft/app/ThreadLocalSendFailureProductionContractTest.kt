package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadLocalSendFailureProductionContractTest {
    @Test
    fun failedBubbleUsesTypedSummaryAndSeparateRetryDetailsCopyActions() {
        val source = threadPageSource()
        val status = block(
            source,
            "private fun ThreadMessageLocalSendStatus(",
            "private fun ThreadMessageSendingIndicator("
        )

        assertContains(status, "threadSendFailureSummary(message.pendingSendErrorCode, userFacingLabels)")
        assertFalse("threadSendFailureStatusLabel(" in status)
        assertFalse("summary = message.pendingSendErrorMessage" in status)
        assertContains(status, "var detailsExpanded by remember(message.id, detail)")
        assertContains(status, "onClick = { if (message.canRetryLocalSend()) onRetry() }")
        assertContains(status, "Modifier.clickable { detailsExpanded = !detailsExpanded }")
        assertContains(status, "Modifier.clickable(onClick = onCopyDetails)")
        assertContains(status, "text = detail")
        assertFalse("maxLines = 1" in status.substringAfter("text = detail"))
    }

    @Test
    fun copyActionRebuildsTheFullRedactedDetailFromPendingMetadata() {
        val source = threadPageSource()
        val eventHandler = block(
            source,
            "            onThreadEvent = { action, itemId, attachmentId, tab ->",
            "            onComposerEvent ="
        )

        assertContains(eventHandler, "action == \"copyLocalSendFailureDetails\"")
        assertContains(eventHandler, "threadFailureDiagnosticDetail(it.pendingSendErrorCode, it.pendingSendErrorMessage)")
        assertContains(eventHandler, "bridge.copyToPasteboard(detail)")
        assertContains(eventHandler, "bridge.toast(failureDetailsCopiedLabel)")
    }

    @Test
    fun retryAndCopyCallsitesRemainIndependentAtTheMessageCardBoundary() {
        val source = threadPageSource()
        val listCallsite = block(
            source,
            "                                onRetryLocalSend = { onThreadEvent(\"retryLocalSend\"",
            "                                attachmentOpenStates ="
        )

        assertContains(listCallsite, "onRetryLocalSend =")
        assertContains(listCallsite, "onCopyLocalSendFailureDetails =")
        assertTrue(listCallsite.indexOf("retryLocalSend") != listCallsite.indexOf("copyLocalSendFailureDetails"))
    }

    @Test
    fun everyFailedMessageVariantUsesTheSameCardLevelStatusPath() {
        val source = threadPageSource()
        val card = block(
            source,
            "private fun ThreadMessageCard(",
            "private fun ThreadMessageSelectionToggle("
        )

        assertContains(card, "if (hasThreadAttachments)")
        assertContains(card, "if (message.isFailedLocalSend())")
        assertContains(card, "ThreadMessageLocalSendStatus(")
        assertTrue(
            card.indexOf("if (message.isFailedLocalSend())") > card.indexOf("if (hasThreadAttachments)"),
            "Failed status must be attached to the shared message card after optional attachment content"
        )
        assertTrue(
            source.windowed("ThreadMessageLocalSendStatus(".length)
                .count { it == "ThreadMessageLocalSendStatus(" } == 2,
            "Expected one shared failed-status callsite plus its declaration"
        )
    }

    private fun block(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, start + startMarker.length)
        assertTrue(start >= 0, "Missing production start marker: $startMarker")
        assertTrue(end > start, "Missing production end marker after $startMarker: $endMarker")
        return source.substring(start, end)
    }

    private fun threadPageSource(): String {
        val commonMain =
            listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
                .firstOrNull(File::isDirectory)
                ?: error("Could not locate shared commonMain Kotlin sources")
        val file = File(commonMain, "build/raft/app/ThreadPage.kt")
        assertTrue(file.isFile, "Missing production ThreadPage.kt")
        return file.readText().replace("\r\n", "\n")
    }
}
