package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one debounced sync pull for realtime events Activity cannot settle from its own read (a live
 * message for a row it lacks, `notification:retract`): bursts coalesce, a pull in flight takes one
 * more after it, other pulls cover it, nothing in the background.
 */
class SyncChangesRealtimePullTest {
    private data class Pull(val atMillis: Long, val trigger: String)

    /**
     * What RaftSharedStoreProvider does around a pull: every pull it starts tells the realtime pull
     * first ([RaftSyncRealtimePull.onTriggerPull]); each pull stays in flight until [finish].
     */
    private class Puller(private val test: TestScope) {
        lateinit var realtime: RaftSyncRealtimePull
        val pulls = mutableListOf<Pull>()
        private val gates = mutableListOf<CompletableDeferred<Unit>>()
        var allowed = true

        fun start(trigger: String): Job? {
            if (!allowed) return null
            realtime.onTriggerPull(syncTestScope)
            val gate = CompletableDeferred<Unit>()
            gates += gate
            pulls += Pull(test.testScheduler.currentTime, trigger)
            return test.backgroundScope.launch { gate.await() }
        }

        fun finish() {
            gates.forEach { it.complete(Unit) }
        }
    }

    private fun TestScope.fixture(foreground: () -> Boolean = { true }, logs: MutableList<String> = mutableListOf()): Puller {
        val puller = Puller(this)
        puller.realtime =
            RaftSyncRealtimePull(
                scope = backgroundScope,
                pull = { key, trigger ->
                    assertEquals(syncTestScope, key)
                    puller.start(trigger)
                },
                isForeground = foreground,
                logger = { logs += it },
            )
        return puller
    }

    @Test
    fun `a burst of hints makes one pull after the debounce with the realtime trigger`() = runTest {
        val logs = mutableListOf<String>()
        val p = fixture(logs = logs)

        p.realtime.request(syncTestScope, "message_absent")
        advanceTimeBy(300L)
        p.realtime.request(syncTestScope, "notification_retract")
        p.realtime.request(syncTestScope, "message_absent")
        advanceTimeBy(SYNC_CHANGES_REALTIME_DEBOUNCE_MILLIS - 300L - 1L)
        runCurrent()
        assertTrue(p.pulls.isEmpty(), "the debounce runs from the first hint")

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf(Pull(SYNC_CHANGES_REALTIME_DEBOUNCE_MILLIS, SYNC_CHANGES_REALTIME_TRIGGER)), p.pulls)
        assertEquals("realtime", SYNC_CHANGES_REALTIME_TRIGGER)
        assertTrue(
            logs.contains("sync_changes_realtime serverId=server-1 result=pulling hints=message_absent,notification_retract"),
            logs.joinToString("\n")
        )

        p.finish()
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(1, p.pulls.size, "nothing more once it ends")
        assertFalse(p.realtime.isPending(syncTestScope))
    }

    @Test
    fun `hints while its pull is in flight coalesce into one more pull after it`() = runTest {
        val p = fixture()

        p.realtime.request(syncTestScope, "message_absent")
        advanceTimeBy(SYNC_CHANGES_REALTIME_DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals(1, p.pulls.size)

        // In flight: these may have come after it read; one more, not three.
        repeat(3) {
            p.realtime.request(syncTestScope, "message_absent")
            advanceTimeBy(1_000L)
        }
        runCurrent()
        assertEquals(1, p.pulls.size, "no second pull beside the one in flight")

        p.finish()
        runCurrent()
        val endedAt = testScheduler.currentTime
        advanceTimeBy(SYNC_CHANGES_REALTIME_DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals(listOf(endedAt + SYNC_CHANGES_REALTIME_DEBOUNCE_MILLIS), p.pulls.drop(1).map { it.atMillis })
        assertEquals(SYNC_CHANGES_REALTIME_TRIGGER, p.pulls.last().trigger)

        p.finish()
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(2, p.pulls.size)
    }

    @Test
    fun `another pull starting during the debounce or the flight covers the hints`() = runTest {
        val p = fixture()

        p.realtime.request(syncTestScope, "notification_retract")
        advanceTimeBy(100L)
        p.start("foreground")
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(listOf("foreground"), p.pulls.map { it.trigger }, "the waiting realtime pull went")
        p.finish()
        runCurrent()

        // A hint during its own pull, then another trigger pulls: that pull reads after the hint.
        p.realtime.request(syncTestScope, "message_absent")
        advanceTimeBy(SYNC_CHANGES_REALTIME_DEBOUNCE_MILLIS)
        runCurrent()
        p.realtime.request(syncTestScope, "message_absent")
        p.start("retry_after")
        p.finish()
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(listOf("foreground", "realtime", "retry_after"), p.pulls.map { it.trigger })
    }

    @Test
    fun `nothing pulls in the background or once the session is gone`() = runTest {
        var foreground = false
        val logs = mutableListOf<String>()
        val p = fixture(foreground = { foreground }, logs = logs)

        p.realtime.request(syncTestScope, "message_absent")
        advanceTimeBy(60_000L)
        runCurrent()
        assertTrue(p.pulls.isEmpty())
        assertFalse(p.realtime.isPending(syncTestScope))
        assertTrue(logs.any { it.contains("result=skipped reason=background") })

        // Backgrounded during the debounce: the waiting pull does not run either.
        foreground = true
        p.realtime.request(syncTestScope, "message_absent")
        foreground = false
        advanceTimeBy(60_000L)
        runCurrent()
        assertTrue(p.pulls.isEmpty())

        // Signed out during the debounce.
        foreground = true
        p.realtime.request(syncTestScope, "message_absent")
        p.realtime.cancelAll("signed_out")
        advanceTimeBy(60_000L)
        runCurrent()
        assertTrue(p.pulls.isEmpty())

        // The provider declined (server no longer selected): nothing waits on.
        p.allowed = false
        p.realtime.request(syncTestScope, "message_absent")
        advanceTimeBy(60_000L)
        runCurrent()
        assertTrue(p.pulls.isEmpty())
        assertFalse(p.realtime.isPending(syncTestScope))
        assertTrue(logs.any { it.contains("result=skipped reason=not_pulled") })
    }

    @Test
    fun `the pull logs the realtime trigger`() = runTest {
        val logs = mutableListOf<String>()
        val engine =
            RaftSyncChangesEngine(
                api = ScriptedSyncChangesApi(syncPage(syncChangesBody("c1.2.1"))),
                cursors = RaftSyncCursorStore(SyncTestPreferences()).also { it.write(syncTestScope, "c1.1.1") },
                sink = RecordingSyncChangesSink(),
                logger = { logs += it },
            )
        val realtime =
            RaftSyncRealtimePull(
                scope = backgroundScope,
                pull = { key, trigger -> backgroundScope.launch { engine.pull(key, trigger) } },
            )

        realtime.request(syncTestScope, "notification_retract")
        advanceTimeBy(SYNC_CHANGES_REALTIME_DEBOUNCE_MILLIS)
        runCurrent()

        assertEquals(1, logs.count { it.startsWith("sync_changes_pull serverId=server-1 trigger=realtime result=synced") }, logs.joinToString("\n"))
    }
}
