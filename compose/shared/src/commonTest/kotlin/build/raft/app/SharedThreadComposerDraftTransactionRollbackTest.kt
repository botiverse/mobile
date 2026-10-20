package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.MviPersistSlice
import build.raft.app.data.MviPersistSliceTransitionSnapshot
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Regression coverage for the draft persistence transaction boundary.
 *
 * The fixture is intentionally seeded after [SharedThreadComposerDraftPersistSlice] is
 * constructed.  Its initializer performs the normal active-index hydration repair; seeding
 * afterwards lets these cases observe the repair performed during transition capture and prove
 * that the raw pre-repair image remains recoverable.
 */
class SharedThreadComposerDraftTransactionRollbackTest {
    @Test
    fun equivalentTransitionRestoresMalformedActiveIndexAndReleasesGate() = runBlocking {
        val fixture = draftFixture()
        val store = fixture.mountStore()
        val before = fixture.snapshot()

        val receipt = store.acceptQueued(SharedThreadComposerDraftIntent.SetOriginalImage(enabled = false))

        assertFalse(receipt.accepted)
        assertEquals(before, fixture.snapshot())
        assertGateIsReleased(fixture.storage)
    }

    @Test
    fun laterSliceFailureRestoresMalformedActiveIndexRowsAndReleasesGate() = runBlocking {
        val fixture = draftFixture()
        val competingPersistence = SharedThreadComposerDraftPersistence(fixture.storage)
        var conflictCode: String? = null
        val failingSlice =
            object : MviPersistSlice<SharedThreadComposerDraftState, SharedThreadComposerDraftIntent> {
                override fun captureTransitionState(
                    previous: SharedThreadComposerDraftState,
                    intent: SharedThreadComposerDraftIntent,
                    reduced: SharedThreadComposerDraftState
                ): MviPersistSliceTransitionSnapshot =
                    object : MviPersistSliceTransitionSnapshot {
                        override fun commit() = Unit

                        override fun rollback() = Unit

                        override fun release() = Unit
                    }

                override fun applyTransition(
                    previous: SharedThreadComposerDraftState,
                    intent: SharedThreadComposerDraftIntent,
                    next: SharedThreadComposerDraftState,
                    snapshot: MviPersistSliceTransitionSnapshot?
                ) {
                    if (intent !is SharedThreadComposerDraftIntent.Change) return
                    val conflict =
                        assertFailsWith<SharedThreadComposerDraftPersistenceFailure> {
                            competingPersistence.beginTransition(emptyList())
                        }
                    conflictCode = conflict.code
                    throw IllegalStateException("later_slice_failed")
                }
            }
        val store = fixture.mountStore(extraSlices = listOf(failingSlice))
        val before = fixture.snapshot()
        val mountedState = store.state.value

        val failure =
            runCatching {
                store.acceptQueued(SharedThreadComposerDraftIntent.Change(seedText = "new text"))
            }.exceptionOrNull()

        assertEquals("later_slice_failed", failure?.message)
        assertEquals("transition_conflict", conflictCode)
        assertEquals(mountedState, store.state.value)
        assertEquals(before, fixture.snapshot())
        assertGateIsReleased(fixture.storage)
    }

    @Test
    fun finalizeMarkerMakesManualRollbackFailClosedForEveryDeferredRetirement() {
        val storage = InMemoryRaftKeyValueStore()
        val retired = mutableListOf<Pair<String, Set<String>>>()
        val persistence =
            SharedThreadComposerDraftPersistence(
                store = storage,
                retireTemporaryFileRefs = { key, refs ->
                    retired += key to refs
                    // Model a native handler which moved bytes and then failed before returning a
                    // per-ref receipt.  The transaction marker must make rollback conservative.
                    error("native retirement failed after partial move")
                }
            )
        val textKey = "send=marker-text|parent=_|mention=marker-text"
        val attachmentOnlyKey = "send=marker-only|parent=_|mention=marker-only"
        val textRef = "cache/marker-text.jpg"
        val attachmentOnlyRef = "cache/marker-only.jpg"

        persistence.save(
            key = textKey,
            text = "before",
            asTask = false,
            uploadedAttachments = listOf(ownedAttachment(textRef))
        )
        persistence.save(
            key = attachmentOnlyKey,
            text = "",
            asTask = false,
            uploadedAttachments = listOf(ownedAttachment(attachmentOnlyRef))
        )

        val transaction = persistence.beginTransition(emptyList())
        transaction.save(
            key = textKey,
            text = "after",
            asTask = false,
            uploadedAttachments = emptyList(),
            externalShareSources = emptyList()
        )
        transaction.clear(attachmentOnlyKey)
        assertEquals(setOf(textRef, attachmentOnlyRef), transaction.deferredRetirementRefs())

        transaction.commit()
        transaction.markFinalizeAttempted()
        assertTrue(transaction.isFinalizeAttempted())
        assertFailsWith<SharedThreadComposerDraftPersistenceFailure> {
            transaction.finalize()
        }
        // The first sorted retirement fails; the second was never called.  Its ref is still in
        // the deferred plan and therefore must also be removed from the rollback image.
        assertEquals(1, retired.size)

        transaction.rollback()

        val restoredText = persistence.read(textKey)
        assertEquals("before", restoredText?.text)
        assertTrue(restoredText?.uploadedAttachments.orEmpty().isEmpty())
        val restoredAttachmentOnly = persistence.read(attachmentOnlyKey)
        assertTrue(restoredAttachmentOnly?.text.orEmpty().isEmpty())
        assertTrue(restoredAttachmentOnly?.uploadedAttachments.orEmpty().isEmpty())
        assertEquals(
            textKey,
            storage.getString(ActiveIndexKey)
        )
        assertGateIsReleased(storage)
    }

    @Test
    fun finalizeMarkerLifecycleIsPreparedBoundAndIdempotent() {
        val storage = InMemoryRaftKeyValueStore()
        val persistence = SharedThreadComposerDraftPersistence(storage)
        val transaction = persistence.beginTransition(emptyList())

        assertFailsWith<IllegalStateException> { transaction.markFinalizeAttempted() }
        transaction.commit()
        assertFailsWith<IllegalStateException> { transaction.finalize() }
        transaction.markFinalizeAttempted()
        transaction.markFinalizeAttempted()
        assertTrue(transaction.isFinalizeAttempted())

        // No retirement is planned, so finalization/rollback remain harmless and the marker is
        // still a stable one-way fact for callers which inspect the snapshot after cleanup.
        transaction.finalize()
        transaction.rollback()
        transaction.markFinalizeAttempted()
        assertTrue(transaction.isFinalizeAttempted())
        assertGateIsReleased(storage)
    }

    private fun assertGateIsReleased(storage: InMemoryRaftKeyValueStore) {
        val persistence = SharedThreadComposerDraftPersistence(storage)
        val probe = persistence.beginTransition(emptyList())
        probe.rollback()
        probe.release()
    }

    private fun draftFixture(): DraftFixture {
        val storage = InMemoryRaftKeyValueStore()
        val persistence = SharedThreadComposerDraftPersistence(storage)
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val validKey =
            "send=rollback-channel|parent=_|mention=rollback-channel"
        val currentKey = validKey

        // Seed one valid row through the persistence API. The malformed/stale entries are added
        // only after the mounted store has completed its clean Start transition; otherwise
        // Start's fallback read could consume the repair before transaction capture.
        persistence.save(validKey, text = "before", asTask = true)

        return DraftFixture(
            storage = storage,
            persistence = persistence,
            persistSlice = persistSlice,
            currentKey = currentKey,
            keys = listOf(
                ActiveIndexKey,
                "$DraftStoragePrefix$validKey:text",
                "$DraftStoragePrefix$validKey:asTask",
                "$DraftStoragePrefix$validKey:attachments",
                "$DraftStoragePrefix$validKey:externalShareSources",
                "$DraftStoragePrefix$MalformedKey:text",
                "$DraftStoragePrefix$MalformedKey:asTask",
                "$DraftStoragePrefix$MalformedKey:attachments",
                "$DraftStoragePrefix$MalformedKey:externalShareSources",
                "$DraftStoragePrefix$StaleKey:text",
                "$DraftStoragePrefix$StaleKey:asTask",
                "$DraftStoragePrefix$StaleKey:attachments",
                "$DraftStoragePrefix$StaleKey:externalShareSources"
            )
        )
    }

    private data class DraftFixture(
        val storage: InMemoryRaftKeyValueStore,
        val persistence: SharedThreadComposerDraftPersistence,
        val persistSlice: SharedThreadComposerDraftPersistSlice,
        val currentKey: String,
        val keys: List<String>
    ) {
        fun mountStore(
            extraSlices: List<MviPersistSlice<SharedThreadComposerDraftState, SharedThreadComposerDraftIntent>> = emptyList()
        ): SharedThreadComposerDraftStore {
            val store =
                SharedThreadComposerDraftStore(
                    persistSlices = listOf(persistSlice) + extraSlices
                )
            store.dispatch(
                SharedThreadComposerDraftIntent.Start(
                    composer =
                        RaftThreadComposerPayload(
                            instanceId = "rollback-composer",
                            seedText = "",
                            seedAsTask = false
                        ),
                    draftKey = currentKey
                )
            )
            storage.putString(
                ActiveIndexKey,
                " $currentKey\n$MalformedKey\n$StaleKey\n$currentKey\n"
            )
            storage.putString("$DraftStoragePrefix$MalformedKey:text", "orphan text")
            storage.putString("$DraftStoragePrefix$MalformedKey:asTask", "true")
            return store
        }

        fun snapshot(): Map<String, String?> =
            keys.associateWith { key -> storage.getString(key) }
    }

    private fun ownedAttachment(fileRef: String): SharedThreadComposerAttachment =
        SharedThreadComposerAttachment(
            id = "owned-${fileRef.substringAfterLast('/')}",
            fileName = "image.jpg",
            mimeType = "image/jpeg",
            status = "ready",
            fileRef = fileRef,
            source = "composer_image_compressor"
        )

    private companion object {
        const val ActiveIndexKey = "threadComposerDraft:v1:activeKeys"
        const val DraftStoragePrefix = "threadComposerDraft:v1:"
        const val MalformedKey = "malformed-active-entry"
        const val StaleKey = "send=stale-channel|parent=_|mention=stale-channel"
    }
}
