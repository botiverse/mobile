package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.MviPersistSlice
import build.raft.app.data.MviPersistSliceTransitionSnapshot
import build.raft.app.thread.dispatch
import build.raft.app.thread.key
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

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

    private companion object {
        const val ActiveIndexKey = "threadComposerDraft:v1:activeKeys"
        const val DraftStoragePrefix = "threadComposerDraft:v1:"
        const val MalformedKey = "malformed-active-entry"
        const val StaleKey = "send=stale-channel|parent=_|mention=stale-channel"
    }
}
