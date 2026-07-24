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

    @Test
    fun routeDisposalTerminatesAttemptsBeforeCancellingUploadJobs() {
        val source = threadPageSource()
        val disposal = block(
            source,
            "    DisposableEffect(threadInstanceKey, attachmentUploadOrchestrator) {",
            "    val agentStatusResolver ="
        )
        val orchestrator = attachmentUploadOrchestratorSource()
        val terminal = block(orchestrator, "    private fun terminateAndCancel(", "    }\n}")

        assertContains(disposal, "attachmentUploadOrchestrator.dispose()")
        assertContains(terminal, "fence = fence.terminate(localAttachmentIds)")
        assertContains(terminal, "jobs[localAttachmentId]?.cancel()")
        assertTrue(
            terminal.indexOf("fence.terminate(localAttachmentIds)") <
                terminal.indexOf("jobs[localAttachmentId]?.cancel()"),
            "Upload attempts must become terminal before route disposal cancels their jobs"
        )
        assertFalse("CancelLocalSend" in disposal)
        assertFalse("CompleteInlineSendFailure" in disposal)
    }

    @Test
    fun blockingAttachmentSendExitsBeforeCreatingOrClearingAnything() {
        val source = threadPageSource()
        val send = block(source, "                \"send\" -> {", "                \"dismissMention\" -> {")

        assertContains(send, "hasBlockingAttachmentUpload -> \"attachment_upload_in_progress\"")
        assertContains(send, "return@ThreadPageContent")
        assertTrue(send.indexOf("return@ThreadPageContent") < send.indexOf("ThreadLocalSendNextId.value"))
        assertTrue(send.indexOf("return@ThreadPageContent") < send.indexOf("ApplyLocalSendStarted"))
        assertTrue(send.indexOf("return@ThreadPageContent") < send.indexOf("SharedThreadComposerDraftIntent.BeginSend"))
        assertFalse("ThreadMessageDeliveryWaitingAttachments" in send)
        assertFalse("deferredAttachmentSend" in send)
    }

    @Test
    fun uploadStatusDrivesDisabledSpinningSendButtonAndFailureStopsTheSpinner() {
        val page = threadPageSource()
        val composer = threadComposerPageSource()
        val draftProjection = block(page, "private fun KuiklyThreadComposerPayload.withThreadComposerDraftState(", "@Composable\nprivate fun ThreadDeferredLoadingRouteContent(")
        val sendButton = block(composer, "    val composerEnabled =", "    val composerKeyboardOptions =") +
            block(composer, "                            ComposerSendButton(", "                        }\n                    }\n                }")

        assertContains(draftProjection, "pendingAttachmentItems.hasUploadingThreadAttachment() -> \"uploading\"")
        assertContains(sendButton, "state.composerMode != \"uploading\"")
        assertContains(sendButton, "!state.pendingAttachments.hasFailedThreadAttachmentUpload()")
        assertContains(sendButton, "sending = state.composerMode == \"sending\" || state.composerMode == \"uploading\"")

        val helper = block(composer, "internal fun List<KuiklyPendingAttachmentItem>.hasUploadingThreadAttachment()", "internal fun List<KuiklyPendingAttachmentItem>.hasFailedThreadAttachmentUpload()")
        assertContains(helper, "attachment.status != \"failed\"")
        assertContains(helper, "!attachment.isReadyThreadAttachmentForSend()")

        val attachmentTray = block(composer, "private fun ComposerAttachmentTray(", "private fun ComposerAttachmentPreviewChip(")
        assertContains(attachmentTray, "onRemove = { onEvent(\"removeAttachment\", attachment.id) }")
        assertContains(attachmentTray, "onRetry = { onEvent(\"retryAttachments\", attachment.id) }")
    }

    @Test
    fun readyComposerImageAndFileTilesShareTheSamePreviewDispatch() {
        val composer = threadComposerPageSource()
        val previewChip = block(composer, "private fun ComposerAttachmentPreviewChip(", "private fun ComposerImageAttachmentPreview(")
        val filePreview = block(composer, "private fun ComposerFileAttachmentPreview(", "private fun ComposerAttachmentUploadingOverlay(")

        assertContains(previewChip, "val previewEnabled = attachment.canOpenComposerAttachmentPreview(enabled)")
        assertContains(previewChip, "previewEnabled = previewEnabled")
        assertContains(previewChip, "onPreview = onPreview")
        assertContains(filePreview, ".clickable(enabled = previewEnabled, onClick = onPreview)")
    }

    @Test
    fun failureAndLateCompletionUseOneWayTerminalOrdering() {
        val source = threadPageSource()
        val uploadFlow = block(source, "    fun handleAttachmentUploadFailure(", "    fun openComposerImageAnnotation(")
        val orchestrator = attachmentUploadOrchestratorSource()
        val terminal = block(orchestrator, "    private fun terminateAndCancel(", "    }\n}")

        assertTrue(
            terminal.indexOf("fence.terminate(localAttachmentIds)") <
                terminal.indexOf("jobs[localAttachmentId]?.cancel()"),
            "Failure must terminate tokens before sibling cancellation"
        )
        assertContains(uploadFlow, "attachmentUploadOrchestrator.failActive(attachmentIds)")
        assertContains(uploadFlow, "SharedThreadComposerDraftIntent.MarkAttachmentsFailed")
        assertTrue(
            uploadFlow.indexOf("attachmentUploadOrchestrator.publishIfAccepted(localAttachmentId, uploadAttemptToken)") <
                uploadFlow.indexOf("SharedThreadComposerDraftIntent.AddUploadedAttachments"),
            "Every completion must cross the attempt fence before publishing into Composer"
        )
        assertContains(uploadFlow, "reason=stale_or_terminal")
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

    private fun threadComposerPageSource(): String {
        val commonMain =
            listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
                .firstOrNull(File::isDirectory)
                ?: error("Could not locate shared commonMain Kotlin sources")
        val file = File(commonMain, "build/raft/app/ThreadComposerPage.kt")
        assertTrue(file.isFile, "Missing production ThreadComposerPage.kt")
        return file.readText().replace("\r\n", "\n")
    }

    private fun attachmentUploadOrchestratorSource(): String {
        val commonMain =
            listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
                .firstOrNull(File::isDirectory)
                ?: error("Could not locate shared commonMain Kotlin sources")
        val file = File(commonMain, "build/raft/app/ThreadAttachmentUploadAttemptFence.kt")
        assertTrue(file.isFile, "Missing production ThreadAttachmentUploadAttemptFence.kt")
        return file.readText().replace("\r\n", "\n")
    }
}
