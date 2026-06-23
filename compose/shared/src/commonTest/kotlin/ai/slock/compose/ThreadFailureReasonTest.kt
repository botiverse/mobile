package ai.slock.compose

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
}
