package build.raft.app

import build.raft.app.network.SlockRequestTimeoutException
import com.tencent.tmm.kmmresource.resource.desc.ResourceStringDesc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
        assertEquals(
            "Send failed: server rejected the message body · Retry",
            threadSendFailureStatusLabel(
                errorCode = "validation_failed",
                errorMessage = " server rejected the message body "
            )
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
    fun userFacingErrorCodeMapsRawThreadCodesToTypedReasons() {
        mapOf(
            "auth_required" to ThreadUserFacingErrorReason.AuthenticationRequired,
            "thread_forbidden" to ThreadUserFacingErrorReason.PermissionDenied,
            "dns_lookup_failed" to ThreadUserFacingErrorReason.ServerUnreachable,
            "{\"error\":\"network_timeout\"}" to ThreadUserFacingErrorReason.RequestTimedOut,
            "thread_send_timeout" to ThreadUserFacingErrorReason.RequestTimedOut,
            "thread_network" to ThreadUserFacingErrorReason.NetworkFailed,
            "http_503" to ThreadUserFacingErrorReason.ServerUnavailable,
            "message_not_found" to ThreadUserFacingErrorReason.ItemNotFound,
            "attachment_too_large" to ThreadUserFacingErrorReason.AttachmentTooLarge,
            "rate_limited" to ThreadUserFacingErrorReason.RateLimited,
            "response_parse_failed" to ThreadUserFacingErrorReason.ResponseUnreadable,
            "thread_send_cancelled" to ThreadUserFacingErrorReason.RequestCancelled,
            "server detail must not be presented" to ThreadUserFacingErrorReason.Generic
        ).forEach { (code, expected) ->
            assertEquals(expected, threadUserFacingErrorCodeReason(code), code)
        }
        assertEquals(null, threadUserFacingErrorCodeReason("  "))
    }

    @Test
    fun userFacingErrorReasonUsesResourceBackedCopy() {
        ThreadUserFacingErrorReason.entries.forEach { reason ->
            assertIs<ResourceStringDesc>(reason.stringDesc())
        }
    }

    @Test
    fun failureReasonParsesReadableMessageFromErrorBody() {
        assertEquals(
            "Body is too large",
            threadFailureReasonMessage("""{"code":"body_too_large","errorMessage":"Body is too large"}""")
        )
        assertEquals(
            "body_too_large",
            threadFailureDiagnosticCode("""{"code":"body_too_large","errorMessage":"Body is too large"}""")
        )
        assertEquals("plain failure", threadFailureReasonMessage("plain failure"))
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

    @Test
    fun silentRetryOnlyTargetsTransientSendFailures() {
        assertEquals(true, threadSendShouldSilentlyRetry("thread_send_timeout"))
        assertEquals(true, threadSendShouldSilentlyRetry("thread_send_network"))
        assertEquals(true, threadSendShouldSilentlyRetry(null, httpCode = 500))
        assertEquals(true, threadSendShouldSilentlyRetry(null, httpCode = 429))
        assertEquals(false, threadSendShouldSilentlyRetry("thread_send_cancelled"))
        assertEquals(false, threadSendShouldSilentlyRetry("body_too_large"))
        assertEquals(false, threadSendShouldSilentlyRetry(null, httpCode = 400))
    }
}
