package build.raft.app

import build.raft.app.data.ExternalInviteRefusal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * slock #15771: a channel conversion refusal no flow has a sentence for (409
 * `channel_conversion_too_large`, which names the counts) reads the server's own words, not the
 * generic "Something went wrong"; refusals a flow words itself keep its sentence.
 */
class ChannelConversionServerWordsTest {
    private val tooLarge =
        "This channel has 160 threads, which is more than can be moved to a shared channel right now. Nothing was changed. Start a new shared channel instead."

    @Test
    fun theServersWordsAreUsedOnlyForAConversionRefusalTheFlowFellBackOn() {
        assertEquals(tooLarge, channelConversionServerWords("channel_conversion_too_large", tooLarge, localFallback = true))
        assertNull(channelConversionServerWords("channel_conversion_too_large", tooLarge, localFallback = false))
        assertNull(channelConversionServerWords("internal_error", "boom", localFallback = true))
        assertNull(channelConversionServerWords("channel_conversion_too_large", "  ", localFallback = true))
        assertNull(channelConversionServerWords(null, tooLarge, localFallback = true))
    }

    @Test
    fun anExternalInviteSendRefusedForAConversionShowsTheServersSentence() {
        val inProgress = "This channel is being converted. Try again when the conversion has finished."
        val failure = ExternalInviteRefusal(409, "channel_conversion_too_large", tooLarge, traceId = "t1").toFailure()
        assertEquals(ExternalInviteError.Generic, failure.error)
        assertEquals(tooLarge, failure.serverMessage)
        assertEquals("t1", failure.traceId)
        assertEquals(inProgress, ExternalInviteRefusal(409, "channel_conversion_in_progress", inProgress).toFailure().serverMessage)
        // A refusal the dialog has its own sentence for keeps it.
        val dailyCap = ExternalInviteRefusal(429, "daily_cap", "Too many").toFailure()
        assertEquals(ExternalInviteError.DailyCap, dailyCap.error)
        assertNull(dailyCap.serverMessage)
    }
}
