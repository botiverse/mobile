package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.microseconds

/**
 * Task #246: the QA timing lines (`change_received` / `draft_written`) the draft-kill acceptance
 * reads from logcat. They carry a sequence number and a length, never text, and only debuggable
 * builds install a sink.
 */
class SharedThreadComposerDraftTimingMarkerTest {
    private val draftKey = "send=channel-timing|parent=_|mention=channel-timing"

    @Test
    fun aChangeAndItsDurableWriteEachLogOneLineWithoutText() {
        val lines = mutableListOf<String>()
        val clock = VirtualDraftWriteClock()
        val store = startStore(clock, timingSink = sinkInto(lines))

        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "secret words"))
        assertEquals(listOf("change_received seq=1 len=12"), changeLines(lines))
        clock.advanceBy(0L)

        assertEquals(listOf("change_received seq=1 len=12", "draft_written seq=1 len=12 path=background"), changeLines(lines))
        assertTrue(lines.none { "secret" in it }, "lines=$lines")
    }

    @Test
    fun coalescedChangesReportTheLatestSequenceAsWritten() {
        val lines = mutableListOf<String>()
        val clock = VirtualDraftWriteClock()
        val store = startStore(clock, timingSink = sinkInto(lines))

        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "a"))
        clock.advanceBy(0L)
        // Within the 40 ms write interval: one write for both, carrying the newest sequence.
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "ab"))
        clock.advanceBy(5L)
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "abc"))
        clock.advanceBy(IntervalMillis)

        assertEquals(
            listOf(
                "change_received seq=1 len=1",
                "draft_written seq=1 len=1 path=background",
                "change_received seq=2 len=2",
                "change_received seq=3 len=3",
                "draft_written seq=3 len=3 path=background",
            ),
            changeLines(lines),
        )
    }

    @Test
    fun aFlushBeforeTheScheduledWriteReportsTheFlushedChange() {
        val lines = mutableListOf<String>()
        val clock = VirtualDraftWriteClock()
        val store = startStore(clock, timingSink = sinkInto(lines))

        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "x"))
        clock.advanceBy(0L)
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "xy"))
        // Backgrounded before the interval ends (Home): the flush writes it.
        store.dispatch(SharedThreadComposerDraftIntent.Flush)

        assertEquals("draft_written seq=2 len=2 path=flush", changeLines(lines).last(), "lines=$lines")
        clock.runEverythingIncludingCancelled()
        assertEquals(1, changeLines(lines).count { it == "draft_written seq=2 len=2 path=flush" }, "lines=$lines")
    }

    @Test
    fun nonDebuggableBuildsInstallNoSinkAndTheSliceLogsNothing() {
        val factoryLines = mutableListOf<String>()
        var factoryCalls = 0
        val factory = {
            factoryCalls += 1
            { line: String -> factoryLines += line }
        }
        try {
            listOf(false to true, true to false, false to false).forEach { (buildConfigDebug, appDebuggable) ->
                assertFalse(
                    RaftDraftTimingMarker.installIfEnabled(buildConfigDebug, appDebuggable, factory),
                    "buildConfigDebug=$buildConfigDebug appDebuggable=$appDebuggable",
                )
            }
            assertEquals(0, factoryCalls)

            // The production default reads the installed sink: none here.
            val clock = VirtualDraftWriteClock()
            val store = startStore(clock)
            store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "release"))
            clock.advanceBy(IntervalMillis)
            store.dispatch(SharedThreadComposerDraftIntent.Flush)
            assertEquals(emptyList<String>(), factoryLines)

            // A debuggable build does install it, and the same slice wiring then logs.
            assertTrue(RaftDraftTimingMarker.installIfEnabled(buildConfigDebug = true, appDebuggable = true, factory))
            store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "debug"))
            clock.advanceBy(IntervalMillis)
            assertEquals(listOf("change_received seq=1 len=5", "draft_written seq=1 len=5 path=background"), changeLines(factoryLines))
        } finally {
            RaftDraftTimingMarker.installIfEnabled(buildConfigDebug = false, appDebuggable = false, factory)
        }
    }

    @Test
    fun eachDraftWriteLogsItsOwnCostAndTheTextLengthButNeverTheText() {
        val lines = mutableListOf<String>()
        val clock = VirtualDraftWriteClock()
        val store = startStore(clock, timingSink = sinkInto(lines))

        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "secret words"))
        clock.advanceBy(0L)
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "secret words!"))
        store.dispatch(SharedThreadComposerDraftIntent.Flush)

        val written = lines.filter { it.startsWith("draft_written ") }
        assertEquals(2, written.size, "lines=$lines")
        assertTrue(written.all { DraftWrittenLine.matches(it) }, "lines=$written")
        assertEquals(listOf("path=background", "path=flush"), written.map { Regex("""path=\w+""").find(it)?.value })
        assertTrue(lines.none { "secret" in it }, "lines=$lines")
        // Nothing but the change and its durable write is logged: no per-keystroke write of its own.
        assertTrue(lines.all { it.startsWith("change_received ") || it.startsWith("draft_written ") }, "lines=$lines")
    }

    @Test
    fun theDraftWrittenLineHasTheShapeTheAcceptanceScriptParses() {
        assertEquals(
            "draft_written seq=3 len=7 path=background costUs=1234",
            raftDraftWrittenLine(3, 7, "background", 1234.microseconds),
        )
    }

    /** The change / durable-write lines without the write's measured cost (wall-clock, so not compared). */
    private fun changeLines(lines: List<String>): List<String> = lines.map { it.replace(CostField, "") }

    private fun sinkInto(lines: MutableList<String>): () -> ((String) -> Unit)? = { { line -> lines += line } }

    private fun startStore(
        clock: VirtualDraftWriteClock,
        timingSink: (() -> ((String) -> Unit)?)? = null,
    ): SharedThreadComposerDraftStore {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val slice =
            if (timingSink == null) {
                SharedThreadComposerDraftPersistSlice(
                    persistence = persistence,
                    scheduler = clock,
                    changeWriteIntervalMillis = IntervalMillis,
                    monotonicMillis = clock::now,
                )
            } else {
                SharedThreadComposerDraftPersistSlice(
                    persistence = persistence,
                    scheduler = clock,
                    changeWriteIntervalMillis = IntervalMillis,
                    monotonicMillis = clock::now,
                    timingSink = timingSink,
                )
            }
        return SharedThreadComposerDraftStore(persistSlices = listOf(slice)).also { store ->
            store.dispatch(
                SharedThreadComposerDraftIntent.Start(
                    composer = RaftThreadComposerPayload(instanceId = "composer-timing"),
                    draftKey = draftKey,
                )
            )
        }
    }

    private companion object {
        const val IntervalMillis = 40L
        val DraftWrittenLine = Regex("""^draft_written seq=\d+ len=\d+ path=(background|flush) costUs=\d+$""")
        val CostField = Regex(""" costUs=\d+$""")
    }
}
