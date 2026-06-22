package ai.slock.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedThreadComposerDraftStoreTest {
    @Test
    fun sendFailureRestoresDraftTextAndMode() {
        val store = SharedThreadComposerDraftStore()

        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                KuiklyThreadComposerPayload(
                    instanceId = "composer-1",
                    seedText = "hello",
                    seedAsTask = true,
                    syncNonce = 3
                )
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.BeginSend)

        assertTrue(store.state.value.sending)
        assertEquals("", store.state.value.seedText)
        assertEquals(false, store.state.value.seedAsTask)
        assertEquals(4, store.state.value.syncNonce)

        store.dispatch(
            SharedThreadComposerDraftIntent.RestoreAfterFailure(
                seedText = "hello",
                seedAsTask = true,
                failureLabel = "Send failed. Try again."
            )
        )

        assertFalse(store.state.value.sending)
        assertEquals("hello", store.state.value.seedText)
        assertEquals(true, store.state.value.seedAsTask)
        assertEquals("Send failed. Try again.", store.state.value.failureLabel)
        assertEquals(5, store.state.value.syncNonce)
    }

    @Test
    fun uploadedAttachmentsMergeWithRemoveAndRetryState() {
        val store = SharedThreadComposerDraftStore()
        val attachment =
            SharedThreadComposerAttachment(
                id = "file-1",
                fileName = "image.png",
                sizeLabel = "24 KB",
                mimeType = "image/png",
                status = "ready"
            )

        store.dispatch(SharedThreadComposerDraftIntent.AddUploadedAttachments(listOf(attachment)))
        store.dispatch(SharedThreadComposerDraftIntent.RemoveAttachment("file-1"))

        assertEquals(listOf(attachment), store.state.value.uploadedAttachments)
        assertEquals(setOf("file-1"), store.state.value.removedAttachmentIds)

        store.dispatch(SharedThreadComposerDraftIntent.RetryAttachment("file-1"))

        assertEquals(emptySet(), store.state.value.removedAttachmentIds)
        assertEquals(listOf(attachment), store.state.value.uploadedAttachments)
    }

    @Test
    fun uploadedAttachmentsCanReplaceLocalPendingAttachmentIds() {
        val store = SharedThreadComposerDraftStore()
        val uploaded =
            SharedThreadComposerAttachment(
                id = "remote-file-1",
                fileName = "image.png",
                sizeLabel = "24 KB",
                mimeType = "image/png",
                status = "ready"
            )

        store.dispatch(
            SharedThreadComposerDraftIntent.AddUploadedAttachments(
                attachments = listOf(uploaded),
                replacedAttachmentIds = setOf("picked-local-1")
            )
        )

        assertEquals(listOf(uploaded), store.state.value.uploadedAttachments)
        assertEquals(setOf("picked-local-1"), store.state.value.removedAttachmentIds)
    }

    @Test
    fun completeSendReplacesPendingMentionActionsAndSuccessfulNextSendClearsThem() {
        val store = SharedThreadComposerDraftStore()
        val pending =
            KuiklyPendingMentionActionItem(
                resolutionId = "res-1",
                targetType = "user",
                targetHandle = "outside",
                availableActions = listOf("invite")
            )

        store.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        store.dispatch(SharedThreadComposerDraftIntent.CompleteSend(listOf(pending)))

        assertEquals(listOf(pending), store.state.value.pendingMentionActions)
        assertFalse(store.state.value.sending)

        store.dispatch(SharedThreadComposerDraftIntent.BeginSend)

        assertEquals(listOf(pending), store.state.value.pendingMentionActions)
        assertEquals(null, store.state.value.mentionActionNotice)

        store.dispatch(SharedThreadComposerDraftIntent.CompleteSend())

        assertEquals(emptyList(), store.state.value.pendingMentionActions)
        assertEquals(null, store.state.value.mentionActionNotice)
    }

    @Test
    fun failedSendPreservesExistingPendingMentionAction() {
        val store = SharedThreadComposerDraftStore()
        val pending =
            KuiklyPendingMentionActionItem(
                resolutionId = "res-1",
                targetType = "agent",
                targetHandle = "outside",
                availableActions = listOf("notify")
            )

        store.dispatch(SharedThreadComposerDraftIntent.CompleteSend(listOf(pending)))
        store.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        store.dispatch(
            SharedThreadComposerDraftIntent.RestoreAfterFailure(
                seedText = "retry body",
                seedAsTask = false,
                failureLabel = "Send failed. Try again."
            )
        )

        assertEquals(listOf(pending), store.state.value.pendingMentionActions)
        assertEquals("retry body", store.state.value.seedText)
        assertEquals("Send failed. Try again.", store.state.value.failureLabel)
    }

    @Test
    fun inlineSendFailureClearsComposerWithoutRestoringDraft() {
        val store = SharedThreadComposerDraftStore()
        val pending =
            KuiklyPendingMentionActionItem(
                resolutionId = "res-1",
                targetType = "agent",
                targetHandle = "outside",
                availableActions = listOf("notify")
            )
        val attachment =
            SharedThreadComposerAttachment(
                id = "file-1",
                fileName = "image.png",
                sizeLabel = "24 KB",
                mimeType = "image/png",
                status = "ready"
            )

        store.dispatch(SharedThreadComposerDraftIntent.CompleteSend(listOf(pending)))
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "failed body", seedAsTask = true))
        store.dispatch(SharedThreadComposerDraftIntent.AddUploadedAttachments(listOf(attachment)))
        store.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        store.dispatch(SharedThreadComposerDraftIntent.CompleteInlineSendFailure)

        assertFalse(store.state.value.sending)
        assertEquals("", store.state.value.seedText)
        assertEquals(false, store.state.value.seedAsTask)
        assertEquals(null, store.state.value.failureLabel)
        assertEquals(emptyList(), store.state.value.uploadedAttachments)
        assertEquals(listOf(pending), store.state.value.pendingMentionActions)
    }

    @Test
    fun deliveredMentionActionRemovesRowAndFailureKeepsRow() {
        val store = SharedThreadComposerDraftStore()
        val pending =
            KuiklyPendingMentionActionItem(
                resolutionId = "res-1",
                targetType = "agent",
                targetHandle = "outside",
                availableActions = listOf("notify")
            )
        store.dispatch(SharedThreadComposerDraftIntent.CompleteSend(listOf(pending)))

        store.dispatch(SharedThreadComposerDraftIntent.BeginMentionAction("res-1"))
        assertEquals(setOf("res-1"), store.state.value.executingMentionActionIds)

        store.dispatch(
            SharedThreadComposerDraftIntent.FinishMentionAction(
                resolutionId = "res-1",
                notice = "failed",
                delivered = false
            )
        )
        assertEquals(listOf(pending), store.state.value.pendingMentionActions)
        assertEquals("failed", store.state.value.mentionActionNotice)

        store.dispatch(
            SharedThreadComposerDraftIntent.FinishMentionAction(
                resolutionId = "res-1",
                notice = "delivered",
                delivered = true
            )
        )
        assertEquals(emptyList(), store.state.value.pendingMentionActions)
        assertEquals("delivered", store.state.value.mentionActionNotice)
    }
}
