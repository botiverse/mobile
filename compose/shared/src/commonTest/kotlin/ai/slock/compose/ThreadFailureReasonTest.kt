package ai.slock.compose

import ai.slock.compose.network.SlockRequestTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThreadFailureReasonTest {
    @Test
    fun sendFailureStatusIncludesSanitizedReason() {
        assertEquals("Send failed · Retry", threadSendFailureStatusLabel(null))
        assertEquals(
            "Send failed: upload_failed · Retry",
            threadSendFailureStatusLabel(" upload_failed ")
        )
        assertEquals(
            "Send failed: request timed out · Retry",
            threadSendFailureStatusLabel("thread_send_timeout")
        )
        assertEquals(
            "Send failed: request cancelled · Retry",
            threadSendFailureStatusLabel("thread_send_cancelled")
        )
    }

    @Test
    fun failureReasonRedactsSecretsAndTruncatesLongBodies() {
        val label =
            threadFailureReasonLabel(
                "server rejected Bearer abc.def.ghi and sk_agent_secretvalue " +
                    "because the body was malformed".repeat(8)
            )

        assertTrue(label!!.contains("Bearer <redacted>"))
        assertTrue(label.contains("sk_agent_<redacted>"))
        assertTrue(label.length <= 120)
    }

    @Test
    fun sendExceptionErrorCodeMapsCommonFailureTypes() {
        assertEquals(
            "thread_send_timeout",
            threadSendExceptionErrorCode(SlockRequestTimeoutException("timed out"))
        )
        assertEquals(
            "thread_send_cancelled",
            threadSendExceptionErrorCode(IllegalStateException("request cancelled"))
        )
        assertEquals(
            "thread_send_exception",
            threadSendExceptionErrorCode(IllegalArgumentException("unexpected"))
        )
    }
}
