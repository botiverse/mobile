package build.raft.app

import build.raft.app.transfer.RaftComposerUploadPhase
import build.raft.app.transfer.raftComposerUploadDisplay
import build.raft.app.transfer.testUploadTransfer
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.InboxItem
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlinx.coroutines.runBlocking
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
    fun originalImageResetsForNewSessionAndAfterCompletedSend() {
        val store = SharedThreadComposerDraftStore()
        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-old"),
                draftKey = "send=channel-old"
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.SetOriginalImage(true))

        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-new"),
                draftKey = "send=channel-new"
            )
        )
        assertFalse(store.state.value.originalImage)

        store.dispatch(SharedThreadComposerDraftIntent.SetOriginalImage(true))
        store.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        store.dispatch(SharedThreadComposerDraftIntent.CompleteSend())
        assertFalse(store.state.value.originalImage)
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
    fun originalImagePreferenceIsNotWrittenToPersistedDraft() {
        val storage = InMemoryRaftKeyValueStore()
        val persistence = SharedThreadComposerDraftPersistence(storage)
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val key = "send=channel-image|parent=_|mention=channel-image"
        val store = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))

        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-image"),
                draftKey = key
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.SetOriginalImage(true))
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "persisted text"))
        store.dispatch(SharedThreadComposerDraftIntent.Flush)

        val restored = SharedThreadComposerDraftPersistence(storage).read(key)
        assertEquals("persisted text", restored?.text)

        val restoredStore = SharedThreadComposerDraftStore(persistSlices = listOf(SharedThreadComposerDraftPersistSlice(SharedThreadComposerDraftPersistence(storage))))
        restoredStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-restored"),
                draftKey = key
            )
        )
        assertFalse(restoredStore.state.value.originalImage)
    }

    @Test
    fun localEditorEchoAndChromeNeverCreateAHostEditorCommand() {
        val store = SharedThreadComposerDraftStore()
        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer"),
                draftKey = null,
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "n"))
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "ni"))
        store.dispatch(
            SharedThreadComposerDraftIntent.AddLocalAttachments(
                listOf(externalShareAttachment(status = "uploading"))
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.UpdateAttachmentUploadProgress("local", testUploadTransfer(1L, 10L)))

        assertEquals("ni", store.state.value.seedText)
        assertEquals(null, store.state.value.editorCommand)

        store.dispatch(SharedThreadComposerDraftIntent.BeginSend)

        assertEquals(ThreadComposerEditorCommandKind.Clear, store.state.value.editorCommand?.kind)
        assertEquals("ni", store.state.value.editorCommand?.expectedText)
        assertEquals(false, store.state.value.editorCommand?.expectedAsTask)

        store.dispatch(
            SharedThreadComposerDraftIntent.RestoreAfterFailure(
                seedText = "ni",
                seedAsTask = true,
                failureLabel = "failed",
            )
        )

        assertEquals(ThreadComposerEditorCommandKind.Replace, store.state.value.editorCommand?.kind)
        assertEquals("ni", store.state.value.editorCommand?.text)
        assertEquals(true, store.state.value.editorCommand?.asTask)
        assertEquals("", store.state.value.editorCommand?.expectedText)
        val restoredCommandId = store.state.value.editorCommand?.id

        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer =
                    RaftThreadComposerPayload(
                        instanceId = "composer",
                        seedText = "ni",
                        seedAsTask = true,
                    ),
                draftKey = null,
            )
        )

        assertEquals(restoredCommandId, store.state.value.editorCommand?.id)
    }

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
                composer = RaftThreadComposerPayload(instanceId = "composer-rapid"),
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
        // One save: text, asTask, attachments, sources, editedAt and the active index.
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
                composer = RaftThreadComposerPayload(instanceId = "composer-blur"),
                draftKey = draftKey
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "persist on blur"))
        store.dispatch(SharedThreadComposerDraftIntent.Flush)

        assertEquals("persist on blur", persistence.read(draftKey)?.text)
        // One save: text, asTask, attachments, sources, editedAt and the active index.
        assertEquals(6, storage.writeCount)

        scheduler.runPending()

        assertEquals(6, storage.writeCount)
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
    fun completedSendCancelsDelayedWriteWithoutResurrectingDraft() {
        val storage = CountingSharedPreferenceStore()
        val persistence = SharedThreadComposerDraftPersistence(storage)
        val scheduler = FakeThreadComposerDraftPersistenceScheduler()
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence, scheduler = scheduler)
        val draftKey = "send=channel-send|parent=_|mention=channel-send"
        val store = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))

        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-send"),
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
                composer = RaftThreadComposerPayload(instanceId = "composer-one"),
                draftKey = firstKey
            )
        )
        firstStore.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "first"))
        secondStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-two"),
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
    fun stagedUploadedAttachmentsRestoreOnceForMatchingComposerKey() {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
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
                composer = RaftThreadComposerPayload(instanceId = "other-composer"),
                draftKey = "send=channel-2|parent=_|mention=channel-2"
            )
        )
        assertEquals(emptyList(), otherStore.state.value.uploadedAttachments)

        val matchingStore = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        matchingStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "matching-composer"),
                draftKey = draftKey
            )
        )
        assertEquals(listOf(attachment), matchingStore.state.value.uploadedAttachments)

        val nextStore = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        nextStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "next-composer"),
                draftKey = draftKey
            )
        )
        assertEquals(emptyList(), nextStore.state.value.uploadedAttachments)
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
    fun successfulSendClearsPersistedDraftForComposerKey() {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val draftKey = "send=thread-1|parent=message-1"
        val store = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))

        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-1"),
                draftKey = draftKey
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "send me", seedAsTask = true))
        store.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        store.dispatch(SharedThreadComposerDraftIntent.CompleteSend())

        val nextStore = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        nextStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-2"),
                draftKey = draftKey
            )
        )

        assertEquals("", nextStore.state.value.seedText)
        assertFalse(nextStore.state.value.seedAsTask)
    }

    @Test
    fun activeDraftRecordsTrackRootAndReplyComposerKeys() {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
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
    fun replyDraftRestoreBumpsSyncNonceForSameComposerInstance() {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
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
                    RaftThreadComposerPayload(
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
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
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
                    RaftThreadComposerPayload(
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
    fun clearingBlankDraftRemovesActiveDraftRecord() {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val draftKey = "send=channel-1|parent=_|mention=channel-1"

        persistence.save(draftKey, "channel draft", asTask = false)
        persistence.save(draftKey, "", asTask = false)

        assertTrue(persistence.activeDraftRecords().isEmpty())
    }

    @Test
    fun activeDraftRecordsStateUpdatesAcrossPersistSliceInstances() {
        val storage = InMemoryRaftKeyValueStore()
        val homePersistSlice = SharedThreadComposerDraftPersistSlice(SharedThreadComposerDraftPersistence(storage))
        val threadPersistSlice = SharedThreadComposerDraftPersistSlice(SharedThreadComposerDraftPersistence(storage))
        val threadStore = SharedThreadComposerDraftStore(persistSlices = listOf(threadPersistSlice))
        val draftKey = "send=channel-1|parent=_|mention=channel-1"

        assertTrue(homePersistSlice.activeDraftRecordsState.value.isEmpty())

        threadStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-1"),
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
            RaftPendingMentionActionItem(
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
            RaftPendingMentionActionItem(
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
    fun reverseCompletedMixedAttachmentsRestoreInSelectionOrderAfterRestart() {
        val storage = InMemoryRaftKeyValueStore()
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
                composer = RaftThreadComposerPayload(instanceId = "composer-1"),
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
                composer = RaftThreadComposerPayload(instanceId = "composer-2"),
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
        firstStore.dispatch(SharedThreadComposerDraftIntent.RemoveAttachment(uploaded.id))

        assertTrue(homePersistSlice.activeDraftRecordsState.value.isEmpty())

        val secondStore = SharedThreadComposerDraftStore(persistSlices = listOf(threadPersistSlice))
        secondStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-2"),
                draftKey = draftKey
            )
        )

        assertEquals(emptyList(), secondStore.state.value.uploadedAttachments)
    }

    @Test
    fun uploadingLocalAttachmentDoesNotPersistUnrecoverableDraft() {
        val storage = InMemoryRaftKeyValueStore()
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
                composer = RaftThreadComposerPayload(instanceId = "composer-1"),
                draftKey = draftKey
            )
        )
        firstStore.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(local)))

        assertTrue(homePersistSlice.activeDraftRecordsState.value.isEmpty())

        val secondStore = SharedThreadComposerDraftStore(persistSlices = listOf(threadPersistSlice))
        secondStore.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-2"),
                draftKey = draftKey
            )
        )

        assertEquals(emptyList(), secondStore.state.value.uploadedAttachments)
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
    fun uploadingLocalAttachmentDoesNotSurviveDifferentComposerKeyRestart() {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
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
                composer = RaftThreadComposerPayload(instanceId = "composer-1"),
                draftKey = firstDraftKey
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(local)))
        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-2"),
                draftKey = secondDraftKey
            )
        )

        assertEquals(emptyList(), store.state.value.uploadedAttachments)
        assertEquals(emptySet(), store.state.value.removedAttachmentIds)
    }

    @Test
    fun draftPersistenceIsOptInToMviStore() {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val draftKey = "send=channel-1|parent=_|mention=channel-1"
        val storeWithoutSlice = SharedThreadComposerDraftStore()

        storeWithoutSlice.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-1"),
                draftKey = draftKey
            )
        )
        storeWithoutSlice.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "not persisted", seedAsTask = false))

        assertTrue(persistSlice.activeDraftRecordsState.value.isEmpty())

        val storeWithSlice = SharedThreadComposerDraftStore(persistSlices = listOf(persistSlice))
        storeWithSlice.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-2"),
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
    fun sendRequestsFocusSynchronouslyButAsyncCompletionDoesNotReviveIt() {
        val store = SharedThreadComposerDraftStore()

        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                RaftThreadComposerPayload(
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
    fun failedSendPreservesExistingPendingMentionAction() {
        val store = SharedThreadComposerDraftStore()
        val pending =
            RaftPendingMentionActionItem(
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
            RaftPendingMentionActionItem(
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
    fun clearingMentionActionNoticeDoesNotRemovePendingAction() {
        val store = SharedThreadComposerDraftStore()
        val pending =
            RaftPendingMentionActionItem(
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
    fun interruptedOwnedUploadRestoresAsQueuedAndVideoRemainsDurable() {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val draftKey = "send=channel-owned|parent=_|mention=channel-owned"
        val image =
            SharedThreadComposerAttachment(
                id = "picked-image",
                fileName = "photo.jpg",
                mimeType = "image/jpeg",
                status = "uploading",
                fileRef = "cache/photo.jpg",
                source = "platform_picker",
                uploadTransfer = testUploadTransfer(42L, 100L)
            )
        val video =
            SharedThreadComposerAttachment(
                id = "picked-video",
                fileName = "clip.mp4",
                mimeType = "video/mp4",
                status = "uploading",
                fileRef = "cache/clip.mp4",
                source = "platform_picker",
                uploadTransfer = testUploadTransfer(42L, 100L)
            )

        persistence.save(draftKey, "Body", asTask = false, uploadedAttachments = listOf(image, video))

        val restored = persistence.read(draftKey) ?: error("expected draft")
        assertEquals(listOf("picked-image", "picked-video"), restored.uploadedAttachments.map { it.id })
        assertEquals(listOf("queued", "queued"), restored.uploadedAttachments.map { it.status })
        assertEquals(listOf(null, null), restored.uploadedAttachments.map { it.uploadTransfer })
    }

    @Test
    fun draftRetirementCollectsEveryOwnedTemporaryRefIncludingVideoRows() {
        val retired = mutableListOf<Pair<String, Set<String>>>()
        val persistence =
            SharedThreadComposerDraftPersistence(
                store = InMemoryRaftKeyValueStore(),
                retireTemporaryFileRefs = { key, refs -> retired += key to refs }
            )
        val draftKey = "send=channel-retire|parent=_|mention=channel-retire"
        val image =
            SharedThreadComposerAttachment(
                id = "image",
                fileName = "photo.jpg",
                mimeType = "image/jpeg",
                status = "ready",
                fileRef = "cache/photo.jpg",
                source = "platform_picker"
            )
        val videoMime =
            SharedThreadComposerAttachment(
                id = "video-mime",
                fileName = "movie.jpg",
                mimeType = "video/mp4",
                status = "ready",
                fileRef = "cache/movie.jpg",
                source = "platform_picker"
            )
        val videoSuffix =
            SharedThreadComposerAttachment(
                id = "video-suffix",
                fileName = "movie.jpg",
                mimeType = "image/jpeg",
                status = "ready",
                fileRef = "cache/movie.flv",
                source = "platform_picker"
            )

        persistence.save(
            key = draftKey,
            text = "Body",
            asTask = false,
            uploadedAttachments = listOf(image, videoMime, videoSuffix)
        )
        assertEquals(3, persistence.read(draftKey)?.uploadedAttachments?.size)

        // Replacing/clearing a draft retires every process-local owned ref. Image/video
        // classification is only relevant to compression admission; it must not strand picker
        // video or ordinary-file handles in generic draft cleanup.
        persistence.save(key = draftKey, text = "Body", asTask = false)

        assertEquals(
            listOf(
                draftKey to
                    setOf(
                        "cache/photo.jpg",
                        "cache/movie.jpg",
                        "cache/movie.flv"
                    )
            ),
            retired
        )
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
    fun externalShareStageDoesNotRetireOrReplaceOwnedAttachment() {
        val retired = mutableListOf<Set<String>>()
        val persistence =
            SharedThreadComposerDraftPersistence(
                store = InMemoryRaftKeyValueStore(),
                retireTemporaryFileRefs = { _, refs -> retired += refs }
            )
        val draftKey = "send=channel-stage|parent=_|mention=channel-stage"
        val owned =
            SharedThreadComposerAttachment(
                id = "owned-image",
                fileName = "owned.jpg",
                mimeType = "image/jpeg",
                status = "ready",
                fileRef = "cache/owned.jpg",
                source = "platform_camera"
            )
        val source = SharedThreadComposerExternalShareSource("share-token", "account", "server")
        val incoming =
            externalShareAttachment(status = "queued").copy(
                id = "shared-image",
                fileRef = "external://share-image"
            )

        persistence.save(draftKey, "Existing", asTask = false, uploadedAttachments = listOf(owned))
        assertEquals(
            ExternalShareComposerDraftStageResult.Staged,
            persistence.stageExternalShare(draftKey, "Incoming", listOf(incoming), source)
        )

        val restored = persistence.read(draftKey) ?: error("expected staged draft")
        assertEquals(setOf("owned-image", "shared-image"), restored.uploadedAttachments.map { it.id }.toSet())
        assertEquals(emptyList(), retired)
    }

    @Test
    fun uploadProgressIsMonotonicClampedAndIgnoredAfterTerminalReplacement() {
        val store = SharedThreadComposerDraftStore()
        store.dispatch(
            SharedThreadComposerDraftIntent.AddLocalAttachments(
                listOf(externalShareAttachment(status = "uploading"))
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.UpdateAttachmentUploadProgress("local", testUploadTransfer(70L, 100L)))
        store.dispatch(SharedThreadComposerDraftIntent.UpdateAttachmentUploadProgress("local", testUploadTransfer(30L, 100L)))
        store.dispatch(SharedThreadComposerDraftIntent.UpdateAttachmentUploadProgress("local", testUploadTransfer(150L, 100L)))
        val transfer = store.state.value.uploadedAttachments.single().uploadTransfer
        // 30 after 70 is a late sample: dropped. 150 of 100 is every byte sent: 99, not 100.
        assertEquals(150L, transfer?.bytesDone)
        assertEquals(99, transfer?.percent)
        assertEquals(RaftComposerUploadPhase.Finishing, raftComposerUploadDisplay("uploading", transfer)?.phase)

        store.dispatch(
            SharedThreadComposerDraftIntent.AddUploadedAttachments(
                attachments = listOf(SharedThreadComposerAttachment("remote", "image.jpg")),
                replacedAttachmentIds = setOf("local"),
                replacedAttachmentIdsByUploadedId = mapOf("remote" to "local")
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.UpdateAttachmentUploadProgress("local", testUploadTransfer(99L, 100L)))
        assertEquals(listOf("remote"), store.state.value.uploadedAttachments.map { it.id })
    }

    @Test
    fun externalShareStagingRejectsReplyComposerAndUnboundFileReference() {
        val persistSlice =
            SharedThreadComposerDraftPersistSlice(
                SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
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
    fun mentionActionBatchBeginMarksStableDistinctIdsAtomically() {
        val store = SharedThreadComposerDraftStore()

        store.dispatch(
            SharedThreadComposerDraftIntent.BeginMentionActionBatch(
                listOf(" first ", "second", "first", "")
            )
        )

        assertEquals(setOf("first", "second"), store.state.value.executingMentionActionIds)
        assertEquals(
            mapOf("first" to "added", "second" to "added"),
            store.state.value.executingMentionActionStates
        )
        assertEquals(null, store.state.value.mentionActionNotice)
    }

    @Test
    fun mentionActionBatchPartialFinishRemovesOnlyDeliveredAndKeepsFailuresRetryable() {
        val first =
            RaftPendingMentionActionItem(
                resolutionId = " first ",
                targetType = "user",
                targetHandle = "first",
                availableActions = listOf("add")
            )
        val second = first.copy(resolutionId = "second", targetHandle = "second")
        val unrelated = first.copy(resolutionId = "unrelated", targetHandle = "unrelated")
        val store = SharedThreadComposerDraftStore()
        store.dispatch(SharedThreadComposerDraftIntent.ApplyPendingMentionActions(listOf(first, second, unrelated)))
        store.dispatch(SharedThreadComposerDraftIntent.BeginMentionActionBatch(listOf("first", "second")))

        store.dispatch(
            SharedThreadComposerDraftIntent.FinishMentionActionBatch(
                requestedIds = listOf("first", "second"),
                deliveredIds = setOf("first", "not-requested"),
                noticeTitle = "Added 1 of 2",
                notice = "The second action failed."
            )
        )

        assertEquals(listOf(second, unrelated), store.state.value.pendingMentionActions)
        assertEquals("Added 1 of 2", store.state.value.mentionActionNoticeTitle)
        assertEquals("The second action failed.", store.state.value.mentionActionNotice)
        assertEquals(emptySet(), store.state.value.executingMentionActionIds)
        assertEquals(emptyMap(), store.state.value.executingMentionActionStates)
        assertEquals(emptySet(), store.state.value.removingMentionActionIds)
        assertEquals(emptyMap(), store.state.value.mentionActionStates)
    }

    @Test
    fun mentionActionBatchMissingResultsKeepEveryPendingRow() {
        val first =
            RaftPendingMentionActionItem(
                resolutionId = "first",
                targetType = "user",
                targetHandle = "first",
                availableActions = listOf("add")
            )
        val second = first.copy(resolutionId = "second", targetHandle = "second")
        val store = SharedThreadComposerDraftStore()
        store.dispatch(SharedThreadComposerDraftIntent.ApplyPendingMentionActions(listOf(first, second)))
        store.dispatch(SharedThreadComposerDraftIntent.BeginMentionActionBatch(listOf("first", "second")))

        store.dispatch(
            SharedThreadComposerDraftIntent.FinishMentionActionBatch(
                requestedIds = listOf("first", "second"),
                deliveredIds = emptySet(),
                noticeTitle = "Added 0 of 2",
                notice = "This mention action is no longer available."
            )
        )

        assertEquals(listOf(first, second), store.state.value.pendingMentionActions)
        assertEquals(emptySet(), store.state.value.executingMentionActionIds)
        assertEquals(emptyMap(), store.state.value.executingMentionActionStates)
        assertEquals(
            "This mention action is no longer available.",
            store.state.value.mentionActionNotice
        )
        assertEquals("Added 0 of 2", store.state.value.mentionActionNoticeTitle)
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
    @Test
    fun changeWithAlreadySyncedTextReturnsNoopReceiptCarryingSameText() = runBlocking {
        // task #18 A4 regression: the adoption draft barrier reads the live native text,
        // then pushes Change(seedText=native). When the shared draft already carries that
        // text the serialized lane reports the transition as an equivalent no-op —
        // accepted=false with state=previous. The barrier must still treat that receipt as
        // satisfied (same identity, same text) and proceed to Flush, or a pre-synced draft
        // would always fail capture.
        val store = SharedThreadComposerDraftStore()
        val draftKey = "send=channel-synced|parent=_|mention=channel-synced"
        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-synced"),
                draftKey = draftKey
            )
        )
        val first = store.acceptQueued(
            SharedThreadComposerDraftIntent.Change(seedText = "already here")
        )
        assertTrue(first.accepted)
        assertEquals("already here", first.state.seedText)

        val noop = store.acceptQueued(
            SharedThreadComposerDraftIntent.Change(seedText = "already here")
        )
        assertFalse(noop.accepted)
        assertEquals("already here", noop.state.seedText)
        assertEquals(draftKey, noop.state.draftKey)
        assertEquals(first.state.instanceId, noop.state.instanceId)

        val drift = store.acceptQueued(
            SharedThreadComposerDraftIntent.Change(seedText = "different text")
        )
        assertTrue(drift.accepted)
        assertEquals("different text", drift.state.seedText)
    }
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
