package build.raft.app

import build.raft.app.data.ThreadApiResult
import build.raft.app.network.RaftRequestTimeoutException
import build.raft.app.resources.ResourceStringDesc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ThreadFailureReasonTest {
    private val labels =
        ThreadUserFacingErrorLabels(
            ThreadUserFacingErrorReason.entries.associateWith { reason -> "localized:${reason.name}" }
        )

    @Test
    fun sendFailureSummaryUsesTypedReasonAndNeverRawMessage() {
        assertEquals(
            "localized:Generic",
            threadSendFailureSummary(null, labels)
        )
        assertEquals(
            "localized:RequestTimedOut",
            threadSendFailureSummary("thread_send_timeout", labels)
        )
        assertEquals(
            "localized:RequestCancelled",
            threadSendFailureSummary("thread_send_cancelled", labels)
        )
        assertEquals(
            "localized:Generic",
            threadSendFailureSummary("server rejected Bearer secret", labels)
        )
    }

    @Test
    fun failureDiagnosticDetailRedactsSecretsAndPreservesCompleteLongBody() {
        val longTail = "because-the-body-was-malformed-".repeat(12)
        val detail = threadFailureDiagnosticDetail(
            errorCode = "thread_send_exception",
            errorMessage =
                "Bearer abc.def.ghi sk_agent_secretvalue sk_machine_machinevalue " +
                    "Authorization: Basic auth-secret Cookie=session-secret;other-secret api_key=key-secret " +
                    "https://example.test/upload?access_token=query-secret&sig=signed-secret " +
                    "/home/alice/private/message.txt C:\\Users\\Alice\\token.txt $longTail"
        )

        assertTrue(detail!!.contains("Bearer <redacted>"))
        assertTrue(detail.contains("sk_agent_<redacted>"))
        assertTrue(detail.contains("sk_machine_<redacted>"))
        assertTrue(detail.contains("Authorization: <redacted>"))
        assertTrue(detail.contains("Cookie=<redacted>"))
        assertTrue(detail.contains("api_key=<redacted>"))
        assertTrue(detail.contains("access_token=<redacted>"))
        assertTrue(detail.contains("sig=<redacted>"))
        assertTrue(detail.contains("<absolute-path-redacted>"))
        assertTrue(detail.contains(longTail))
        assertTrue(detail.length > 120)
        assertTrue("abc.def.ghi" !in detail)
        assertTrue("auth-secret" !in detail)
        assertTrue("session-secret" !in detail)
        assertTrue("other-secret" !in detail)
        assertTrue("query-secret" !in detail)
    }

    @Test
    fun legacyFailureReasonLabelStaysBoundedWhileDiagnosticDetailRemainsComplete() {
        val longReason = "safe-diagnostic-".repeat(20)
        val legacyLabel = threadFailureReasonLabel(longReason)
        val diagnosticDetail = threadFailureDiagnosticDetail("thread_send_exception", longReason)

        assertEquals(120, legacyLabel?.length)
        assertTrue(legacyLabel!!.endsWith("..."))
        assertTrue(diagnosticDetail!!.contains(longReason))
        assertTrue(diagnosticDetail.length > 120)
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
            "server_missing" to ThreadUserFacingErrorReason.ServerUnavailable,
            "message_not_found" to ThreadUserFacingErrorReason.ItemNotFound,
            "attachment_too_large" to ThreadUserFacingErrorReason.AttachmentTooLarge,
            "upload_too_large" to ThreadUserFacingErrorReason.AttachmentTooLarge,
            "FILE_UPLOAD_QUOTA_EXCEEDED" to ThreadUserFacingErrorReason.AttachmentMonthlyQuotaExceeded,
            "UPLOAD_QUOTA_EXCEEDED" to ThreadUserFacingErrorReason.AttachmentMonthlyQuotaExceeded,
            "external_limit_frozen" to ThreadUserFacingErrorReason.ChannelReadOnly,
            "channel_read_only" to ThreadUserFacingErrorReason.ChannelReadOnly,
            "quota_read_only" to ThreadUserFacingErrorReason.ChannelReadOnly,
            "channel_archived" to ThreadUserFacingErrorReason.ChannelArchived,
            "UPLOAD_FORBIDDEN" to ThreadUserFacingErrorReason.PermissionDenied,
            "rate_limited" to ThreadUserFacingErrorReason.RateLimited,
            "response_parse_failed" to ThreadUserFacingErrorReason.ResponseUnreadable,
            "thread_send_cancelled" to ThreadUserFacingErrorReason.RequestCancelled,
            "server detail must not be presented" to ThreadUserFacingErrorReason.Generic
        ).forEach { (code, expected) ->
            assertEquals(expected, threadUserFacingErrorCodeReason(code), code)
        }
        assertEquals(null, threadUserFacingErrorCodeReason("  "))
        assertEquals(
            ThreadUserFacingErrorReason.Generic,
            threadUserFacingErrorCodeReason("body_too_large")
        )
        assertEquals(
            ThreadUserFacingErrorReason.Generic,
            threadUserFacingErrorCodeReason("attachment_size_unavailable")
        )
    }

    @Test
    fun deadAttachmentSendRefusalsAskToUploadAgainAndAreNeverRetried() {
        // botiverse/slock #15130 DEAD_ATTACHMENT_CODES (server attachmentLinkingService).
        listOf(
            "attachment_not_found",
            "attachment_already_linked",
            "attachment_not_linked",
            "attachment_expired",
            "attachment_canceled"
        ).forEach { code ->
            assertEquals(ThreadUserFacingErrorReason.AttachmentNeedsReupload, threadUserFacingErrorCodeReason(code), code)
            assertEquals("localized:AttachmentNeedsReupload", threadSendFailureSummary(code, labels), code)
            assertTrue(threadSendFailureNeedsAttachmentReupload(code), code)
        }
        // Other attachment refusals keep their own handling.
        listOf("attachment_id_invalid", "attachment_duplicate", "attachment_replay_conflict", "attachment_too_large", null)
            .forEach { code -> assertFalse(threadSendFailureNeedsAttachmentReupload(code), code.toString()) }
        assertEquals(ThreadUserFacingErrorReason.ItemNotFound, threadUserFacingErrorCodeReason("message_not_found"))
    }

    @Test
    fun deadAttachmentCodeIsReadFromTheServersSendRefusal() {
        // messages.ts `res.status(err.status).json({ error: err.message, code: err.code })`, 410, with the
        // attachmentLifecycleService message.
        val body = """{"error":"Attachment 7b0c0f9e-2f53-4d7e-9b0a-2b4a3e8c1d11 expired before the message was sent. Re-upload the file.","code":"attachment_expired"}"""
        val code = threadSendFailureErrorCode(contractCode = null, errorBodyText = body, httpCode = 410)
        assertEquals("attachment_expired", code)
        assertFalse(threadSendShouldSilentlyRetry(code, 410))
        assertTrue(threadSendFailureNeedsAttachmentReupload(code))
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
            threadSendExceptionErrorCode(RaftRequestTimeoutException("timed out"))
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

    @Test
    fun codeZeroConnectionLostCurlFailureRetriesWithOneStableRandomId() {
        val errorCode =
            threadSendFailureErrorCode(
                contractCode = null,
                errorBodyText =
                    "[connection_lost]_CURLcode:55_OpenSSL_SSL_read:_SSL_ERROR_SYSCALL_errno_0",
                httpCode = 0
            )
        val state = ThreadSendAttemptState(randomId = "rid-send-1", maxSilentRetries = 2)
        val sentRandomIds = mutableListOf<String?>()

        sentRandomIds += state.randomId
        assertTrue(state.advanceForSilentRetry(threadSendShouldSilentlyRetry(errorCode, httpCode = 0)))
        sentRandomIds += state.randomId
        assertTrue(state.advanceForSilentRetry(threadSendShouldSilentlyRetry(errorCode, httpCode = 0)))
        sentRandomIds += state.randomId

        assertEquals("thread_send_network", errorCode)
        assertEquals(listOf<String?>("rid-send-1", "rid-send-1", "rid-send-1"), sentRandomIds)
        assertEquals(3, state.attempt)
        assertEquals(
            false,
            state.advanceForSilentRetry(threadSendShouldSilentlyRetry(errorCode, httpCode = 0))
        )
    }

    @Test
    fun codeZeroTimeoutUsesTransientTimeoutClassification() {
        val errorCode =
            threadSendFailureErrorCode(
                contractCode = null,
                errorBodyText = """{"error":"network_timeout","message":"request timed out"}""",
                httpCode = 0
            )

        assertEquals("thread_send_timeout", errorCode)
        assertEquals(true, threadSendShouldSilentlyRetry(errorCode, httpCode = 0))
    }

    @Test
    fun arbitraryCodeZeroAndBusinessFailuresDoNotSilentlyRetry() {
        val unknownCodeZero =
            threadSendFailureErrorCode(
                contractCode = null,
                errorBodyText = "unexpected local failure",
                httpCode = 0
            )
        val validationFailure =
            threadSendFailureErrorCode(
                contractCode = null,
                errorBodyText = """{"code":"body_too_large","errorMessage":"Body is too large"}""",
                httpCode = 400
            )

        assertEquals("http_0", unknownCodeZero)
        assertEquals(false, threadSendShouldSilentlyRetry(unknownCodeZero, httpCode = 0))
        assertEquals("body_too_large", validationFailure)
        assertEquals(false, threadSendShouldSilentlyRetry(validationFailure, httpCode = 400))
    }

    @Test
    fun silentRetryRequiresAnIdempotencyIdentity() {
        val missingIdentity = ThreadSendAttemptState(randomId = null, maxSilentRetries = 2)
        val blankIdentity = ThreadSendAttemptState(randomId = " ", maxSilentRetries = 2)

        assertEquals(false, missingIdentity.advanceForSilentRetry(eligible = true))
        assertEquals(false, blankIdentity.advanceForSilentRetry(eligible = true))
        assertEquals(1, missingIdentity.attempt)
        assertEquals(1, blankIdentity.attempt)
    }

    @Test
    fun uploadFailureCodePrefersStructuredServerCodeExceptForAuthBlockers() {
        assertEquals(
            "FILE_UPLOAD_QUOTA_EXCEEDED",
            resolveThreadAttachmentUploadFailureCode(
                build.raft.app.data.DataResult.Error(
                    build.raft.app.data.ScreenError(
                        code = "http_403",
                        retryable = false,
                        serverCode = "FILE_UPLOAD_QUOTA_EXCEEDED",
                    )
                )
            )
        )
        assertEquals(
            "FILE_UPLOAD_QUOTA_EXCEEDED",
            resolveThreadAttachmentUploadFailureCode(
                build.raft.app.data.DataResult.NoPermission(
                    build.raft.app.data.PermissionBlocker(
                        code = "server_forbidden",
                        requiredCapability = "server_access",
                        serverCode = "FILE_UPLOAD_QUOTA_EXCEEDED",
                    )
                )
            )
        )
        assertEquals(
            "UPLOAD_FORBIDDEN",
            resolveThreadAttachmentUploadFailureCode(
                build.raft.app.data.DataResult.NoPermission(
                    build.raft.app.data.PermissionBlocker(
                        code = "server_forbidden",
                        requiredCapability = "server_access",
                        serverCode = "UPLOAD_FORBIDDEN",
                    )
                )
            )
        )
        assertEquals(
            "auth_required",
            resolveThreadAttachmentUploadFailureCode(
                build.raft.app.data.DataResult.NoPermission(
                    build.raft.app.data.PermissionBlocker(
                        code = "auth_required",
                        requiredCapability = "auth_session",
                        serverCode = "token_expired",
                    )
                )
            )
        )
        assertEquals(
            "attachment_upload_batch_empty",
            resolveThreadAttachmentUploadFailureCode(
                build.raft.app.data.DataResult.Empty(build.raft.app.data.EmptyReason.NoMessages),
                emptyCode = "attachment_upload_batch_empty",
            )
        )
    }

    @Test
    fun threadDataErrorClassifies403PlanAndReadOnlyCodesAsNonPermissionErrors() {
        val frozen =
            ThreadApiResult.Failure(
                code = 403,
                errorBodyText = """{"error":"Frozen","code":"external_limit_frozen"}""",
                contractCode = "external_limit_frozen",
            ).toThreadDataError("thread_send_failed")
        val frozenError = assertIs<build.raft.app.data.DataResult.Error>(frozen).error
        assertEquals("external_limit_frozen", frozenError.code)
        assertEquals("external_limit_frozen", frozenError.serverCode)
        assertFalse(frozenError.retryable)

        val readOnly =
            ThreadApiResult.Failure(
                code = 403,
                errorBodyText = """{"error":"Read-only","code":"channel_read_only"}""",
                contractCode = "channel_read_only",
            ).toThreadDataError("thread_send_failed")
        val readOnlyError = assertIs<build.raft.app.data.DataResult.Error>(readOnly).error
        assertEquals("channel_read_only", readOnlyError.code)
        assertFalse(readOnlyError.retryable)

        val forbidden =
            ThreadApiResult.Failure(
                code = 403,
                errorBodyText = """{"error":"Access denied"}""",
                contractCode = null,
            ).toThreadDataError("thread_send_failed")
        val blocker = assertIs<build.raft.app.data.DataResult.NoPermission>(forbidden).blocker
        assertEquals("thread_forbidden", blocker.code)
    }
}
