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
}
