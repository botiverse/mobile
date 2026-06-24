package ai.slock.compose

import ai.slock.compose.data.InMemorySlockKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedThreadComposerDraftStoreTest {
    @Test
    fun persistedDraftRestoresByComposerKey() {
        val persistence = SharedThreadComposerDraftPersistence(InMemorySlockKeyValueStore())
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val firstStore = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        val composer =
            KuiklyThreadComposerPayload(
                instanceId = "composer-1",
                seedText = "",
                seedAsTask = false
            )

        firstStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = composer,
                draftKey = "send=channel-1|parent=_"
            )
        )
        firstStore.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "remember me", seedAsTask = true))

        val secondStore = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        secondStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = composer.copy(seedText = "", seedAsTask = false),
                draftKey = "send=channel-1|parent=_"
            )
        )

        assertEquals("remember me", secondStore.state.value.seedText)
        assertTrue(secondStore.state.value.seedAsTask)
    }

    @Test
    fun insertMentionAppendsTokenFocusesComposerAndPersistsDraft() {
        val persistence = SharedThreadComposerDraftPersistence(InMemorySlockKeyValueStore())
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val draftKey = "send=channel-1|parent=_|mention=channel-1"
        val firstStore = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        val composer =
            KuiklyThreadComposerPayload(
                instanceId = "composer-mention",
                seedText = "hello",
                seedAsTask = true,
                syncNonce = 4,
                focusRequestNonce = 2
            )

        firstStore.dispatch(SharedThreadComposerDraftIntent.Start(composer = composer, draftKey = draftKey))
        firstStore.dispatch(SharedThreadComposerDraftIntent.InsertMention("@Android Developer"))

        assertEquals("hello @android-developer ", firstStore.state.value.seedText)
        assertEquals(true, firstStore.state.value.seedAsTask)
        assertEquals(5, firstStore.state.value.syncNonce)
        assertEquals(3, firstStore.state.value.focusRequestNonce)

        val secondStore = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        secondStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = composer.copy(seedText = "", seedAsTask = false, syncNonce = 0, focusRequestNonce = 0),
                draftKey = draftKey
            )
        )

        assertEquals("hello @android-developer ", secondStore.state.value.seedText)
        assertTrue(secondStore.state.value.seedAsTask)
    }

    @Test
    fun successfulSendClearsPersistedDraftForComposerKey() {
        val persistence = SharedThreadComposerDraftPersistence(InMemorySlockKeyValueStore())
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val draftKey = "send=thread-1|parent=message-1"
        val store = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))

        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "composer-1"),
                draftKey = draftKey
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "send me", seedAsTask = true))
        store.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        store.dispatch(SharedThreadComposerDraftIntent.CompleteSend())

        val nextStore = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        nextStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "composer-2"),
                draftKey = draftKey
            )
        )

        assertEquals("", nextStore.state.value.seedText)
        assertFalse(nextStore.state.value.seedAsTask)
    }

    @Test
    fun activeDraftRecordsTrackRootAndReplyComposerKeys() {
        val persistence = SharedThreadComposerDraftPersistence(InMemorySlockKeyValueStore())
        persistence.save(
            key = "send=channel-1|parent=_|mention=channel-1",
            text = "channel draft",
            asTask = false
        )
        persistence.save(
            key = "send=reply-thread-1|parent=message-1|mention=channel-1",
            text = "reply draft",
            asTask = false
        )

        val records = persistence.activeDraftRecords()

        assertTrue(records.any { it.matchesRootConversation("channel-1") })
        assertTrue(records.any { it.matchesReplyThread("message-1") })
        assertFalse(records.any { it.matchesRootConversation("reply-thread-1") })
    }

    @Test
    fun clearingBlankDraftRemovesActiveDraftRecord() {
        val persistence = SharedThreadComposerDraftPersistence(InMemorySlockKeyValueStore())
        val draftKey = "send=channel-1|parent=_|mention=channel-1"

        persistence.save(draftKey, "channel draft", asTask = false)
        persistence.save(draftKey, "", asTask = false)

        assertTrue(persistence.activeDraftRecords().isEmpty())
    }

    @Test
    fun activeDraftRecordsStateUpdatesAcrossPersistSliceInstances() {
        val storage = InMemorySlockKeyValueStore()
        val homePersistSlice = SharedThreadComposerDraftPersistSlice(SharedThreadComposerDraftPersistence(storage))
        val threadPersistSlice = SharedThreadComposerDraftPersistSlice(SharedThreadComposerDraftPersistence(storage))
        val threadStore = SharedThreadComposerDraftStore(persistSlices = listOf(threadPersistSlice))
        val draftKey = "send=channel-1|parent=_|mention=channel-1"

        assertTrue(homePersistSlice.activeDraftRecordsState.value.isEmpty())

        threadStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "composer-1"),
                draftKey = draftKey
            )
        )
        threadStore.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "channel draft", seedAsTask = false))

        assertTrue(homePersistSlice.activeDraftRecordsState.value.any { it.matchesRootConversation("channel-1") })

        threadStore.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        threadStore.dispatch(SharedThreadComposerDraftIntent.CompleteSend())

        assertTrue(homePersistSlice.activeDraftRecordsState.value.isEmpty())
    }

    @Test
    fun draftPersistenceIsOptInToMviStore() {
        val persistence = SharedThreadComposerDraftPersistence(InMemorySlockKeyValueStore())
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val draftKey = "send=channel-1|parent=_|mention=channel-1"
        val storeWithoutSlice = SharedThreadComposerDraftStore()

        storeWithoutSlice.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "composer-1"),
                draftKey = draftKey
            )
        )
        storeWithoutSlice.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "not persisted", seedAsTask = false))

        assertTrue(persistSlice.activeDraftRecordsState.value.isEmpty())

        val storeWithSlice = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        storeWithSlice.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "composer-2"),
                draftKey = draftKey
            )
        )

        assertEquals("", storeWithSlice.state.value.seedText)
    }

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
        val local =
            SharedThreadComposerAttachment(
                id = "picked-local-1",
                fileName = "image.png",
                sizeLabel = "24 KB",
                mimeType = "image/png",
                status = "uploading"
            )
        val uploaded =
            SharedThreadComposerAttachment(
                id = "remote-file-1",
                fileName = "image.png",
                sizeLabel = "24 KB",
                mimeType = "image/png",
                status = "ready"
            )

        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(local)))
        assertEquals(listOf(local), store.state.value.uploadedAttachments)

        store.dispatch(
            SharedThreadComposerDraftIntent.AddUploadedAttachments(
                attachments = listOf(uploaded),
                replacedAttachmentIds = setOf("picked-local-1")
            )
        )

        assertEquals(listOf(local, uploaded), store.state.value.uploadedAttachments)
        assertEquals(setOf("picked-local-1"), store.state.value.removedAttachmentIds)
    }

    @Test
    fun uploadFailureMarksLocalAttachmentWithReason() {
        val store = SharedThreadComposerDraftStore()
        val local =
            SharedThreadComposerAttachment(
                id = "picked-local-1",
                fileName = "image.png",
                sizeLabel = "24 KB",
                mimeType = "image/png",
                status = "uploading"
            )

        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(local)))
        store.dispatch(
            SharedThreadComposerDraftIntent.MarkAttachmentsFailed(
                attachmentIds = setOf("picked-local-1"),
                errorCode = "payload_too_large"
            )
        )

        val failed = store.state.value.uploadedAttachments.single()
        assertEquals("failed", failed.status)
        assertEquals("payload_too_large", failed.errorCode)
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
    fun sendCompletionDoesNotClearDraftEnteredAfterSubmit() {
        val store = SharedThreadComposerDraftStore()

        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "first"))
        store.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "second", seedAsTask = true))
        store.dispatch(SharedThreadComposerDraftIntent.CompleteSend())

        assertFalse(store.state.value.sending)
        assertEquals("second", store.state.value.seedText)
        assertEquals(true, store.state.value.seedAsTask)
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
