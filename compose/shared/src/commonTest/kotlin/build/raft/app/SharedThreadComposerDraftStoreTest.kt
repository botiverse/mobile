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
        // One save: text, asTask, attachments, sources, mentions, editedAt, serverId and the active index.
        assertEquals(8, storage.writeCount)

        scheduler.runPending()

        assertEquals(8, storage.writeCount)
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
        val owner = ThreadComposerEditorOwner(seedText = "", seedAsTask = false)
        owner.textFieldState.edit {
            replace(0, length, "ni")
            selection = androidx.compose.ui.text.TextRange(2)
        }

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
                seedText = owner.text,
                seedAsTask = owner.asTask,
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.Flush)

        assertEquals("ni", store.state.value.seedText)
        assertEquals("ni", persistence.read(draftKey)?.text)
        assertEquals(androidx.compose.ui.text.TextRange(2), owner.selection)
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

    @Test
    fun jointChannelRootDraftIsSingleSharedAcrossServersAndClearsOnSendOrBlank() {
        val storage = InMemoryRaftKeyValueStore()
        var now = 10_000L
        val persistence = SharedThreadComposerDraftPersistence(storage, nowEpochMillis = { now })
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val serverAKey =
            ThreadComposerSurface(
                kind = RaftThreadSurfaceKind.Channel,
                channelId = "chan-a",
                jointChannelId = "joint-1",
            ).threadComposerDraftKey()
        val serverBKey =
            ThreadComposerSurface(
                kind = RaftThreadSurfaceKind.Channel,
                channelId = "chan-b",
                jointChannelId = "joint-1",
            ).threadComposerDraftKey()
        assertEquals("send=chan-a|parent=_|mention=chan-a|joint=joint-1", serverAKey)
        assertEquals("send=chan-b|parent=_|mention=chan-b|joint=joint-1", serverBKey)
        // Non-joint channel keeps the exact legacy 3-part key.
        assertEquals(
            "send=plain-chan|parent=_|mention=plain-chan",
            ThreadComposerSurface(kind = RaftThreadSurfaceKind.Channel, channelId = "plain-chan").threadComposerDraftKey(),
        )

        val mention = build.raft.app.data.ThreadSendMessageMention(type = "agent", id = "agent-1", name = "Reef")
        val uploaded =
            SharedThreadComposerAttachment(
                id = "att-1",
                fileName = "spec.pdf",
                sizeLabel = "12 KB",
                mimeType = "application/pdf",
                status = "ready",
            )

        val storeA = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        storeA.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-a"),
                draftKey = serverAKey,
            )
        )
        storeA.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "hi @Reef", seedAsTask = true))
        storeA.dispatch(SharedThreadComposerDraftIntent.SelectMention(mention))
        storeA.dispatch(SharedThreadComposerDraftIntent.AddUploadedAttachments(listOf(uploaded)))

        assertEquals(1, persistence.draftListings().size)
        assertEquals(10_000L, persistence.draftListings().single().editedAtEpochMillis)

        // Opening the same joint channel on Server B loads & migrates the single draft, preserving editedAt.
        now = 25_000L
        val storeB = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        storeB.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-b"),
                draftKey = serverBKey,
            )
        )
        assertEquals("hi @Reef", storeB.state.value.seedText)
        assertTrue(storeB.state.value.seedAsTask)
        assertEquals(listOf(mention), storeB.state.value.structuredMentions)
        assertEquals(listOf(uploaded), storeB.state.value.uploadedAttachments)
        assertEquals(1, persistence.draftListings().size)
        assertEquals(serverBKey, persistence.draftListings().single().record.key)
        assertEquals(10_000L, persistence.draftListings().single().editedAtEpochMillis)
        assertNull(persistence.read(requireNotNull(serverAKey)))

        // Editing on Server B updates the single shared draft; Server A sees the edit.
        now = 30_000L
        storeB.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "updated on B @Reef"))
        assertEquals(30_000L, persistence.draftListings().single().editedAtEpochMillis)

        val reopenedA = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        reopenedA.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-a-2"),
                draftKey = serverAKey,
            )
        )
        assertEquals("updated on B @Reef", reopenedA.state.value.seedText)
        assertEquals(1, persistence.draftListings().size)

        // Sending on Server A clears the shared draft on both servers (including any stale sibling key).
        persistence.save(requireNotNull(serverBKey), text = "stale duplicate", asTask = false)
        reopenedA.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        reopenedA.dispatch(SharedThreadComposerDraftIntent.CompleteSend())
        assertEquals(emptyList(), persistence.draftListings())
        assertTrue(persistSlice.activeDraftRecordsState.value.isEmpty())

        // Clearing to blank on Server B also clears any sibling key.
        persistence.save(requireNotNull(serverAKey), text = "to clear", asTask = false)
        val clearStoreB = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        clearStoreB.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-b-clear"),
                draftKey = serverBKey,
            )
        )
        assertEquals("to clear", clearStoreB.state.value.seedText)
        clearStoreB.dispatch(SharedThreadComposerDraftIntent.Change(seedText = ""))
        assertEquals(emptyList(), persistence.draftListings())
        assertTrue(persistSlice.activeDraftRecordsState.value.isEmpty())
    }

    @Test
    fun jointReplyThreadDraftSharesAcrossServersWithoutCrossChannelLeak() {
        val storage = InMemoryRaftKeyValueStore()
        val persistence = SharedThreadComposerDraftPersistence(storage)
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)

        val serverAReplyKey = "send=thread-a|parent=msg-1|mention=chan-a|joint=joint-1"
        val serverBReplyBeforeCreateKey = "send=chan-b|parent=msg-1|mention=chan-b|joint=joint-1"
        val serverBReplyKey = "send=thread-b|parent=msg-1|mention=chan-b|joint=joint-1"
        val otherJointReplyKey = "send=thread-c|parent=msg-1|mention=chan-c|joint=joint-2"
        val rootJointKey = "send=chan-b|parent=_|mention=chan-b|joint=joint-1"

        persistence.save(serverAReplyKey, text = "joint reply draft", asTask = false)

        // Different joint channel must never inherit this reply draft.
        val otherStore = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        otherStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "other-joint", parentMessageId = "msg-1"),
                draftKey = otherJointReplyKey,
            )
        )
        assertEquals("", otherStore.state.value.seedText)

        // Root joint channel must never inherit a reply thread draft.
        val rootStore = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        rootStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "root-joint"),
                draftKey = rootJointKey,
            )
        )
        assertEquals("", rootStore.state.value.seedText)

        // Server B opens the reply thread (even before Server B has a local thread projection).
        val storeB = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        storeB.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "reply-b", parentMessageId = "msg-1"),
                draftKey = serverBReplyBeforeCreateKey,
            )
        )
        assertEquals("joint reply draft", storeB.state.value.seedText)
        assertEquals(1, persistence.draftListings().size)
        assertEquals(serverBReplyBeforeCreateKey, persistence.draftListings().single().record.key)

        // Once Server B resolves its local thread projection, it seamlessly carries over.
        storeB.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "reply-b", parentMessageId = "msg-1"),
                draftKey = serverBReplyKey,
            )
        )
        assertEquals("joint reply draft", storeB.state.value.seedText)
        assertEquals(1, persistence.draftListings().size)
        assertEquals(serverBReplyKey, persistence.draftListings().single().record.key)
    }

    @Test
    fun pendingDebouncedWriteOnServerAFlushesWhenServerBOpensSameJointChannel() {
        val storage = InMemoryRaftKeyValueStore()
        val persistence = SharedThreadComposerDraftPersistence(storage)
        val scheduler = FakeThreadComposerDraftPersistenceScheduler()
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence, scheduler = scheduler)

        val keyA = "send=chan-a|parent=_|mention=chan-a|joint=joint-1"
        val keyB = "send=chan-b|parent=_|mention=chan-b|joint=joint-1"

        val storeA = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        storeA.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-a"),
                draftKey = keyA,
            )
        )
        storeA.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "typed right before switching"))
        // Not flushed yet: still pending in scheduler.
        assertNull(persistence.read(keyA))

        // Switch immediately to Server B before debounce runs.
        val storeB = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        storeB.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-b"),
                draftKey = keyB,
            )
        )
        assertEquals("typed right before switching", storeB.state.value.seedText)
        assertEquals("typed right before switching", persistence.read(keyB)?.text)
        assertNull(persistence.read(keyA))
    }

    @Test
    fun legacyThreePartJointKeyUpgradesCleanlyToFourPartKeyOnSameServer() {
        val storage = InMemoryRaftKeyValueStore()
        val persistence = SharedThreadComposerDraftPersistence(storage)
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)

        val legacyKey = "send=chan-a|parent=_|mention=chan-a"
        val upgradedKey = "send=chan-a|parent=_|mention=chan-a|joint=joint-1"
        persistence.save(legacyKey, text = "pre-upgrade draft", asTask = false)

        val store = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-upgrade"),
                draftKey = upgradedKey,
            )
        )
        assertEquals("pre-upgrade draft", store.state.value.seedText)
        assertEquals(1, persistence.draftListings().size)
        assertEquals(upgradedKey, persistence.draftListings().single().record.key)
    }

    @Test
    fun draftProjectionMatchesSiblingServerJointChannelInSidebarAndActivity() {
        val records =
            setOf(
                SharedThreadComposerDraftRecord(
                    key = "send=chan-a|parent=_|mention=chan-a|joint=joint-1",
                    sendChannelId = "chan-a",
                    mentionChannelId = "chan-a",
                    jointChannelId = "joint-1",
                )
            )
        val serverBDirectory =
            build.raft.app.data.SharedConversationDirectoryState(
                projectionsByCanonicalId =
                    mapOf(
                        "chan-b" to
                            build.raft.app.data.SharedConversationDirectoryProjection(
                                serverId = "server-b",
                                canonicalChannelId = "chan-b",
                                aliases = setOf("chan-b"),
                                conversationKind = build.raft.app.model.ConversationKind.Joint,
                                surfaceKind = RaftThreadSurfaceKind.Channel,
                                channelKind = "joint",
                                title = "shared-room",
                                jointChannelId = "joint-1",
                            ),
                        "other-b" to
                            build.raft.app.data.SharedConversationDirectoryProjection(
                                serverId = "server-b",
                                canonicalChannelId = "other-b",
                                aliases = setOf("other-b"),
                                conversationKind = build.raft.app.model.ConversationKind.Joint,
                                surfaceKind = RaftThreadSurfaceKind.Channel,
                                channelKind = "joint",
                                title = "other-room",
                                jointChannelId = "joint-2",
                            ),
                    )
            )
        val directProjection = records.toSharedThreadComposerDraftProjection()
        assertTrue(directProjection.hasRootDraft("chan-b", jointChannelId = "joint-1"))
        assertFalse(directProjection.hasRootDraft("other-b", jointChannelId = "joint-2"))

        val directoryProjection = records.toSharedThreadComposerDraftProjection(serverBDirectory)
        assertTrue(directoryProjection.hasRootDraft("chan-b"))
        assertFalse(directoryProjection.hasRootDraft("other-b"))
        val activityItem =
            InboxItem(
                key = "chan-b",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("chan-b"),
                channelName = "shared-room",
            ).withComposerDraftProjection(directoryProjection)
        assertTrue(activityItem.hasDraft)
    }

    @Test
    fun threadComposerJointChannelIdResolvesForRootAndReplyThread() {
        val parentProj =
            build.raft.app.data.SharedConversationDirectoryProjection(
                serverId = "s2",
                canonicalChannelId = "chan-b",
                aliases = setOf("chan-b"),
                conversationKind = build.raft.app.model.ConversationKind.Joint,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "joint",
                title = "shared-room",
                jointChannelId = "joint-1",
            )
        val replyPayload =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentChannelId = "chan-b",
                parentMessageId = "msg-1",
            )
        assertEquals(
            "joint-1",
            threadComposerJointChannelId(
                payload = replyPayload,
                lookupIds = listOf("thread-b", "chan-b"),
                projections = mapOf("chan-b" to parentProj),
            ),
        )
        val dmPayload = RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.DirectMessage, jointChannelId = "joint-1")
        assertNull(
            threadComposerJointChannelId(
                payload = dmPayload,
                lookupIds = listOf("dm-1"),
                projections = mapOf("dm-1" to parentProj),
            ),
        )
        val taskPayload =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = RaftTaskConversationChannelKind,
                parentChannelId = "chan-b",
                jointChannelId = "joint-1",
            )
        assertNull(
            threadComposerJointChannelId(
                payload = taskPayload,
                lookupIds = listOf("task-conv-1", "chan-b"),
                projections = mapOf("chan-b" to parentProj),
                stableHeaderProjection = parentProj,
            ),
        )
    }

    @Test
    fun commentHostAndDmLoadingPayloadsClearJointChannelId() {
        val jointPayload =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "joint",
                sourceThreadId = "chan-joint",
                streamChannelId = "chan-joint",
                jointChannelId = "joint-42",
            )
        val nonJointChannelRef =
            RaftThreadMessageCommentRef(
                attachmentId = "att-1",
                filename = "doc.md",
                hostMessageId = "msg-10",
                hostSource =
                    RaftThreadMessageCommentHostSource(
                        type = RaftThreadSurfaceKind.Channel,
                        routeKind = RaftThreadSurfaceKind.Channel,
                        channelId = "chan-general",
                    ),
            )
        val nonJointChannelPayload = requireNotNull(jointPayload.commentHostLoadingPayload(nonJointChannelRef))
        assertNull(nonJointChannelPayload.jointChannelId)
        assertEquals(RaftThreadSurfaceKind.Channel, nonJointChannelPayload.channelKind)

        val replyRef =
            RaftThreadMessageCommentRef(
                attachmentId = "att-1",
                filename = "doc.md",
                hostMessageId = "msg-10",
                hostSource =
                    RaftThreadMessageCommentHostSource(
                        type = RaftThreadSurfaceKind.ReplyThread,
                        routeKind = RaftThreadSurfaceKind.Channel,
                        channelId = "chan-general",
                        parentMessageId = "msg-parent",
                        threadChannelId = "thread-general",
                    ),
            )
        val replyFromComment = requireNotNull(jointPayload.commentHostLoadingPayload(replyRef))
        assertNull(replyFromComment.jointChannelId)

        val dmPayload =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                channelKind = "dm",
                sourceThreadId = "dm-1",
                streamChannelId = "dm-1",
                jointChannelId = "joint-42",
            )
        val dmReply = dmPayload.replyThreadLoadingPayload(parentMessageId = "msg-dm", parentChannelId = "dm-1", threadChannelId = null)
        assertNull(dmReply.jointChannelId)
        val backToDm = dmReply.copy(jointChannelId = "joint-42").parentMessageContextLoadingPayload("msg-dm")
        assertNull(backToDm.jointChannelId)
    }

    @Test
    fun jointChannelDuplicateSiblingStartKeepsStrictlyNewerSiblingInsteadOfOverwriting() {
        val storage = InMemoryRaftKeyValueStore()
        var now = 100L
        val persistence = SharedThreadComposerDraftPersistence(storage, nowEpochMillis = { now })
        val keyA = "send=chan-a|parent=_|mention=chan-a|joint=joint-1"
        val keyB = "send=chan-b|parent=_|mention=chan-b|joint=joint-1"

        persistence.save(key = keyA, text = "older on A", asTask = false)
        now = 200L
        persistence.save(key = keyB, text = "newer on B", asTask = false)

        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val store = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-a"),
                draftKey = keyA,
            )
        )
        assertEquals("newer on B", store.state.value.seedText)
        assertEquals(200L, persistence.readEditedAtEpochMillis(keyA))
        assertNull(persistence.read(keyB))
        assertEquals(listOf(keyA), persistence.activeDraftRecords().map { it.key })

        // Reverse case: current key is strictly newer than sibling -> keep current and clean stale sibling.
        now = 300L
        persistence.save(key = keyA, text = "newest on A", asTask = false)
        now = 250L
        persistence.save(key = keyB, text = "stale on B", asTask = false)
        val storeNewerCurrent = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        storeNewerCurrent.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-a-newer"),
                draftKey = keyA,
            )
        )
        assertEquals("newest on A", storeNewerCurrent.state.value.seedText)
        assertEquals(300L, persistence.readEditedAtEpochMillis(keyA))
        assertNull(persistence.read(keyB))
    }

    @Test
    fun externalShareCrossServerNoticeNamesTargetServerAndPreservesDraft() {
        val session =
            build.raft.app.data.StoredAuthSession(
                user = build.raft.app.data.StoredAuthUser(id = "user-1", email = "alice@example.com", name = "Alice"),
                accessToken = "tok",
                refreshToken = "ref",
                selectedServerId = "server-b",
                servers =
                    listOf(
                        build.raft.app.data.StoredAuthServer(id = "server-a", name = "Alpha Server"),
                        build.raft.app.data.StoredAuthServer(id = "server-b", name = "Beta Server"),
                    ),
            )
        val sources =
            listOf(
                SharedThreadComposerExternalShareSource(
                    token = "share-1",
                    accountId = "user-1",
                    serverId = "server-a",
                )
            )
        assertEquals(
            "This shared draft can only be sent from Alpha Server.",
            resolveExternalShareCrossServerNotice(
                session = session,
                externalShareSources = sources,
                externalShareAttachments = emptyList(),
                otherServerTemplate = "This shared draft can only be sent from ${ThreadLocalizedFeedbackPlaceholder}.",
                otherServerGenericLabel = "Generic other server.",
            ),
        )
        assertEquals(
            "Generic other server.",
            resolveExternalShareCrossServerNotice(
                session = session.copy(servers = emptyList()),
                externalShareSources = sources,
                externalShareAttachments = emptyList(),
                otherServerTemplate = "This shared draft can only be sent from ${ThreadLocalizedFeedbackPlaceholder}.",
                otherServerGenericLabel = "Generic other server.",
            ),
        )
        // Same server -> no cross-server block notice.
        assertNull(
            resolveExternalShareCrossServerNotice(
                session = session.copy(selectedServerId = "server-a"),
                externalShareSources = sources,
                externalShareAttachments = emptyList(),
                otherServerTemplate = "From ${ThreadLocalizedFeedbackPlaceholder}.",
                otherServerGenericLabel = "Generic.",
            ),
        )
        // Different account -> not a cross-server notice.
        assertNull(
            resolveExternalShareCrossServerNotice(
                session = session.copy(user = build.raft.app.data.StoredAuthUser(id = "user-2", email = "bob@example.com", name = "Bob")),
                externalShareSources = sources,
                externalShareAttachments = emptyList(),
                otherServerTemplate = "From ${ThreadLocalizedFeedbackPlaceholder}.",
                otherServerGenericLabel = "Generic.",
            ),
        )
    }

    @Test
    fun persistedServerIdSurvivesReloadFallbackMigrationRollbackClearAndAccountWipe() {
        val storage = InMemoryRaftKeyValueStore()
        val persistence = SharedThreadComposerDraftPersistence(storage)
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val rootKey = "send=chan-1|parent=_|mention=chan-1"
        val store = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))

        // Save with serverId through Start + Change and verify reload preserves it.
        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-s2"),
                draftKey = rootKey,
                serverId = "s2",
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "draft on s2"))
        assertEquals("s2", persistence.readServerId(rootKey))
        assertEquals("s2", persistence.draftListings().single { it.record.key == rootKey }.serverId)

        val reloaded = SharedThreadComposerDraftPersistence(storage)
        assertEquals("s2", reloaded.readServerId(rootKey))
        assertEquals("s2", reloaded.draftListings().single { it.record.key == rootKey }.serverId)

        // A save that omits serverId preserves the existing stored serverId.
        reloaded.save(rootKey, text = "updated without serverId", asTask = false)
        assertEquals("s2", reloaded.readServerId(rootKey))

        // SetServerId updates active state (and any pending debounced write) without touching text or syncNonce.
        val scheduler = FakeThreadComposerDraftPersistenceScheduler()
        val debouncedSlice = SharedThreadComposerDraftPersistSlice(reloaded, scheduler = scheduler)
        val debouncedStore = SharedThreadComposerDraftStore(persistSlices = listOf(debouncedSlice))
        debouncedStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-late-server"),
                draftKey = rootKey,
                serverId = null,
            )
        )
        val syncBeforeSetServer = debouncedStore.state.value.syncNonce
        debouncedStore.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "typed before server known"))
        debouncedStore.dispatch(SharedThreadComposerDraftIntent.SetServerId("s3"))
        assertEquals("s3", debouncedStore.state.value.serverId)
        assertEquals(syncBeforeSetServer, debouncedStore.state.value.syncNonce)
        scheduler.runPending()
        assertEquals("s3", reloaded.readServerId(rootKey))

        // Transaction rollback restores the previous :serverId metadata cleanly.
        val tx = reloaded.beginTransition(listOf(rootKey))
        tx.save(
            key = rootKey,
            text = "rolled back edit",
            asTask = false,
            uploadedAttachments = emptyList(),
            externalShareSources = emptyList(),
            serverId = "s-rolled-back",
        )
        assertEquals("s-rolled-back", reloaded.readServerId(rootKey))
        tx.rollback()
        tx.release()
        assertEquals("s3", reloaded.readServerId(rootKey))
        assertEquals("typed before server known", reloaded.read(rootKey)?.text)

        // Fallback migration preserves :serverId onto the new key and clears it from the source key.
        val fallbackKey = "send=chan-parent|parent=msg-7|mention=chan-parent"
        val migratedKey = "send=thread-7|parent=msg-7|mention=chan-parent"
        reloaded.save(fallbackKey, text = "first reply", asTask = false, serverId = "s2")
        val migrateStore = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        migrateStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-migrate"),
                draftKey = migratedKey,
                serverId = null,
            )
        )
        assertEquals("first reply", migrateStore.state.value.seedText)
        assertEquals("s2", reloaded.readServerId(migratedKey))
        assertNull(reloaded.readServerId(fallbackKey))
        assertNull(storage.getString("${SharedThreadComposerDraftPersistence.DraftStoragePrefix}$fallbackKey:serverId"))

        // Clear removes :serverId from storage.
        reloaded.clear(migratedKey)
        assertNull(reloaded.readServerId(migratedKey))
        assertNull(storage.getString("${SharedThreadComposerDraftPersistence.DraftStoragePrefix}$migratedKey:serverId"))

        // External share staging persists source.serverId.
        val shareKey = "send=chan-share|parent=_|mention=chan-share"
        val shareSource = SharedThreadComposerExternalShareSource("tok-1", "acc-1", "s-share")
        assertEquals(
            ExternalShareComposerDraftStageResult.Staged,
            persistSlice.stageExternalShareDraft(
                draftKey = shareKey,
                text = "shared note",
                attachments = emptyList(),
                source = shareSource,
            ),
        )
        assertEquals("s-share", reloaded.readServerId(shareKey))

        // Account wipe removes scoped :serverId completely.
        val accountBase = InMemoryRaftKeyValueStore()
        val accountA = SharedThreadComposerDraftAccount("https://api.raft.build", "user-a")
        var liveAccount: SharedThreadComposerDraftAccount? = accountA
        val accountStore = SharedThreadComposerDraftAccountStore(accountBase, liveAccount = { liveAccount })
        val accountPersistence = SharedThreadComposerDraftPersistence(accountStore)
        val accountSlice = SharedThreadComposerDraftPersistSlice(accountPersistence)
        val accounts =
            SharedThreadComposerDraftAccounts(
                base = accountBase,
                store = accountStore,
                persistenceFor = { view -> SharedThreadComposerDraftPersistence(view) },
                placeLookups = RaftDraftPlaceLookups(store = accountBase),
            )
        accounts.onAccountBinding(accountA, accountSlice)
        accountPersistence.save(rootKey, text = "account secret", asTask = false, serverId = "s2")
        val scopedServerIdKey =
            SharedThreadComposerDraftAccountKeys.scopedKey(
                accountA,
                "${SharedThreadComposerDraftPersistence.DraftStoragePrefix}$rootKey:serverId",
            )
        assertEquals("s2", accountBase.getString(scopedServerIdKey))

        liveAccount = null
        accounts.onAccountBinding(account = null, slice = accountSlice)
        assertNull(accountBase.getString(scopedServerIdKey))
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
