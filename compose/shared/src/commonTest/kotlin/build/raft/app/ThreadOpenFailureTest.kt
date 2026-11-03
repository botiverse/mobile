package build.raft.app

import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun logSaysWhichRouteIdsWerePresentWithoutTheirValues() {
        val payload =
            RaftThreadScreenPayload(instanceId = "inst-2", parentChannelId = "channel-secret", parentMessageId = " ")
        val fields =
            threadOpenFailureLog(ThreadOpenFailure.MissingIdentity, payload)
                .split(' ').drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }

        assertEquals("inst-2", fields["instanceId"])
        assertEquals("1", fields["parentChannelId"])
        assertEquals("0", fields["parentMessageId"])
        assertEquals("0", fields["sourceThreadId"])
        assertEquals("0", fields["streamChannelId"])
        assertFalse(fields.values.any { "channel-secret" in it })
    }
}
