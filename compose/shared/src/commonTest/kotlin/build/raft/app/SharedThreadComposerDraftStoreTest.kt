package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.InboxItem
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedThreadComposerDraftStoreTest {
    @Test
    fun originalImageDefaultsToCompressedAndTogglesWithinSession() {
        val store = SharedThreadComposerDraftStore()
        val composer = RaftThreadComposerPayload(instanceId = "composer-image")

        store.dispatch(SharedThreadComposerDraftIntent.Start(composer = composer, draftKey = "send=channel-image"))
        assertFalse(store.state.value.originalImage)

        store.dispatch(SharedThreadComposerDraftIntent.SetOriginalImage(true))
        assertTrue(store.state.value.originalImage)

        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = composer.copy(seedText = "remounted"),
                draftKey = "send=channel-image"
            )
        )
        assertTrue(store.state.value.originalImage)
    }

    @Test
    fun journalAdmissionClearsPersistedDraftAndFailureDoesNotRestoreIt() {
        // #1134 cell 3 (Argus ruling): once the send is admitted to the pending-send
        // journal, the journal row owns crash protection, so the admission path dispatches
        // BeginSend + CompleteSend immediately. A mid-send process kill must restart with
        // an empty composer (the journaled row renders sending/failed instead), and a
        // later inline failure must not resurrect the draft text.
        val storage = InMemoryRaftKeyValueStore()
        val persistence = SharedThreadComposerDraftPersistence(storage)
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val draftKey = "send=channel-journal|parent=_|mention=channel-journal"
        val store = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))

        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-journal"),
                draftKey = draftKey
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "offline body"))
        store.dispatch(SharedThreadComposerDraftIntent.Flush)
        assertEquals("offline body", persistence.read(draftKey)?.text)

        // Journal-admitted path: editor clears at BeginSend, durable record clears at
        // the admission-time CompleteSend.
        store.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        store.dispatch(SharedThreadComposerDraftIntent.CompleteSend())
        assertEquals(null, persistence.read(draftKey))

        // Simulated process kill + rebuild: the draft must not come back.
        val restarted = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        restarted.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-journal"),
                draftKey = draftKey
            )
        )
        assertEquals("", restarted.state.value.seedText)

        // A later inline failure (network down) leaves the composer and persistence
        // empty — the failed journal row carries the retry affordance.
        restarted.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        restarted.dispatch(SharedThreadComposerDraftIntent.CompleteSend())
        restarted.dispatch(SharedThreadComposerDraftIntent.CompleteInlineSendFailure)
        assertEquals("", restarted.state.value.seedText)
        assertEquals(null, persistence.read(draftKey))
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
                composer = RaftThreadComposerPayload(instanceId = "composer-blur"),
                draftKey = draftKey
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "persist on blur"))
        store.dispatch(SharedThreadComposerDraftIntent.Flush)

        assertEquals("persist on blur", persistence.read(draftKey)?.text)
        // One save: text, asTask, attachments, sources, mentions, editedAt and the active index.
        assertEquals(7, storage.writeCount)

        scheduler.runPending()

        assertEquals(7, storage.writeCount)
        assertEquals(0, scheduler.executedTaskCount)
    }

    @Test
    fun delayedHydrateCannotOverwriteFirstKeystrokeAndLatestOwnerWinsPersistence() {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val draftKey = "send=channel-hydrate|parent=_|mention=channel-hydrate"
        persistence.save(draftKey, text = "n", asTask = false)
        val store = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-hydrate"),
                draftKey = null,
            )
        )
        val owner = ThreadComposerControlledEditorOwner(seedText = "", seedAsTask = false)
        owner.acceptEditorValue(
            owner.editorSessionId,
            androidx.compose.ui.text.input.TextFieldValue(
                text = "ni",
                selection = androidx.compose.ui.text.TextRange(2),
                composition = androidx.compose.ui.text.TextRange(0, 2),
            )
        )

        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-hydrate"),
                draftKey = draftKey,
            )
        )
        val delayedHydrate = requireNotNull(store.state.value.editorCommand)
        assertEquals(ThreadComposerEditorCommandOutcome.Stale, owner.apply(delayedHydrate))

        store.dispatch(
            SharedThreadComposerDraftIntent.Change(
                seedText = owner.value.text,
                seedAsTask = owner.asTask,
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.Flush)

        assertEquals("ni", store.state.value.seedText)
        assertEquals("ni", persistence.read(draftKey)?.text)
        assertEquals(androidx.compose.ui.text.TextRange(0, 2), owner.value.composition)
    }

    @Test
    fun persistedDraftRestoresByComposerKey() {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val firstStore = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        val composer =
            RaftThreadComposerPayload(
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
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val draftKey = "send=channel-1|parent=_|mention=channel-1"
        val firstStore = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        val composer =
            RaftThreadComposerPayload(
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
    fun appendQuoteAddsTheQuoteAfterTheDraftFocusesAndPersists() {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val replyKey = "send=thread-1|parent=message-1|mention=channel-1"
        persistence.save(key = replyKey, text = "earlier draft", asTask = false)

        val store = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "reply-composer"),
                draftKey = replyKey
            )
        )
        val focusBefore = store.state.value.focusRequestNonce
        // Select Text > Quote dispatches the sheet's quote into this page's composer.
        store.dispatch(SharedThreadComposerDraftIntent.AppendQuote("> picked line"))

        assertEquals("earlier draft\n\n> picked line\n\n", store.state.value.seedText)
        assertEquals(ThreadComposerEditorCommandKind.Replace, store.state.value.editorCommand?.kind)
        assertEquals("earlier draft\n\n> picked line\n\n", store.state.value.editorCommand?.text)
        // The composer takes focus so the reader types under the quote.
        assertEquals(focusBefore + 1, store.state.value.focusRequestNonce)
        assertTrue(store.state.value.focusRequestShowsKeyboard)
        assertEquals(ThreadComposerFocusRequestKeyboardMode.RaiseKeyboard, store.state.value.focusRequestMeta.keyboardMode)
        assertEquals(
            build.raft.designsystem.focus.RaftFocusRequestSource.User("append_quote"),
            store.state.value.focusRequestMeta.source,
        )

        // Explicit RequestFocus carries its own reason and raises the keyboard.
        store.dispatch(SharedThreadComposerDraftIntent.RequestFocus)
        assertEquals(focusBefore + 2, store.state.value.focusRequestNonce)
        assertEquals(ThreadComposerFocusRequestKeyboardMode.RaiseKeyboard, store.state.value.focusRequestMeta.keyboardMode)
        assertEquals(
            build.raft.designsystem.focus.RaftFocusRequestSource.User("request_focus"),
            store.state.value.focusRequestMeta.source,
        )

        // Send keeps the editor as it was: a focus request that never raises a put-away keyboard.
        store.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        assertEquals(focusBefore + 3, store.state.value.focusRequestNonce)
        assertFalse(store.state.value.focusRequestShowsKeyboard)
        assertEquals(ThreadComposerFocusRequestKeyboardMode.KeepOpenKeyboardOnly, store.state.value.focusRequestMeta.keyboardMode)
        assertEquals(
            build.raft.designsystem.focus.RaftFocusRequestSource.User("begin_send"),
            store.state.value.focusRequestMeta.source,
        )

        // Deferred system completion (e.g. edited image upload) carries its pre-captured ticket with FocusOnly mode.
        val coordinator = build.raft.designsystem.focus.RaftFocusCoordinator()
        val deferredTicket =
            coordinator.request(
                targetKey = ThreadComposerFocusTargetKey("reply-composer"),
                region = build.raft.designsystem.focus.RaftFocusRegion.Conversation,
                source = build.raft.designsystem.focus.RaftFocusRequestSource.System("image_edit_complete"),
                isText = true,
            )
        store.dispatch(SharedThreadComposerDraftIntent.RequestDeferredFocus(deferredTicket))
        assertEquals(focusBefore + 4, store.state.value.focusRequestNonce)
        assertFalse(store.state.value.focusRequestShowsKeyboard)
        assertEquals(ThreadComposerFocusRequestKeyboardMode.FocusOnly, store.state.value.focusRequestMeta.keyboardMode)
        assertEquals(deferredTicket, store.state.value.focusRequestMeta.deferredTicket)
        assertEquals(deferredTicket.source, store.state.value.focusRequestMeta.source)

        // withRouteIntentFrom preserves focusRequestMeta for whichever side holds the newer nonce.
        val newerMeta = store.state.value.focusRequestMeta
        val existingMeta =
            ThreadComposerFocusRequestMeta(
                source = build.raft.designsystem.focus.RaftFocusRequestSource.User("append_quote"),
                keyboardMode = ThreadComposerFocusRequestKeyboardMode.RaiseKeyboard,
            )
        val basePayload =
            RaftThreadScreenPayload(
                instanceId = "thread-1",
                composer =
                    RaftThreadComposerPayload(
                        instanceId = "reply-composer",
                        focusRequestNonce = 2,
                        focusRequestMeta = existingMeta,
                    ),
            )
        val newerRoutePayload =
            basePayload.copy(
                composer =
                    basePayload.composer.copy(
                        focusRequestNonce = 3,
                        focusRequestMeta = newerMeta,
                    ),
            )
        val olderRoutePayload =
            basePayload.copy(
                composer =
                    basePayload.composer.copy(
                        focusRequestNonce = 2,
                        focusRequestMeta = newerMeta,
                    ),
            )
        assertEquals(newerMeta, basePayload.withRouteIntentFrom(newerRoutePayload).composer.focusRequestMeta)
        assertEquals(existingMeta, basePayload.withRouteIntentFrom(olderRoutePayload).composer.focusRequestMeta)

        val nextStore = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        nextStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "reply-composer-next"),
                draftKey = replyKey
            )
        )
        assertEquals("earlier draft\n\n> picked line\n\n", nextStore.state.value.seedText)
    }

    @Test
    fun replyDraftRestoresByParentComposerKey() {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val draftKey = "send=reply-thread-1|parent=message-1|mention=channel-1"
        val firstStore = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))

        firstStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer =
                    RaftThreadComposerPayload(
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
                    RaftThreadComposerPayload(
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
    fun replyDraftFallbackRequiresParentChannelMatch() {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
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
                    RaftThreadComposerPayload(
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
    fun uploadedAttachmentOnlyDraftPersistsActiveRecordAndRestores() {
        val storage = InMemoryRaftKeyValueStore()
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
                composer = RaftThreadComposerPayload(instanceId = "composer-1"),
                draftKey = draftKey
            )
        )
        firstStore.dispatch(SharedThreadComposerDraftIntent.AddUploadedAttachments(listOf(uploaded)))

        assertTrue(homePersistSlice.activeDraftRecordsState.value.any { it.matchesRootConversation("channel-1") })

        val secondStore = SharedThreadComposerDraftStore(persistSlices = listOf(threadPersistSlice))
        secondStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-2"),
                draftKey = draftKey
            )
        )

        assertEquals("", secondStore.state.value.seedText)
        assertFalse(secondStore.state.value.seedAsTask)
        assertEquals(listOf(uploaded), secondStore.state.value.uploadedAttachments)
    }

    @Test
    fun uploadingLocalAttachmentSurvivesSameComposerKeyRestart() {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
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
                composer = RaftThreadComposerPayload(instanceId = "composer-1"),
                draftKey = draftKey
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(local)))
        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-1", syncNonce = 0),
                draftKey = draftKey
            )
        )

        assertEquals(listOf(local), store.state.value.uploadedAttachments)
        assertEquals(1, store.state.value.syncNonce)
        assertEquals(null, persistence.read(draftKey))
    }

    @Test
    fun removedLocalAttachmentDoesNotReappearAfterSameComposerKeyRestart() {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
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
                composer = RaftThreadComposerPayload(instanceId = "composer-1"),
                draftKey = draftKey
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(local)))
        store.dispatch(SharedThreadComposerDraftIntent.RemoveAttachment(local.id))
        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-1", syncNonce = 0),
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
    fun sendFailureRestoresDraftTextAndMode() {
        val store = SharedThreadComposerDraftStore()

        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                RaftThreadComposerPayload(
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
                RaftPendingAttachmentItem(
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
                .map { RaftPendingAttachmentItem(id = it.id, fileName = it.fileName, status = it.status) }
                .readyThreadAttachmentIdsForSend()
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
    fun completeSendReplacesPendingMentionActionsAndSuccessfulNextSendClearsThem() {
        val store = SharedThreadComposerDraftStore()
        val pending =
            RaftPendingMentionActionItem(
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
            RaftPendingMentionActionItem(
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
    fun externalShareStagesDurableQueuedDraftIntoCanonicalRootComposer() {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
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
                composer = RaftThreadComposerPayload(instanceId = "composer-share"),
                draftKey = draftKey
            )
        )
        assertEquals("Subject\n\nBody", restoredStore.state.value.seedText)
        assertEquals(listOf(attachment), restoredStore.state.value.uploadedAttachments)
        assertEquals(listOf(source), restoredStore.state.value.externalShareSources)
        assertTrue(persistSlice.activeDraftRecordsState.value.any { it.key == draftKey })
    }

    @Test
    fun externalShareStagesDurableQueuedDraftIntoReplyThreadComposerAndClearsOnSend() {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val replyDraftKey = "send=thread-5|parent=parent-5|mention=room-1"
        val source = SharedThreadComposerExternalShareSource("token-reply", "account", "server")
        val attachment =
            externalShareAttachment(status = "queued").copy(
                id = "external-share-token-reply-0",
                externalShareToken = "token-reply"
            )

        assertEquals(
            ExternalShareComposerDraftStageResult.Staged,
            persistSlice.stageExternalShareDraft(
                draftKey = replyDraftKey,
                text = "Shared into thread",
                attachments = listOf(attachment),
                source = source
            )
        )

        val projection = persistSlice.activeDraftRecordsState.value.toSharedThreadComposerDraftProjection()
        assertTrue(projection.hasReplyDraft("parent-5"))
        assertFalse(projection.hasRootDraft("thread-5"))

        val restoredStore = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        restoredStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer =
                    RaftThreadComposerPayload(
                        instanceId = "composer-reply-share",
                        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                        parentMessageId = "parent-5",
                        mentionChannelId = "room-1",
                        streamChannelId = "thread-5"
                    ),
                draftKey = replyDraftKey
            )
        )
        assertEquals("Shared into thread", restoredStore.state.value.seedText)
        assertFalse(restoredStore.state.value.seedAsTask)
        assertEquals(listOf(attachment), restoredStore.state.value.uploadedAttachments)
        assertEquals(listOf(source), restoredStore.state.value.externalShareSources)

        // Removing the queued external-share attachment while clearing text drops the share source and draft.
        restoredStore.dispatch(SharedThreadComposerDraftIntent.RemoveAttachment(attachment.id))
        restoredStore.dispatch(SharedThreadComposerDraftIntent.Change(seedText = ""))
        assertEquals(emptyList(), restoredStore.state.value.externalShareSources)
        assertNull(persistence.read(replyDraftKey))

        // Stage again and verify send clears the reply-thread draft completely.
        assertEquals(
            ExternalShareComposerDraftStageResult.Staged,
            persistSlice.stageExternalShareDraft(
                draftKey = replyDraftKey,
                text = "Ready to send",
                attachments = listOf(attachment),
                source = source
            )
        )
        val sendStore = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        sendStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-reply-send"),
                draftKey = replyDraftKey
            )
        )
        assertEquals("Ready to send", sendStore.state.value.seedText)
        sendStore.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        sendStore.dispatch(SharedThreadComposerDraftIntent.CompleteSend())
        assertNull(persistence.read(replyDraftKey))
        assertFalse(persistSlice.activeDraftRecordsState.value.any { it.key == replyDraftKey })
    }

    @Test
    fun externalShareReplyThreadStagingMergesExistingFallbackReplyDraft() {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val fallbackKey = "send=room-1|parent=parent-5|mention=room-1"
        val canonicalKey = "send=thread-5|parent=parent-5|mention=room-1"
        val existingAttachment =
            SharedThreadComposerAttachment(
                id = "ready-1",
                fileName = "existing.png",
                status = "ready"
            )
        val existingMention =
            build.raft.app.data.ThreadSendMessageMention(
                type = "user",
                id = "user-1",
                name = "alice"
            )
        persistence.save(
            key = fallbackKey,
            text = "Hey @alice",
            asTask = false,
            uploadedAttachments = listOf(existingAttachment),
            structuredMentions = listOf(existingMention)
        )

        val sharedAttachment = externalShareAttachment(status = "queued")
        val source = SharedThreadComposerExternalShareSource("token", "account", "server")
        assertEquals(
            ExternalShareComposerDraftStageResult.Staged,
            persistSlice.stageExternalShareDraft(
                draftKey = canonicalKey,
                text = "Attached link",
                attachments = listOf(sharedAttachment),
                source = source
            )
        )

        assertNull(persistence.read(fallbackKey))
        val merged = persistence.read(canonicalKey) ?: error("expected merged reply draft")
        assertEquals("Hey @alice\n\nAttached link", merged.text)
        assertFalse(merged.asTask)
        assertEquals(listOf(existingAttachment, sharedAttachment), merged.uploadedAttachments)
        assertEquals(listOf(source), merged.externalShareSources)
        assertEquals(listOf(existingMention), merged.structuredMentions)
        assertEquals(setOf(canonicalKey), persistSlice.activeDraftRecordsState.value.map { it.key }.toSet())
    }

    @Test
    fun externalShareStagesDurableQueuedDraftIntoTaskConversationComposer() {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val taskDraftKey = "send=task-conv-7|parent=_|mention=task-conv-7"
        val source = SharedThreadComposerExternalShareSource("token-task", "account", "server")
        val attachment =
            externalShareAttachment(status = "queued").copy(
                id = "external-share-token-task-0",
                externalShareToken = "token-task"
            )

        assertEquals(
            ExternalShareComposerDraftStageResult.Staged,
            persistSlice.stageExternalShareDraft(
                draftKey = taskDraftKey,
                text = "Task context",
                attachments = listOf(attachment),
                source = source
            )
        )

        val restoredStore = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        restoredStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-task-share"),
                draftKey = taskDraftKey
            )
        )
        assertEquals("Task context", restoredStore.state.value.seedText)
        assertEquals(listOf(attachment), restoredStore.state.value.uploadedAttachments)
        assertEquals(listOf(source), restoredStore.state.value.externalShareSources)

        restoredStore.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        restoredStore.dispatch(SharedThreadComposerDraftIntent.CompleteSend())
        assertNull(persistence.read(taskDraftKey))
    }

    @Test
    fun interruptedReplyThreadExternalShareUploadRestoresAsQueuedAfterProcessDeath() {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val replyDraftKey = "send=thread-5|parent=parent-5|mention=room-1"
        persistence.save(
            key = replyDraftKey,
            text = "Reply body",
            asTask = false,
            uploadedAttachments = listOf(externalShareAttachment(status = "uploading")),
            externalShareSources = listOf(SharedThreadComposerExternalShareSource("token", "account", "server"))
        )

        val restored = persistence.read(replyDraftKey) ?: error("expected reply draft")

        assertEquals("queued", restored.uploadedAttachments.single().status)
        assertNull(restored.uploadedAttachments.single().uploadTransfer)
        assertEquals("private://image", restored.uploadedAttachments.single().fileRef)
    }

    @Test
    fun interruptedExternalShareUploadRestoresAsQueuedAfterProcessDeath() {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
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
        assertNull(restored.uploadedAttachments.single().uploadTransfer)
        assertEquals("private://image", restored.uploadedAttachments.single().fileRef)
    }

    @Test
    fun draftRetirementDefersSharedImageRefUntilLastSiblingLeaves() {
        val retired = mutableListOf<Set<String>>()
        val persistence =
            SharedThreadComposerDraftPersistence(
                store = InMemoryRaftKeyValueStore(),
                retireTemporaryFileRefs = { _, refs -> retired += refs }
            )
        val firstKey = "send=channel-one|parent=_|mention=channel-one"
        val secondKey = "send=channel-two|parent=_|mention=channel-two"
        val image =
            SharedThreadComposerAttachment(
                id = "image",
                fileName = "photo.jpg",
                mimeType = "image/jpeg",
                status = "ready",
                fileRef = "cache/shared-photo.jpg",
                source = "composer_image_compressor"
            )

        persistence.save(firstKey, "one", asTask = false, uploadedAttachments = listOf(image))
        persistence.save(secondKey, "two", asTask = false, uploadedAttachments = listOf(image))
        persistence.clear(firstKey)
        assertEquals(emptyList(), retired)

        persistence.clear(secondKey)
        assertEquals(listOf(setOf("cache/shared-photo.jpg")), retired)
    }

    @Test
    fun queuedExternalShareAttachmentWaitsForExplicitSendWithoutPretendingUploadStarted() {
        val queued =
            RaftPendingAttachmentItem(
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
            externalShareServerId = "server"
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
