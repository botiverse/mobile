package build.raft.app

import build.raft.app.resources.*
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.ThreadOpenFailure
import build.raft.app.thread.threadOpenFailureLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Each local thread-open failure shows its own plain reason and logs its own reason code (artin 11-03). */
class ThreadOpenFailureTest {
    @Test
    fun eachFailurePicksItsOwnMessageAndLogReason() {
        val expected =
            mapOf(
                ThreadOpenFailure.MissingIdentity to (Res.string.thread_error_open_thread_missing_identity to "missing_identity"),
                ThreadOpenFailure.StaleSource to (Res.string.thread_error_open_thread_stale_source to "stale_source"),
                ThreadOpenFailure.MissingScope to (Res.string.thread_error_open_thread_missing_scope to "missing_scope"),
            )
        assertEquals(ThreadOpenFailure.entries.toSet(), expected.keys)
        val payload = RaftThreadScreenPayload(instanceId = "inst-1")
        for ((failure, pair) in expected) {
            val (message, reason) = pair
            assertEquals(message, failure.message)
            val log = threadOpenFailureLog(failure, payload)
            assertTrue(log.startsWith("thread_open_failed reason=$reason "), log)
        }
    }
}
