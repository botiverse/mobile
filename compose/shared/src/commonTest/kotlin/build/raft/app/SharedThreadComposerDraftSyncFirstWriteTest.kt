package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.SharedPreferenceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task #246: the first typed change after a quiet period is durable before the transition returns
 * (no scheduler run), without waiting behind another writer; later changes inside the write interval
 * still coalesce on the background scheduler.
 */
class SharedThreadComposerDraftSyncFirstWriteTest {
    private val draftKey = "send=channel-sync|parent=_|mention=channel-sync"

    @Test
    fun theFirstChangeAfterAQuietPeriodIsDurableWithoutAnySchedulerRun() {
        val storage = InMemoryRaftKeyValueStore()
        val clock = VirtualDraftWriteClock()
        val store = startStore(storage, clock)

        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "h"))
        // No scheduler run at all: the process could die right here.
        assertEquals("h", SharedThreadComposerDraftPersistence(storage).read(draftKey)?.text)
        assertEquals(emptyList<Long>(), clock.requestedDelays)

        // A keystroke inside the interval coalesces in the background, as before.
        clock.advanceBy(10L)
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "he"))
        assertEquals(listOf(IntervalMillis - 10L), clock.requestedDelays)
        assertEquals("h", SharedThreadComposerDraftPersistence(storage).read(draftKey)?.text)
        clock.advanceBy(IntervalMillis)
        assertEquals("he", SharedThreadComposerDraftPersistence(storage).read(draftKey)?.text)

        // After a quiet period the next keystroke is synchronous again.
        clock.advanceBy(500L)
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "hey"))
        assertEquals("hey", SharedThreadComposerDraftPersistence(storage).read(draftKey)?.text)
        assertEquals(1, clock.requestedDelays.size)
    }

    @Test
    fun fastTypingStillWritesABoundedNumberOfTimes() {
        val storage = CountingWritesStore()
        val clock = VirtualDraftWriteClock()
        val store = startStore(storage, clock)
        val textKey = SharedThreadComposerDraftPersistence.draftRowKeys(draftKey).first()

        var typed = ""
        repeat(100) { index ->
            typed += ('a' + index % 26)
            store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = typed))
            clock.advanceBy(5L)
        }
        clock.advanceBy(IntervalMillis)

        assertEquals(typed, SharedThreadComposerDraftPersistence(storage).read(draftKey)?.text)
        // 500 ms of typing: about one write per 40 ms interval, synchronous or not; never one per key.
        val writes = storage.writesTo(textKey)
        assertTrue(writes in 2..(500 / IntervalMillis.toInt() + 3), "writes=$writes")
    }

    @Test
    fun aSentDraftIsNotResurrectedByTheSynchronousWriteNorByALateBackgroundWrite() {
        val storage = InMemoryRaftKeyValueStore()
        val clock = VirtualDraftWriteClock()
        val store = startStore(storage, clock)

        // Synchronous first write, then a coalesced one still pending when the message is sent.
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "the"))
        clock.advanceBy(10L)
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "the message"))
        store.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        store.dispatch(SharedThreadComposerDraftIntent.CompleteSend())
        // Even a callback already past its cancellation check runs: the generation fence holds.
        clock.runEverythingIncludingCancelled()

        assertNull(SharedThreadComposerDraftPersistence(storage).read(draftKey))
        assertEquals("", restart(storage).seedText)
    }

    @Test
    fun aBusyStoreLockFallsBackToAnImmediateBackgroundWriteAndLogsItOncePerBurst() {
        val storage = InMemoryRaftKeyValueStore()
        val clock = VirtualDraftWriteClock()
        val lines = mutableListOf<String>()
        val store = startStore(storage, clock, lockAvailable = { false }, diagnostics = { lines += it })

        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "a"))
        // Not written on the calling thread, and it did not wait: an immediate background write instead.
        assertNull(SharedThreadComposerDraftPersistence(storage).read(draftKey))
        assertEquals(listOf(0L), clock.requestedDelays)
        clock.advanceBy(0L)
        assertEquals("a", SharedThreadComposerDraftPersistence(storage).read(draftKey)?.text)

        // A second quiet-period keystroke in the same burst: falls back again, logged only once.
        clock.advanceBy(100L)
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "ab"))
        clock.advanceBy(0L)
        assertEquals("ab", SharedThreadComposerDraftPersistence(storage).read(draftKey)?.text)
        assertEquals(1, lines.count { it.startsWith("thread_composer_draft_sync_write_fallback ") }, "lines=$lines")
    }

    private fun startStore(
        storage: SharedPreferenceStore,
        clock: VirtualDraftWriteClock,
        lockAvailable: () -> Boolean = { true },
        diagnostics: ((String) -> Unit)? = null,
    ): SharedThreadComposerDraftStore {
        val slice =
            SharedThreadComposerDraftPersistSlice(
                persistence = SharedThreadComposerDraftPersistence(storage, diagnosticsLogger = diagnostics),
                diagnosticsLogger = diagnostics,
                scheduler = clock,
                changeWriteIntervalMillis = IntervalMillis,
                monotonicMillis = clock::now,
                syncWriteLockAvailable = lockAvailable,
            )
        return SharedThreadComposerDraftStore(persistSlices = listOf(slice)).also { store ->
            store.dispatch(
                SharedThreadComposerDraftIntent.Start(
                    composer = RaftThreadComposerPayload(instanceId = "composer-sync"),
                    draftKey = draftKey,
                )
            )
        }
    }

    private fun restart(storage: SharedPreferenceStore): SharedThreadComposerDraftState {
        val slice = SharedThreadComposerDraftPersistSlice(SharedThreadComposerDraftPersistence(storage), scheduler = VirtualDraftWriteClock())
        val store = SharedThreadComposerDraftStore(persistSlices = listOf(slice))
        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-sync-restarted"),
                draftKey = draftKey,
            )
        )
        return store.state.value
    }

    private class CountingWritesStore : SharedPreferenceStore {
        private val values = mutableMapOf<String, String>()
        private val writes = mutableMapOf<String, Int>()

        fun writesTo(key: String): Int = writes[key] ?: 0

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            writes[key] = (writes[key] ?: 0) + 1
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private companion object {
        const val IntervalMillis = 40L
    }
}
