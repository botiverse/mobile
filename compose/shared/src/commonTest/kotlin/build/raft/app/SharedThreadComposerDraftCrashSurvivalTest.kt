package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.SharedPreferenceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task #246: the newest typed text must already be durable when the process dies right after the
 * last keystroke (force-stop, crash in the foreground).  "Process death" here is a fresh
 * persistence facade, slice and store reading the same storage; whatever is still only scheduled
 * (not yet run) on the virtual clock is lost with the process.
 */
class SharedThreadComposerDraftCrashSurvivalTest {
    private val draftKey = "send=channel-crash|parent=_|mention=channel-crash"

    @Test
    fun changeIsWrittenAtOnceAndSurvivesProcessDeathRightAfterTheKeystroke() {
        val storage = InMemoryRaftKeyValueStore()
        val clock = VirtualDraftWriteClock()
        val store = startStore(storage, clock)

        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "hello"))
        // Nothing of the virtual clock passes: only work that is due now runs before the kill.
        clock.advanceBy(0L)

        assertEquals(listOf(0L), clock.requestedDelays)
        assertEquals("hello", restartAndRestore(storage).seedText)
    }

    @Test
    fun spacedKeystrokesAreEachDurableBeforeTheNextOne() {
        val storage = InMemoryRaftKeyValueStore()
        val clock = VirtualDraftWriteClock()
        val store = startStore(storage, clock)

        listOf("h", "he", "hel", "hell", "hello").forEach { text ->
            store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = text))
            clock.advanceBy(0L)
            assertEquals(text, restartAndRestore(storage).seedText)
            // Human typing pace: well over the write interval between keystrokes.
            clock.advanceBy(120L)
        }
        assertTrue(clock.requestedDelays.all { it == 0L }, "delays=${clock.requestedDelays}")
    }

    @Test
    fun rapidTypingCoalescesWritesButNeverLagsMoreThanOneInterval() {
        val storage = WriteCountingStore()
        val clock = VirtualDraftWriteClock()
        val store = startStore(storage, clock)
        val textKey = SharedThreadComposerDraftPersistence.draftRowKeys(draftKey).first()

        // 100 keystrokes 5 ms apart (500 ms of very fast typing / key repeat).
        var typed = ""
        repeat(100) { index ->
            typed += ('a' + index % 26)
            store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = typed))
            clock.advanceBy(5L)
            // The durable text is never older than one interval.
            val durable = storage.getString(textKey).orEmpty()
            assertTrue(typed.length - durable.length <= IntervalMillis / 5 + 1, "typed=${typed.length} durable=${durable.length}")
        }
        clock.advanceBy(IntervalMillis)

        assertEquals(typed, restartAndRestore(storage).seedText)
        // Bounded: about one write per interval (500 ms / 40 ms), not one per keystroke.
        val writes = storage.writesTo(textKey)
        assertTrue(writes in 2..(500 / IntervalMillis.toInt() + 3), "writes=$writes")
        assertTrue(clock.requestedDelays.all { it in 0L..IntervalMillis }, "delays=${clock.requestedDelays}")
    }

    @Test
    fun sentDraftIsNotResurrectedByALateWriteNorAfterRestart() {
        val storage = InMemoryRaftKeyValueStore()
        val clock = VirtualDraftWriteClock()
        val store = startStore(storage, clock)

        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "first"))
        clock.advanceBy(0L)
        // Typed within the interval: its write waits for the end of the interval.
        clock.advanceBy(10L)
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "the message"))
        store.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        store.dispatch(SharedThreadComposerDraftIntent.CompleteSend())

        // The canceled write runs anyway (a callback already past its cancellation check).
        clock.runEverythingIncludingCancelled()

        val restored = restartAndRestore(storage)
        assertEquals("", restored.seedText)
        assertNull(SharedThreadComposerDraftPersistence(storage).read(draftKey))
    }

    @Test
    fun typingAfterSendStartsAFreshDraftThatIsWrittenAtOnce() {
        val storage = InMemoryRaftKeyValueStore()
        val clock = VirtualDraftWriteClock()
        val store = startStore(storage, clock)

        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "sent"))
        clock.advanceBy(0L)
        store.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        store.dispatch(SharedThreadComposerDraftIntent.CompleteSend())
        clock.advanceBy(5L)
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "n"))
        clock.advanceBy(IntervalMillis)

        assertEquals("n", restartAndRestore(storage).seedText)
    }

    @Test
    fun rapidTypingLogsABoundedNumberOfLinesAndOneSummaryWhenTheBurstEnds() {
        val storage = InMemoryRaftKeyValueStore()
        val clock = VirtualDraftWriteClock()
        val lines = mutableListOf<String>()
        val store = startStore(storage, clock, diagnostics = { lines += it })
        lines.clear()

        var typed = ""
        repeat(100) { index ->
            typed += ('a' + index % 26)
            store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = typed))
            clock.advanceBy(5L)
        }
        clock.advanceBy(IntervalMillis)

        assertEquals(typed, restartAndRestore(storage).seedText)
        assertTrue(lines.size <= 3, "lines=${lines.size}: ${lines.joinToString(separator = "\n")}")
        // The first write of the burst still logs the lines diagnostics scripts look for.
        assertEquals(1, lines.count { it.startsWith("thread_composer_draft_persist_save ") })
        assertEquals(0, lines.count { it.startsWith("thread_composer_draft_change_writes ") })

        lines.clear()
        store.dispatch(SharedThreadComposerDraftIntent.Flush)

        val summaries = lines.filter { it.startsWith("thread_composer_draft_change_writes ") }
        assertEquals(1, summaries.size, "lines=$lines")
        assertTrue("lastTextLen=100" in summaries.single(), summaries.single())
    }

    @Test
    fun typingAfterAQuietPeriodLogsItsFirstWriteAgain() {
        val storage = InMemoryRaftKeyValueStore()
        val clock = VirtualDraftWriteClock()
        val lines = mutableListOf<String>()
        val store = startStore(storage, clock, diagnostics = { lines += it })

        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "a"))
        clock.advanceBy(100L)
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "ab"))
        clock.advanceBy(5_000L)
        lines.clear()
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "abc"))
        clock.advanceBy(0L)

        // The quiet period ended the first burst (its summary) and the new write logs in full.
        assertEquals(1, lines.count { it.startsWith("thread_composer_draft_change_writes ") }, "lines=$lines")
        assertEquals(1, lines.count { it.startsWith("thread_composer_draft_persist_save ") }, "lines=$lines")
    }

    private fun startStore(
        storage: SharedPreferenceStore,
        clock: VirtualDraftWriteClock,
        diagnostics: ((String) -> Unit)? = null,
    ): SharedThreadComposerDraftStore {
        val slice =
            SharedThreadComposerDraftPersistSlice(
                persistence = SharedThreadComposerDraftPersistence(storage, diagnosticsLogger = diagnostics),
                diagnosticsLogger = diagnostics,
                scheduler = clock,
                changeWriteIntervalMillis = IntervalMillis,
                monotonicMillis = clock::now
            )
        return SharedThreadComposerDraftStore(persistSlices = listOf(slice)).also { store ->
            store.dispatch(
                SharedThreadComposerDraftIntent.Start(
                    composer = RaftThreadComposerPayload(instanceId = "composer-crash"),
                    draftKey = draftKey
                )
            )
        }
    }

    /** A new process: fresh facade, slice and store over the same storage; cold-start restore. */
    private fun restartAndRestore(storage: SharedPreferenceStore): SharedThreadComposerDraftState {
        val slice =
            SharedThreadComposerDraftPersistSlice(
                persistence = SharedThreadComposerDraftPersistence(storage),
                scheduler = VirtualDraftWriteClock()
            )
        val store = SharedThreadComposerDraftStore(persistSlices = listOf(slice))
        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-crash-restarted"),
                draftKey = draftKey
            )
        )
        return store.state.value
    }

    private companion object {
        const val IntervalMillis = 40L
    }
}

/** Virtual time: scheduled blocks run when [advanceBy] reaches their due time. */
private class VirtualDraftWriteClock : SharedThreadComposerDraftPersistenceScheduler {
    private class Task(val dueAt: Long, val order: Long, val block: () -> Unit) {
        var cancelled = false
    }

    private val tasks = mutableListOf<Task>()
    private var nextOrder = 0L
    private var nowMillis = 0L
    val requestedDelays = mutableListOf<Long>()

    fun now(): Long = nowMillis

    override fun schedule(
        delayMillis: Long,
        block: () -> Unit
    ): SharedThreadComposerDraftPersistenceCancellation {
        requestedDelays += delayMillis
        val task = Task(dueAt = nowMillis + delayMillis, order = nextOrder++, block = block)
        tasks += task
        return SharedThreadComposerDraftPersistenceCancellation { task.cancelled = true }
    }

    fun advanceBy(millis: Long) {
        val target = nowMillis + millis
        while (true) {
            val next =
                tasks.filter { it.dueAt <= target }
                    .minWithOrNull(compareBy<Task>({ it.dueAt }, { it.order }))
                    ?: break
            tasks.remove(next)
            nowMillis = maxOf(nowMillis, next.dueAt)
            if (!next.cancelled) next.block()
        }
        nowMillis = target
    }

    fun runEverythingIncludingCancelled() {
        while (tasks.isNotEmpty()) {
            val next = tasks.minWith(compareBy<Task>({ it.dueAt }, { it.order }))
            tasks.remove(next)
            nowMillis = maxOf(nowMillis, next.dueAt)
            next.block()
        }
    }
}

private class WriteCountingStore : SharedPreferenceStore {
    private val values = mutableMapOf<String, String>()
    private val writes = mutableMapOf<String, Int>()

    fun writesTo(key: String): Int = writes[key] ?: 0

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String?) {
        writes[key] = (writes[key] ?: 0) + 1
        if (value == null) values.remove(key) else values[key] = value
    }
}
