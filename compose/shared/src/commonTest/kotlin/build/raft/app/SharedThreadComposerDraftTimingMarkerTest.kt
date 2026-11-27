package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.thread.dispatch
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
        val CostField = Regex(""" costUs=\d+$""")
    }
}
