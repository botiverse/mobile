package build.raft.app

import build.raft.app.data.InMemorySlockKeyValueStore
import build.raft.app.data.InboxItem
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedThreadComposerDraftStoreTest {
    @Test
    fun rapidChangesUpdateStateImmediatelyAndPersistOnlyLatestDraft() {
        val storage = CountingSharedPreferenceStore()
        val persistence = SharedThreadComposerDraftPersistence(storage)
        val scheduler = FakeThreadComposerDraftPersistenceScheduler()
        val persistSlice =
            SharedThreadComposerDraftPersistSlice(
                persistence = persistence,
                scheduler = scheduler,
                changeDebounceMillis = 250L
            )
        val draftKey = "send=channel-rapid|parent=_|mention=channel-rapid"
        val store = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))

        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "composer-rapid"),
                draftKey = draftKey
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "a"))
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "ab"))
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "abc"))

        assertEquals("abc", store.state.value.seedText)
        assertEquals(null, persistence.read(draftKey))
        assertEquals(0, storage.writeCount)
        assertTrue(persistSlice.activeDraftRecordsState.value.any { it.key == draftKey })

        scheduler.runPending()

        assertEquals("abc", persistence.read(draftKey)?.text)
        assertEquals(6, storage.writeCount)
        assertEquals(1, scheduler.executedTaskCount)
    }

    @Test
    fun flushPersistsPendingDraftBeforeDebounceDeadline() {
        val storage = CountingSharedPreferenceStore()
        val persistence = SharedThreadComposerDraftPersistence(storage)
        val scheduler = FakeThreadComposerDraftPersistenceScheduler()
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence, scheduler = scheduler)
        val draftKey = "send=channel-blur|parent=_|mention=channel-blur"
        val store = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))

        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "composer-blur"),
                draftKey = draftKey
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "persist on blur"))
        store.dispatch(SharedThreadComposerDraftIntent.Flush)

        assertEquals("persist on blur", persistence.read(draftKey)?.text)
        assertEquals(6, storage.writeCount)

        scheduler.runPending()

        assertEquals(6, storage.writeCount)
        assertEquals(0, scheduler.executedTaskCount)
    }

    @Test
    fun completedSendCancelsDelayedWriteWithoutResurrectingDraft() {
        val storage = CountingSharedPreferenceStore()
        val persistence = SharedThreadComposerDraftPersistence(storage)
        val scheduler = FakeThreadComposerDraftPersistenceScheduler()
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence, scheduler = scheduler)
        val draftKey = "send=channel-send|parent=_|mention=channel-send"
        val store = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))

        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "composer-send"),
                draftKey = draftKey
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "latest message"))
        store.dispatch(SharedThreadComposerDraftIntent.BeginSend)

        assertEquals("latest message", persistence.read(draftKey)?.text)

        store.dispatch(SharedThreadComposerDraftIntent.CompleteSend())
        scheduler.runIncludingCancelled()

        assertEquals(null, persistence.read(draftKey))
        assertFalse(persistSlice.activeDraftRecordsState.value.any { it.key == draftKey })
        assertEquals(1, scheduler.executedTaskCount)
    }

    @Test
    fun differentDraftKeysDebounceIndependently() {
        val storage = CountingSharedPreferenceStore()
        val persistence = SharedThreadComposerDraftPersistence(storage)
        val scheduler = FakeThreadComposerDraftPersistenceScheduler()
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence, scheduler = scheduler)
        val firstKey = "send=channel-one|parent=_|mention=channel-one"
        val secondKey = "send=channel-two|parent=_|mention=channel-two"
        val firstStore = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        val secondStore = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))

        firstStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "composer-one"),
                draftKey = firstKey
            )
        )
        firstStore.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "first"))
        secondStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "composer-two"),
                draftKey = secondKey
            )
        )
        secondStore.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "second"))

        scheduler.runPending()

        assertEquals("first", persistence.read(firstKey)?.text)
        assertEquals("second", persistence.read(secondKey)?.text)
        assertEquals(2, scheduler.executedTaskCount)
    }

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
    fun stagedUploadedAttachmentsRestoreOnceForMatchingComposerKey() {
        val persistence = SharedThreadComposerDraftPersistence(InMemorySlockKeyValueStore())
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val draftKey = "send=channel-1|parent=_|mention=channel-1"
        val attachment =
            SharedThreadComposerAttachment(
                id = "att-1",
                fileName = "diagnostics.txt",
                sizeLabel = "12 KB",
                mimeType = "text/plain",
                status = "ready"
            )

        persistSlice.stageUploadedAttachments(
            draftKey = draftKey,
            attachments = listOf(attachment)
        )

        val otherStore = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        otherStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "other-composer"),
                draftKey = "send=channel-2|parent=_|mention=channel-2"
            )
        )
        assertEquals(emptyList(), otherStore.state.value.uploadedAttachments)

        val matchingStore = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        matchingStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "matching-composer"),
                draftKey = draftKey
            )
        )
        assertEquals(listOf(attachment), matchingStore.state.value.uploadedAttachments)

        val nextStore = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        nextStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "next-composer"),
                draftKey = draftKey
            )
        )
        assertEquals(emptyList(), nextStore.state.value.uploadedAttachments)
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
    fun replyDraftRestoresByParentComposerKey() {
        val persistence = SharedThreadComposerDraftPersistence(InMemorySlockKeyValueStore())
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val draftKey = "send=reply-thread-1|parent=message-1|mention=channel-1"
        val firstStore = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))

        firstStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer =
                    KuiklyThreadComposerPayload(
                        instanceId = "reply-composer-1",
                        parentMessageId = "message-1",
                        streamChannelId = "reply-thread-1"
                    ),
                draftKey = draftKey
            )
        )
        firstStore.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "reply in progress", seedAsTask = false))

        val secondStore = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        secondStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer =
                    KuiklyThreadComposerPayload(
                        instanceId = "reply-composer-2",
                        parentMessageId = "message-1",
                        streamChannelId = "reply-thread-1"
                    ),
                draftKey = draftKey
            )
        )

        assertEquals("reply in progress", secondStore.state.value.seedText)
        assertTrue(persistSlice.activeDraftRecordsState.value.any { it.matchesReplyThread("message-1") })
    }

    @Test
    fun replyDraftRestoreBumpsSyncNonceForSameComposerInstance() {
        val persistence = SharedThreadComposerDraftPersistence(InMemorySlockKeyValueStore())
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val draftKey = "send=reply-thread-1|parent=message-1|mention=channel-1"
        persistence.save(
            key = draftKey,
            text = "reply in progress",
            asTask = false
        )
        val store = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))

        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer =
                    KuiklyThreadComposerPayload(
                        instanceId = "reply-composer",
                        seedText = "",
                        syncNonce = 0,
                        parentMessageId = "message-1",
                        streamChannelId = "reply-thread-1"
                    ),
                draftKey = draftKey
            )
        )

        assertEquals("reply in progress", store.state.value.seedText)
        assertEquals(1, store.state.value.syncNonce)
    }

    @Test
    fun replyDraftRestoresAndMigratesWhenSendTargetChangesForSameParentTuple() {
        val persistence = SharedThreadComposerDraftPersistence(InMemorySlockKeyValueStore())
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val oldDraftKey = "send=reply-thread-old|parent=message-1|mention=channel-1"
        val currentDraftKey = "send=reply-thread-new|parent=message-1|mention=channel-1"
        persistence.save(
            key = oldDraftKey,
            text = "reply in progress",
            asTask = false
        )
        val store = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))

        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer =
                    KuiklyThreadComposerPayload(
                        instanceId = "reply-composer",
                        parentMessageId = "message-1",
                        streamChannelId = "reply-thread-new",
                        mentionChannelId = "channel-1"
                    ),
                draftKey = currentDraftKey
            )
        )

        assertEquals("reply in progress", store.state.value.seedText)
        assertEquals("reply in progress", persistence.read(currentDraftKey)?.text)
        assertEquals(null, persistence.read(oldDraftKey))
        assertTrue(persistSlice.activeDraftRecordsState.value.any { record -> record.key == currentDraftKey })
        assertFalse(persistSlice.activeDraftRecordsState.value.any { record -> record.key == oldDraftKey })
    }

    @Test
    fun replyDraftFallbackRequiresParentChannelMatch() {
        val persistence = SharedThreadComposerDraftPersistence(InMemorySlockKeyValueStore())
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        persistence.save(
            key = "send=reply-thread-old|parent=message-1|mention=channel-1",
            text = "wrong channel draft",
            asTask = false
        )
        val store = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))

        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer =
                    KuiklyThreadComposerPayload(
                        instanceId = "reply-composer",
                        parentMessageId = "message-1",
                        streamChannelId = "reply-thread-new",
                        mentionChannelId = "channel-2"
                    ),
                draftKey = "send=reply-thread-new|parent=message-1|mention=channel-2"
            )
        )

        assertEquals("", store.state.value.seedText)
        assertEquals(0, store.state.value.syncNonce)
    }

    @Test
    fun activeDraftProjectionMarksThreadInboxAndTaskRows() {
        val projection =
            setOf(
                SharedThreadComposerDraftRecord(
                    key = "send=channel-1|parent=_|mention=channel-1",
                    sendChannelId = "channel-1"
                ),
                SharedThreadComposerDraftRecord(
                    key = "send=reply-thread-1|parent=message-1|mention=channel-1",
                    sendChannelId = "reply-thread-1",
                    parentMessageId = "message-1",
                    mentionChannelId = "channel-1"
                )
            ).toSharedThreadComposerDraftProjection()

        val channelItem =
            InboxItem(
                key = "channel-1",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("channel-1"),
                channelName = "channel"
            ).withComposerDraftProjection(projection)
        val threadItem =
            InboxItem(
                key = "thread-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("channel-1"),
                channelName = "channel",
                threadChannelId = ChannelId("reply-thread-1"),
                parentChannelId = ChannelId("channel-1"),
                parentMessageId = MessageId("message-1")
            ).withComposerDraftProjection(projection)
        val task =
            TaskSummary(
                id = TaskId("task-1"),
                title = "Task",
                status = TaskStatus.Todo,
                messageId = MessageId("message-1"),
                channelId = ChannelId("channel-1"),
                updatedAtLabel = "now"
            )

        assertTrue(channelItem.hasDraft)
        assertTrue(threadItem.hasDraft)
        assertTrue(projection.hasDraftForTask(task))
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
    fun deliveredMentionActionShowsAddedStateBeforeRemoval() {
        val store = SharedThreadComposerDraftStore()
        val action =
            KuiklyPendingMentionActionItem(
                resolutionId = "res-1",
                targetType = "user",
                targetHandle = "jane",
                availableActions = listOf("add")
            )

        store.dispatch(SharedThreadComposerDraftIntent.ApplyPendingMentionActions(listOf(action)))
        store.dispatch(SharedThreadComposerDraftIntent.BeginMentionAction("res-1", state = "added"))

        assertEquals(mapOf("res-1" to "added"), store.state.value.executingMentionActionStates)
        assertEquals(listOf(action), store.state.value.pendingMentionActions)

        store.dispatch(
            SharedThreadComposerDraftIntent.FinishMentionAction(
                resolutionId = "res-1",
                notice = "@jane was added to #team",
                delivered = true,
                state = "added"
            )
        )

        assertEquals(listOf(action), store.state.value.pendingMentionActions)
        assertEquals(mapOf("res-1" to "added"), store.state.value.mentionActionStates)
        assertEquals(null, store.state.value.mentionActionNotice)
        assertEquals(emptyMap(), store.state.value.executingMentionActionStates)

        store.dispatch(SharedThreadComposerDraftIntent.MarkMentionActionRemoving("res-1"))
        assertEquals(setOf("res-1"), store.state.value.removingMentionActionIds)

        store.dispatch(SharedThreadComposerDraftIntent.RemoveMentionAction("res-1"))
        assertEquals(emptyList(), store.state.value.pendingMentionActions)
        assertEquals(emptyMap(), store.state.value.mentionActionStates)
        assertEquals(emptySet(), store.state.value.removingMentionActionIds)
    }

    @Test
    fun failedMentionActionKeepsPendingRowAndShowsNotice() {
        val store = SharedThreadComposerDraftStore()
        val action =
            KuiklyPendingMentionActionItem(
                resolutionId = "res-2",
                targetType = "user",
                targetHandle = "jane",
                availableActions = listOf("notify")
            )

        store.dispatch(SharedThreadComposerDraftIntent.ApplyPendingMentionActions(listOf(action)))
        store.dispatch(SharedThreadComposerDraftIntent.BeginMentionAction("res-2", state = "notified"))
        store.dispatch(
            SharedThreadComposerDraftIntent.FinishMentionAction(
                resolutionId = "res-2",
                notice = "That person cannot read this conversation, so they were not notified.",
                delivered = false,
                state = "notified"
            )
        )

        assertEquals(listOf(action), store.state.value.pendingMentionActions)
        assertEquals(emptyMap(), store.state.value.mentionActionStates)
        assertEquals("That person cannot read this conversation, so they were not notified.", store.state.value.mentionActionNotice)
        assertEquals(emptyMap(), store.state.value.executingMentionActionStates)
    }

    @Test
    fun uploadedAttachmentOnlyDraftPersistsActiveRecordAndRestores() {
        val storage = InMemorySlockKeyValueStore()
        val homePersistSlice = SharedThreadComposerDraftPersistSlice(SharedThreadComposerDraftPersistence(storage))
        val threadPersistSlice = SharedThreadComposerDraftPersistSlice(SharedThreadComposerDraftPersistence(storage))
        val draftKey = "send=channel-1|parent=_|mention=channel-1"
        val uploaded =
            SharedThreadComposerAttachment(
                id = "remote-image-1",
                fileName = "photo.png",
                sizeLabel = "32 KB",
                mimeType = "image/png",
                previewUri = "file:///tmp/photo.png",
                status = "ready"
            )
        val firstStore = SharedThreadComposerDraftStore(persistSlices = listOf(threadPersistSlice))

        firstStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "composer-1"),
                draftKey = draftKey
            )
        )
        firstStore.dispatch(SharedThreadComposerDraftIntent.AddUploadedAttachments(listOf(uploaded)))

        assertTrue(homePersistSlice.activeDraftRecordsState.value.any { it.matchesRootConversation("channel-1") })

        val secondStore = SharedThreadComposerDraftStore(persistSlices = listOf(threadPersistSlice))
        secondStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "composer-2"),
                draftKey = draftKey
            )
        )

        assertEquals("", secondStore.state.value.seedText)
        assertFalse(secondStore.state.value.seedAsTask)
        assertEquals(listOf(uploaded), secondStore.state.value.uploadedAttachments)
    }

    @Test
    fun reverseCompletedMixedAttachmentsRestoreInSelectionOrderAfterRestart() {
        val storage = InMemorySlockKeyValueStore()
        val persistence = SharedThreadComposerDraftPersistence(storage)
        val draftKey = "send=channel-1|parent=_|mention=channel-1"
        val localImage = SharedThreadComposerAttachment("picked-image", "image.png", mimeType = "image/png", status = "uploading")
        val localFile = SharedThreadComposerAttachment("picked-file", "notes.pdf", mimeType = "application/pdf", status = "uploading")
        val remoteImage = SharedThreadComposerAttachment("remote-image", "image.png", mimeType = "image/png", status = "ready")
        val remoteFile = SharedThreadComposerAttachment("remote-file", "notes.pdf", mimeType = "application/pdf", status = "ready")
        val firstStore =
            SharedThreadComposerDraftStore(
                persistSlices = listOf(SharedThreadComposerDraftPersistSlice(persistence))
            )

        firstStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "composer-1"),
                draftKey = draftKey
            )
        )
        firstStore.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(localImage, localFile)))
        firstStore.dispatch(
            SharedThreadComposerDraftIntent.AddUploadedAttachments(
                attachments = listOf(remoteFile),
                replacedAttachmentIdsByUploadedId = mapOf(remoteFile.id to localFile.id)
            )
        )
        firstStore.dispatch(
            SharedThreadComposerDraftIntent.AddUploadedAttachments(
                attachments = listOf(remoteImage),
                replacedAttachmentIdsByUploadedId = mapOf(remoteImage.id to localImage.id)
            )
        )

        val restoredStore =
            SharedThreadComposerDraftStore(
                persistSlices = listOf(SharedThreadComposerDraftPersistSlice(persistence))
            )
        restoredStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "composer-2"),
                draftKey = draftKey
            )
        )

        assertEquals(
            listOf(remoteImage.id, remoteFile.id),
            restoredStore.state.value.uploadedAttachments.map { it.id }
        )
    }

    @Test
    fun removingLastPersistedUploadedAttachmentClearsActiveRecord() {
        val storage = InMemorySlockKeyValueStore()
        val homePersistSlice = SharedThreadComposerDraftPersistSlice(SharedThreadComposerDraftPersistence(storage))
        val threadPersistSlice = SharedThreadComposerDraftPersistSlice(SharedThreadComposerDraftPersistence(storage))
        val draftKey = "send=channel-1|parent=_|mention=channel-1"
        val uploaded =
            SharedThreadComposerAttachment(
                id = "remote-image-1",
                fileName = "photo.png",
                sizeLabel = "32 KB",
                mimeType = "image/png",
                status = "ready"
            )
        val firstStore = SharedThreadComposerDraftStore(persistSlices = listOf(threadPersistSlice))

        firstStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "composer-1"),
                draftKey = draftKey
            )
        )
        firstStore.dispatch(SharedThreadComposerDraftIntent.AddUploadedAttachments(listOf(uploaded)))
        firstStore.dispatch(SharedThreadComposerDraftIntent.RemoveAttachment(uploaded.id))

        assertTrue(homePersistSlice.activeDraftRecordsState.value.isEmpty())

        val secondStore = SharedThreadComposerDraftStore(persistSlices = listOf(threadPersistSlice))
        secondStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "composer-2"),
                draftKey = draftKey
            )
        )

        assertEquals(emptyList(), secondStore.state.value.uploadedAttachments)
    }

    @Test
    fun uploadingLocalAttachmentDoesNotPersistUnrecoverableDraft() {
        val storage = InMemorySlockKeyValueStore()
        val homePersistSlice = SharedThreadComposerDraftPersistSlice(SharedThreadComposerDraftPersistence(storage))
        val threadPersistSlice = SharedThreadComposerDraftPersistSlice(SharedThreadComposerDraftPersistence(storage))
        val draftKey = "send=channel-1|parent=_|mention=channel-1"
        val local =
            SharedThreadComposerAttachment(
                id = "picked-local-1",
                fileName = "photo.png",
                sizeLabel = "32 KB",
                mimeType = "image/png",
                status = "uploading"
            )
        val firstStore = SharedThreadComposerDraftStore(persistSlices = listOf(threadPersistSlice))

        firstStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "composer-1"),
                draftKey = draftKey
            )
        )
        firstStore.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(local)))

        assertTrue(homePersistSlice.activeDraftRecordsState.value.isEmpty())

        val secondStore = SharedThreadComposerDraftStore(persistSlices = listOf(threadPersistSlice))
        secondStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "composer-2"),
                draftKey = draftKey
            )
        )

        assertEquals(emptyList(), secondStore.state.value.uploadedAttachments)
    }

    @Test
    fun uploadingLocalAttachmentSurvivesSameComposerKeyRestart() {
        val persistence = SharedThreadComposerDraftPersistence(InMemorySlockKeyValueStore())
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val draftKey = "send=channel-1|parent=_|mention=channel-1"
        val local =
            SharedThreadComposerAttachment(
                id = "picked-local-1",
                fileName = "photo.png",
                sizeLabel = "32 KB",
                mimeType = "image/png",
                previewUri = "content://picker/photo.png",
                status = "uploading"
            )
        val store = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))

        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "composer-1"),
                draftKey = draftKey
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(local)))
        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "composer-1", syncNonce = 0),
                draftKey = draftKey
            )
        )

        assertEquals(listOf(local), store.state.value.uploadedAttachments)
        assertEquals(1, store.state.value.syncNonce)
        assertEquals(null, persistence.read(draftKey))
    }

    @Test
    fun removedLocalAttachmentDoesNotReappearAfterSameComposerKeyRestart() {
        val persistence = SharedThreadComposerDraftPersistence(InMemorySlockKeyValueStore())
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val draftKey = "send=channel-1|parent=_|mention=channel-1"
        val local =
            SharedThreadComposerAttachment(
                id = "picked-local-1",
                fileName = "photo.png",
                sizeLabel = "32 KB",
                mimeType = "image/png",
                status = "uploading"
            )
        val uploaded =
            SharedThreadComposerAttachment(
                id = "remote-image-1",
                fileName = "photo.png",
                sizeLabel = "32 KB",
                mimeType = "image/png",
                status = "ready"
            )
        val store = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))

        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "composer-1"),
                draftKey = draftKey
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(local)))
        store.dispatch(SharedThreadComposerDraftIntent.RemoveAttachment(local.id))
        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "composer-1", syncNonce = 0),
                draftKey = draftKey
            )
        )
        store.dispatch(
            SharedThreadComposerDraftIntent.AddUploadedAttachments(
                attachments = listOf(uploaded),
                replacedAttachmentIds = setOf(local.id),
                replacedAttachmentIdsByUploadedId = mapOf(uploaded.id to local.id)
            )
        )

        assertEquals(emptyList(), store.state.value.uploadedAttachments)
        assertTrue(local.id in store.state.value.removedAttachmentIds)
    }

    @Test
    fun uploadingLocalAttachmentDoesNotSurviveDifferentComposerKeyRestart() {
        val persistence = SharedThreadComposerDraftPersistence(InMemorySlockKeyValueStore())
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val firstDraftKey = "send=channel-1|parent=_|mention=channel-1"
        val secondDraftKey = "send=channel-2|parent=_|mention=channel-2"
        val local =
            SharedThreadComposerAttachment(
                id = "picked-local-1",
                fileName = "photo.png",
                sizeLabel = "32 KB",
                mimeType = "image/png",
                status = "uploading"
            )
        val store = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))

        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "composer-1"),
                draftKey = firstDraftKey
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(local)))
        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "composer-2"),
                draftKey = secondDraftKey
            )
        )

        assertEquals(emptyList(), store.state.value.uploadedAttachments)
        assertEquals(emptySet(), store.state.value.removedAttachmentIds)
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
        assertEquals(1, store.state.value.focusRequestNonce)

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
        assertEquals(1, store.state.value.focusRequestNonce)
    }

    @Test
    fun sendRequestsFocusSynchronouslyButAsyncCompletionDoesNotReviveIt() {
        val store = SharedThreadComposerDraftStore()

        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                KuiklyThreadComposerPayload(
                    instanceId = "composer-send-focus",
                    seedText = "first",
                    focusRequestNonce = 7
                )
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.BeginSend)

        assertEquals(8, store.state.value.focusRequestNonce)

        store.dispatch(SharedThreadComposerDraftIntent.CompleteSend())

        // Completion can race with a later outside tap. Only the synchronous send action owns
        // focus retention; an async terminal callback must never reopen the keyboard.
        assertEquals(8, store.state.value.focusRequestNonce)
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
                replacedAttachmentIds = setOf("picked-local-1"),
                replacedAttachmentIdsByUploadedId = mapOf("remote-file-1" to "picked-local-1")
            )
        )

        assertEquals(listOf(uploaded), store.state.value.uploadedAttachments)
        assertEquals(setOf("picked-local-1"), store.state.value.removedAttachmentIds)
    }

    @Test
    fun reverseUploadCompletionPreservesSelectionOrderForVisibleAndSendAttachments() {
        val store = SharedThreadComposerDraftStore()
        val localA = SharedThreadComposerAttachment("picked-a", "a.png", mimeType = "image/png", status = "uploading")
        val localB = SharedThreadComposerAttachment("picked-b", "b.pdf", mimeType = "application/pdf", status = "uploading")
        val remoteA = SharedThreadComposerAttachment("remote-a", "a.png", mimeType = "image/png", status = "ready")
        val remoteB = SharedThreadComposerAttachment("remote-b", "b.pdf", mimeType = "application/pdf", status = "ready")

        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(localA, localB)))
        store.dispatch(
            SharedThreadComposerDraftIntent.AddUploadedAttachments(
                attachments = listOf(remoteB),
                replacedAttachmentIdsByUploadedId = mapOf(remoteB.id to localB.id)
            )
        )
        val partiallyReady =
            store.state.value.uploadedAttachments.map { attachment ->
                KuiklyPendingAttachmentItem(
                    id = attachment.id,
                    fileName = attachment.fileName,
                    status = attachment.status
                )
            }
        assertTrue(partiallyReady.hasBlockingThreadAttachmentUpload())
        assertEquals(listOf(remoteB.id), partiallyReady.readyThreadAttachmentIdsForSend())
        store.dispatch(
            SharedThreadComposerDraftIntent.AddUploadedAttachments(
                attachments = listOf(remoteA),
                replacedAttachmentIdsByUploadedId = mapOf(remoteA.id to localA.id)
            )
        )

        assertEquals(listOf(remoteA.id, remoteB.id), store.state.value.uploadedAttachments.map { it.id })
        assertEquals(
            listOf(remoteA.id, remoteB.id),
            store.state.value.uploadedAttachments
                .map { KuiklyPendingAttachmentItem(id = it.id, fileName = it.fileName, status = it.status) }
                .readyThreadAttachmentIdsForSend()
        )
    }

    @Test
    fun retryAndMultiResultCompletionStayInsideTheirOriginalSelectionSlots() {
        val store = SharedThreadComposerDraftStore()
        val localA = SharedThreadComposerAttachment("picked-a", "a.zip", status = "uploading")
        val localB = SharedThreadComposerAttachment("picked-b", "b.png", mimeType = "image/png", status = "uploading")
        val remoteA1 = SharedThreadComposerAttachment("remote-a-1", "a-1.zip", status = "ready")
        val remoteA2 = SharedThreadComposerAttachment("remote-a-2", "a-2.zip", status = "ready")
        val remoteB = SharedThreadComposerAttachment("remote-b", "b.png", mimeType = "image/png", status = "ready")

        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(localA, localB)))
        store.dispatch(SharedThreadComposerDraftIntent.MarkAttachmentsFailed(setOf(localA.id), "timeout"))
        store.dispatch(SharedThreadComposerDraftIntent.RetryAttachment(localA.id))
        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(localA)))
        assertEquals(listOf(localA.id, localB.id), store.state.value.uploadedAttachments.map { it.id })

        store.dispatch(
            SharedThreadComposerDraftIntent.AddUploadedAttachments(
                attachments = listOf(remoteB),
                replacedAttachmentIdsByUploadedId = mapOf(remoteB.id to localB.id)
            )
        )
        store.dispatch(
            SharedThreadComposerDraftIntent.AddUploadedAttachments(
                attachments = listOf(remoteA1, remoteA2),
                replacedAttachmentIdsByUploadedId =
                    mapOf(
                        remoteA1.id to localA.id,
                        remoteA2.id to localA.id
                    )
            )
        )

        assertEquals(
            listOf(remoteA1.id, remoteA2.id, remoteB.id),
            store.state.value.uploadedAttachments.map { it.id }
        )
    }

    @Test
    fun annotatedImageReplacementKeepsTheOriginalRemoteSlot() {
        val store = SharedThreadComposerDraftStore()
        val remoteImage = SharedThreadComposerAttachment("remote-image", "image.png", mimeType = "image/png", status = "ready")
        val remoteFile = SharedThreadComposerAttachment("remote-file", "notes.pdf", mimeType = "application/pdf", status = "ready")
        val editedLocal = SharedThreadComposerAttachment("picked-edited", "edited.png", mimeType = "image/png", status = "uploading")
        val editedRemote = SharedThreadComposerAttachment("remote-edited", "edited.png", mimeType = "image/png", status = "ready")
        val replacementSlotId =
            threadComposerReplacementSlotId(
                replaceAttachmentId = remoteImage.id,
                localAttachmentId = editedLocal.id
            )

        store.dispatch(SharedThreadComposerDraftIntent.AddUploadedAttachments(listOf(remoteImage, remoteFile)))
        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(editedLocal)))
        store.dispatch(
            SharedThreadComposerDraftIntent.AddUploadedAttachments(
                attachments = listOf(editedRemote),
                replacedAttachmentIds = setOf(editedLocal.id),
                replacedAttachmentIdsByUploadedId = mapOf(editedRemote.id to replacementSlotId)
            )
        )

        assertEquals(
            listOf(editedRemote.id, remoteFile.id),
            store.state.value.uploadedAttachments
                .filterNot { attachment -> attachment.id in store.state.value.removedAttachmentIds }
                .map { it.id }
        )
        assertEquals(setOf(editedLocal.id, remoteImage.id), store.state.value.removedAttachmentIds)
        assertEquals(editedLocal.id, threadComposerReplacementSlotId("", editedLocal.id))
    }

    @Test
    fun removedSlotNeverRevivesAndNewSelectionAppendsAfterSurvivingSibling() {
        val store = SharedThreadComposerDraftStore()
        val localA = SharedThreadComposerAttachment("picked-a", "a.png", status = "uploading")
        val localB = SharedThreadComposerAttachment("picked-b", "b.png", status = "uploading")
        val localC = SharedThreadComposerAttachment("picked-c", "c.png", status = "uploading")
        val remoteA = SharedThreadComposerAttachment("remote-a", "a.png", status = "ready")
        val remoteB = SharedThreadComposerAttachment("remote-b", "b.png", status = "ready")
        val remoteC = SharedThreadComposerAttachment("remote-c", "c.png", status = "ready")

        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(localA, localB)))
        store.dispatch(SharedThreadComposerDraftIntent.RemoveAttachment(localA.id))
        store.dispatch(
            SharedThreadComposerDraftIntent.AddUploadedAttachments(
                attachments = listOf(remoteB),
                replacedAttachmentIdsByUploadedId = mapOf(remoteB.id to localB.id)
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(localC)))
        store.dispatch(
            SharedThreadComposerDraftIntent.AddUploadedAttachments(
                attachments = listOf(remoteA),
                replacedAttachmentIdsByUploadedId = mapOf(remoteA.id to localA.id)
            )
        )
        store.dispatch(
            SharedThreadComposerDraftIntent.AddUploadedAttachments(
                attachments = listOf(remoteC),
                replacedAttachmentIdsByUploadedId = mapOf(remoteC.id to localC.id)
            )
        )

        assertEquals(
            listOf(remoteB.id, remoteC.id),
            store.state.value.uploadedAttachments
                .filterNot { attachment -> attachment.id in store.state.value.removedAttachmentIds }
                .map { it.id }
        )
        assertTrue(store.state.value.uploadedAttachments.none { it.id == remoteA.id })
    }

    @Test
    fun uploadedFileDoesNotBlockNextPickedImageAttachment() {
        val store = SharedThreadComposerDraftStore()
        val localFile =
            SharedThreadComposerAttachment(
                id = "picked-file-1",
                fileName = "notes.pdf",
                sizeLabel = "24 KB",
                mimeType = "application/pdf",
                status = "uploading"
            )
        val uploadedFile =
            SharedThreadComposerAttachment(
                id = "remote-file-1",
                fileName = "notes.pdf",
                sizeLabel = "24 KB",
                mimeType = "application/pdf",
                status = "ready"
            )
        val localImage =
            SharedThreadComposerAttachment(
                id = "picked-image-1",
                fileName = "photo.png",
                sizeLabel = "32 KB",
                mimeType = "image/png",
                status = "uploading"
            )
        val uploadedImage =
            SharedThreadComposerAttachment(
                id = "remote-image-1",
                fileName = "photo.png",
                sizeLabel = "32 KB",
                mimeType = "image/png",
                status = "ready"
            )

        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(localFile)))
        store.dispatch(
            SharedThreadComposerDraftIntent.AddUploadedAttachments(
                attachments = listOf(uploadedFile),
                replacedAttachmentIds = setOf(localFile.id),
                replacedAttachmentIdsByUploadedId = mapOf(uploadedFile.id to localFile.id)
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(localImage)))
        store.dispatch(
            SharedThreadComposerDraftIntent.AddUploadedAttachments(
                attachments = listOf(uploadedImage),
                replacedAttachmentIds = setOf(localImage.id),
                replacedAttachmentIdsByUploadedId = mapOf(uploadedImage.id to localImage.id)
            )
        )

        assertEquals(
            listOf(uploadedFile, uploadedImage),
            store.state.value.uploadedAttachments
        )
        assertEquals(
            setOf(localFile.id, localImage.id),
            store.state.value.removedAttachmentIds
        )
    }

    @Test
    fun removedLocalPendingAttachmentDoesNotReturnWhenUploadCompletes() {
        val store = SharedThreadComposerDraftStore()
        val localRemoved =
            SharedThreadComposerAttachment(
                id = "picked-local-1",
                fileName = "removed.png",
                sizeLabel = "24 KB",
                mimeType = "image/png",
                status = "uploading"
            )
        val localKept =
            SharedThreadComposerAttachment(
                id = "picked-local-2",
                fileName = "kept.png",
                sizeLabel = "32 KB",
                mimeType = "image/png",
                status = "uploading"
            )
        val uploadedRemoved =
            SharedThreadComposerAttachment(
                id = "remote-file-1",
                fileName = "removed.png",
                sizeLabel = "24 KB",
                mimeType = "image/png",
                status = "ready"
            )
        val uploadedKept =
            SharedThreadComposerAttachment(
                id = "remote-file-2",
                fileName = "kept.png",
                sizeLabel = "32 KB",
                mimeType = "image/png",
                status = "ready"
            )

        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(localRemoved, localKept)))
        store.dispatch(SharedThreadComposerDraftIntent.RemoveAttachment("picked-local-1"))
        store.dispatch(
            SharedThreadComposerDraftIntent.AddUploadedAttachments(
                attachments = listOf(uploadedRemoved, uploadedKept),
                replacedAttachmentIds = setOf("picked-local-1", "picked-local-2"),
                replacedAttachmentIdsByUploadedId =
                    mapOf(
                        "remote-file-1" to "picked-local-1",
                        "remote-file-2" to "picked-local-2"
                    )
            )
        )

        assertEquals(
            listOf(localRemoved, uploadedKept),
            store.state.value.uploadedAttachments
        )
        assertEquals(
            setOf("picked-local-1", "picked-local-2"),
            store.state.value.removedAttachmentIds
        )
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
    fun retryingFailedLocalAttachmentReplacesItWithUploadingState() {
        val store = SharedThreadComposerDraftStore()
        val failed =
            SharedThreadComposerAttachment(
                id = "picked-local-1",
                fileName = "doc.pdf",
                sizeLabel = "82 KB",
                mimeType = "application/pdf",
                status = "failed",
                errorCode = "network_error"
            )
        val retrying =
            failed.copy(status = "uploading", errorCode = null)

        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(failed)))
        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(retrying)))

        assertEquals(listOf(retrying), store.state.value.uploadedAttachments)
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
    fun deliveredMentionActionMarksRowAndFailureKeepsRow() {
        val store = SharedThreadComposerDraftStore()
        val pending =
            KuiklyPendingMentionActionItem(
                resolutionId = "res-1",
                targetType = "agent",
                targetHandle = "outside",
                availableActions = listOf("notify")
            )
        store.dispatch(SharedThreadComposerDraftIntent.CompleteSend(listOf(pending)))

        store.dispatch(SharedThreadComposerDraftIntent.BeginMentionAction("res-1", state = "notified"))
        assertEquals(setOf("res-1"), store.state.value.executingMentionActionIds)

        store.dispatch(
            SharedThreadComposerDraftIntent.FinishMentionAction(
                resolutionId = "res-1",
                notice = "failed",
                delivered = false,
                state = "notified"
            )
        )
        assertEquals(listOf(pending), store.state.value.pendingMentionActions)
        assertEquals("failed", store.state.value.mentionActionNotice)

        store.dispatch(SharedThreadComposerDraftIntent.BeginMentionAction("res-1", state = "notified"))
        store.dispatch(
            SharedThreadComposerDraftIntent.FinishMentionAction(
                resolutionId = "res-1",
                notice = "delivered",
                delivered = true,
                state = "notified"
            )
        )
        assertEquals(listOf(pending), store.state.value.pendingMentionActions)
        assertEquals(mapOf("res-1" to "notified"), store.state.value.mentionActionStates)
        assertEquals(null, store.state.value.mentionActionNotice)

        store.dispatch(SharedThreadComposerDraftIntent.RemoveMentionAction("res-1"))
        assertEquals(emptyList(), store.state.value.pendingMentionActions)
    }

    @Test
    fun clearingMentionActionNoticeDoesNotRemovePendingAction() {
        val store = SharedThreadComposerDraftStore()
        val pending =
            KuiklyPendingMentionActionItem(
                resolutionId = "res-1",
                targetType = "agent",
                targetHandle = "outside",
                availableActions = listOf("invite")
            )
        store.dispatch(SharedThreadComposerDraftIntent.CompleteSend(listOf(pending)))
        store.dispatch(
            SharedThreadComposerDraftIntent.FinishMentionAction(
                resolutionId = "res-1",
                notice = "failed",
                delivered = false,
                state = "added"
            )
        )

        store.dispatch(SharedThreadComposerDraftIntent.ClearMentionActionNotice)

        assertEquals(listOf(pending), store.state.value.pendingMentionActions)
        assertEquals(null, store.state.value.mentionActionNotice)
    }

    @Test
    fun externalShareStagesDurableQueuedDraftIntoCanonicalRootComposer() {
        val persistence = SharedThreadComposerDraftPersistence(InMemorySlockKeyValueStore())
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val draftKey = "send=channel-share|parent=_|mention=channel-share"
        val source = SharedThreadComposerExternalShareSource("token", "account", "server")
        val attachment = externalShareAttachment(status = "queued")

        assertEquals(
            ExternalShareComposerDraftStageResult.Staged,
            persistSlice.stageExternalShareDraft(
                draftKey = draftKey,
                text = "Subject\n\nBody",
                attachments = listOf(attachment),
                source = source
            )
        )

        val restoredStore = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        restoredStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "composer-share"),
                draftKey = draftKey
            )
        )
        assertEquals("Subject\n\nBody", restoredStore.state.value.seedText)
        assertEquals(listOf(attachment), restoredStore.state.value.uploadedAttachments)
        assertEquals(listOf(source), restoredStore.state.value.externalShareSources)
        assertTrue(persistSlice.activeDraftRecordsState.value.any { it.key == draftKey })
    }

    @Test
    fun interruptedExternalShareUploadRestoresAsQueuedAfterProcessDeath() {
        val persistence = SharedThreadComposerDraftPersistence(InMemorySlockKeyValueStore())
        val draftKey = "send=channel-share|parent=_|mention=channel-share"
        persistence.save(
            key = draftKey,
            text = "Body",
            asTask = false,
            uploadedAttachments = listOf(externalShareAttachment(status = "uploading")),
            externalShareSources = listOf(SharedThreadComposerExternalShareSource("token", "account", "server"))
        )

        val restored = persistence.read(draftKey) ?: error("expected draft")

        assertEquals("queued", restored.uploadedAttachments.single().status)
        assertEquals(0L, restored.uploadedAttachments.single().uploadBytesTransferred)
        assertEquals("private://image", restored.uploadedAttachments.single().fileRef)
    }

    @Test
    fun uploadProgressIsMonotonicClampedAndIgnoredAfterTerminalReplacement() {
        val store = SharedThreadComposerDraftStore()
        store.dispatch(
            SharedThreadComposerDraftIntent.AddLocalAttachments(
                listOf(externalShareAttachment(status = "uploading"))
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.UpdateAttachmentUploadProgress("local", 70L, 100L))
        store.dispatch(SharedThreadComposerDraftIntent.UpdateAttachmentUploadProgress("local", 30L, 100L))
        store.dispatch(SharedThreadComposerDraftIntent.UpdateAttachmentUploadProgress("local", 150L, 100L))
        assertEquals(100L, store.state.value.uploadedAttachments.single().uploadBytesTransferred)
        assertEquals(100, store.state.value.uploadedAttachments.single().threadComposerUploadProgressPercent())

        store.dispatch(
            SharedThreadComposerDraftIntent.AddUploadedAttachments(
                attachments = listOf(SharedThreadComposerAttachment("remote", "image.jpg")),
                replacedAttachmentIds = setOf("local"),
                replacedAttachmentIdsByUploadedId = mapOf("remote" to "local")
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.UpdateAttachmentUploadProgress("local", 99L, 100L))
        assertEquals(listOf("remote"), store.state.value.uploadedAttachments.map { it.id })
    }

    @Test
    fun externalShareStagingRejectsReplyComposerAndUnboundFileReference() {
        val persistSlice =
            SharedThreadComposerDraftPersistSlice(
                SharedThreadComposerDraftPersistence(InMemorySlockKeyValueStore())
            )
        val source = SharedThreadComposerExternalShareSource("token", "account", "server")
        val reply =
            persistSlice.stageExternalShareDraft(
                draftKey = "send=channel|parent=message|mention=channel",
                text = "Body",
                attachments = emptyList(),
                source = source
            )
        val invalidAttachment =
            persistSlice.stageExternalShareDraft(
                draftKey = "send=channel|parent=_|mention=channel",
                text = "Body",
                attachments = listOf(externalShareAttachment(status = "queued").copy(fileRef = null)),
                source = source
            )

        assertEquals(
            "external_share_reply_composer_not_supported",
            (reply as ExternalShareComposerDraftStageResult.Rejected).code
        )
        assertEquals(
            "external_share_attachment_invalid",
            (invalidAttachment as ExternalShareComposerDraftStageResult.Rejected).code
        )
    }

    @Test
    fun queuedExternalShareAttachmentWaitsForExplicitSendWithoutPretendingUploadStarted() {
        val queued =
            KuiklyPendingAttachmentItem(
                id = "local",
                fileName = "image.jpg",
                status = "queued",
                previewUri = "private://image",
                source = "external_share"
            )

        assertFalse(listOf(queued).hasUploadingThreadAttachment())
        assertTrue(listOf(queued).hasBlockingThreadAttachmentUpload())
        assertFalse(queued.isReadyThreadAttachmentForSend())
        assertTrue(queued.canOpenComposerAttachmentPreview(composerEnabled = true))
    }

    private fun externalShareAttachment(status: String) =
        SharedThreadComposerAttachment(
            id = "local",
            fileName = "image.jpg",
            sizeBytes = 100L,
            mimeType = "image/jpeg",
            status = status,
            fileRef = "private://image",
            source = "external_share",
            externalShareToken = "token",
            externalShareAccountId = "account",
            externalShareServerId = "server",
            uploadBytesTotal = 100L
        )

    @Test
    fun internalForwardDraftRestoresAndClearsOnlyAfterExplicitSuccessfulSend() {
        val persistence = SharedThreadComposerDraftPersistence(CountingSharedPreferenceStore())
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val draftKey = "send=destination|parent=_|mention=destination"
        val source =
            SharedThreadComposerInternalForwardSource(
                operationId = "forward-1",
                accountId = "account-a",
                serverId = "server-a",
                sourceChannelId = "source",
                destinationChannelId = "destination",
                destinationKind = "Channel",
                messageIds = listOf("message-1"),
                mode = "merged",
                sourceLabel = "Source channel",
                attachmentCount = 2
            )

        assertEquals(
            InternalForwardComposerDraftStageResult.Staged,
            persistSlice.stageInternalForwardDraft(draftKey, source)
        )
        val restored = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        restored.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "destination-composer"),
                draftKey = draftKey
            )
        )
        assertEquals(source, restored.state.value.internalForwardSource)
        assertEquals("", restored.state.value.seedText)

        restored.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "Optional note"))
        restored.dispatch(SharedThreadComposerDraftIntent.BeginInternalForwardSend)
        restored.dispatch(
            SharedThreadComposerDraftIntent.FinishInternalForwardSend(
                succeeded = false,
                failureLabel = "network_failed"
            )
        )
        assertEquals(source, restored.state.value.internalForwardSource)
        assertEquals("Optional note", restored.state.value.seedText)

        restored.dispatch(SharedThreadComposerDraftIntent.BeginInternalForwardSend)
        restored.dispatch(SharedThreadComposerDraftIntent.FinishInternalForwardSend(succeeded = true))
        assertEquals(null, restored.state.value.internalForwardSource)
        assertEquals(null, persistence.read(draftKey))
    }

    @Test
    fun internalForwardPolicyExhaustivelyClassifiesEveryPersistedDraftDimension() {
        val policies =
            enumValues<SharedThreadComposerPersistedDraftDimension>().associateWith(::internalForwardDraftMergePolicy)

        assertEquals(
            mapOf(
                SharedThreadComposerPersistedDraftDimension.Text to
                    InternalForwardDraftMergePolicy.PreserveAsUserNote,
                SharedThreadComposerPersistedDraftDimension.AsTask to
                    InternalForwardDraftMergePolicy.RejectDestinationDraft,
                SharedThreadComposerPersistedDraftDimension.UploadedAttachments to
                    InternalForwardDraftMergePolicy.RejectDestinationDraft,
                SharedThreadComposerPersistedDraftDimension.ExternalShareSources to
                    InternalForwardDraftMergePolicy.RejectDestinationDraft,
                SharedThreadComposerPersistedDraftDimension.InternalForwardSource to
                    InternalForwardDraftMergePolicy.RejectDestinationDraft
            ),
            policies
        )
    }

    @Test
    fun activeDimensionDetectorCoversEveryPersistedDraftDimension() {
        val active =
            SharedThreadComposerPersistedDraft(
                text = "note",
                asTask = true,
                uploadedAttachments =
                    listOf(
                        SharedThreadComposerAttachment(
                            id = "attachment",
                            fileName = "draft.txt",
                            status = "ready"
                        )
                    ),
                externalShareSources =
                    listOf(SharedThreadComposerExternalShareSource("token", "account", "server")),
                internalForwardSource = internalForwardSource(destinationChannelId = "destination")
            ).activeDimensions()

        assertEquals(enumValues<SharedThreadComposerPersistedDraftDimension>().toSet(), active)
    }

    @Test
    fun internalForwardPreservesTextAndRejectsEveryOtherPersistedDraftDimension() {
        val persistence = SharedThreadComposerDraftPersistence(InMemorySlockKeyValueStore())
        val source = internalForwardSource(destinationChannelId = "destination")
        val textKey = "send=destination|parent=_|mention=destination"
        persistence.save(key = textKey, text = "Keep my note", asTask = false)

        assertEquals(
            InternalForwardComposerDraftStageResult.Staged,
            persistence.stageInternalForward(textKey, source)
        )
        assertEquals("Keep my note", persistence.read(textKey)?.text)

        val attachment =
            SharedThreadComposerAttachment(
                id = "attachment-1",
                fileName = "draft.txt",
                sizeBytes = 4L,
                mimeType = "text/plain",
                status = "ready",
                fileRef = "private://draft"
            )
        val externalSource = SharedThreadComposerExternalShareSource("token", "account", "server")
        val rejectedDrafts =
            listOf<(String) -> Unit>(
                { key -> persistence.save(key, text = "", asTask = true) },
                { key -> persistence.save(key, text = "", asTask = false, uploadedAttachments = listOf(attachment)) },
                { key -> persistence.save(key, text = "", asTask = false, externalShareSources = listOf(externalSource)) },
                { key -> persistence.save(key, text = "", asTask = false, internalForwardSource = source) }
            )

        rejectedDrafts.forEachIndexed { index, seedDraft ->
            val key = "send=destination|parent=_|mention=destination-$index"
            seedDraft(key)
            assertEquals(
                InternalForwardComposerDraftStageResult.Rejected("internal_forward_destination_draft_conflict"),
                persistence.stageInternalForward(key, source),
                "persisted dimension index $index must fail closed"
            )
        }
    }

    private fun internalForwardSource(destinationChannelId: String) =
        SharedThreadComposerInternalForwardSource(
            operationId = "forward-policy",
            accountId = "account",
            serverId = "server",
            sourceChannelId = "source",
            destinationChannelId = destinationChannelId,
            destinationKind = "Channel",
            messageIds = listOf("message"),
            mode = "merged",
            sourceLabel = "Source"
        )
}

private class CountingSharedPreferenceStore : build.raft.app.data.SharedPreferenceStore {
    private val values = mutableMapOf<String, String>()
    var writeCount: Int = 0
        private set

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String?) {
        writeCount += 1
        if (value == null) {
            values.remove(key)
        } else {
            values[key] = value
        }
    }
}

private class FakeThreadComposerDraftPersistenceScheduler : SharedThreadComposerDraftPersistenceScheduler {
    private data class ScheduledTask(
        val block: () -> Unit,
        var cancelled: Boolean = false
    )

    private val tasks = mutableListOf<ScheduledTask>()
    var executedTaskCount: Int = 0
        private set

    override fun schedule(
        delayMillis: Long,
        block: () -> Unit
    ): SharedThreadComposerDraftPersistenceCancellation {
        val task = ScheduledTask(block = block)
        tasks += task
        return SharedThreadComposerDraftPersistenceCancellation {
            task.cancelled = true
        }
    }

    fun runPending() {
        runScheduledTasks(includeCancelled = false)
    }

    fun runIncludingCancelled() {
        runScheduledTasks(includeCancelled = true)
    }

    private fun runScheduledTasks(includeCancelled: Boolean) {
        val pending = tasks.toList()
        tasks.clear()
        pending.forEach { task ->
            if (includeCancelled || !task.cancelled) {
                executedTaskCount += 1
                task.block()
            }
        }
    }
}
