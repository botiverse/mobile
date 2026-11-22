package build.raft.app.core.data.diagnostics

import build.hands.update.HandsFeedbackException
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [submitRendererGoneTicket]: accepted only with a ticket id, one bounded retry, never a double submit. */
class RaftWebViewRendererGoneReporterTest {
    private val waits = mutableListOf<Long>()
    private val logs = mutableListOf<String>()

    private suspend fun send(vararg outcomes: () -> String): Pair<Boolean, Int> {
        var calls = 0
        val accepted =
            submitRendererGoneTicket(
                submit = { outcomes[calls++]() },
                log = { _, line -> logs += line },
                wait = { waits += it },
            )
        return accepted to calls
    }

    @Test
    fun anAcceptedTicketIsSubmittedOnce() = runTest {
        val (accepted, calls) = send({ "ticket-1" })
        assertTrue(accepted)
        assertEquals(1, calls)
        assertTrue(waits.isEmpty())
    }

    @Test
    fun aRetryableFailureIsRetriedOnceAfterTheDelay() = runTest {
        val (accepted, calls) = send({ throw IOException("offline") }, { "ticket-1" })
        assertTrue(accepted)
        assertEquals(2, calls)
        assertEquals(listOf(RendererGoneTicketRetryDelayMillis), waits)
    }
}
