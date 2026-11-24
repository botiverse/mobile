package build.raft.app

import build.raft.app.resources.*
import androidx.compose.runtime.Composable

private const val ThreadFailureReasonMaxLength = 120

private val ThreadFailureReasonKnownLabels =
    mapOf(
        "thread_send_timeout" to "request timed out",
        "thread_send_cancelled" to "request cancelled",
        "thread_send_network" to "network error",
        "thread_send_exception" to "unexpected error"
    )

internal enum class ThreadUserFacingErrorReason {
    AuthenticationRequired,
    PermissionDenied,
    ServerUnreachable,
    RequestTimedOut,
    NetworkFailed,
    ServerUnavailable,
    ItemNotFound,
    AttachmentTooLarge,
    AttachmentMonthlyQuotaExceeded,
    AttachmentImageCompressionFailed,
    AttachmentNeedsReupload,
    ChannelReadOnly,
    ChannelArchived,
    RateLimited,
    ResponseUnreadable,
    RequestCancelled,
    Generic
}

/**
 * Send refusals that mean an uploaded attachment id can never be linked again (server
 * attachmentLinkingService; web `DEAD_ATTACHMENT_CODES`, botiverse/slock #15130). Resending the
 * same ids fails every time: the file has to be uploaded again for a new id.
 */
private val ThreadSendDeadAttachmentCodes =
    setOf(
        "attachment_not_found",
        "attachment_already_linked",
        "attachment_not_linked",
        "attachment_expired",
        "attachment_canceled"
    )

/** Whether a send failed because one of its attachment ids is dead, so Retry can never succeed. */
internal fun threadSendFailureNeedsAttachmentReupload(errorCode: String?): Boolean =
    errorCode?.trim()?.lowercase() in ThreadSendDeadAttachmentCodes

private val ThreadUserFacingKnownReasons =
    mapOf(
        "thread_send_timeout" to ThreadUserFacingErrorReason.RequestTimedOut,
        "thread_send_cancelled" to ThreadUserFacingErrorReason.RequestCancelled,
        "thread_send_network" to ThreadUserFacingErrorReason.NetworkFailed,
        "thread_send_exception" to ThreadUserFacingErrorReason.Generic
    )

internal fun threadSendFailureSummary(
    errorCode: String?,
    labels: ThreadUserFacingErrorLabels
): String = labels.forErrorCode(errorCode) ?: labels.generic()

internal fun threadFailureReasonLabel(errorCode: String?): String? =
    errorCode
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        ?.takeIf { it.isNotBlank() }
        ?.let { value -> ThreadFailureReasonKnownLabels[value] ?: value }
        ?.let(::redactThreadFailureReason)
        ?.let { value ->
            if (value.length <= ThreadFailureReasonMaxLength) {
                value
            } else {
                value.take(ThreadFailureReasonMaxLength - 3).trimEnd() + "..."
            }
        }

internal fun threadFailureDiagnosticDetail(
    errorCode: String?,
    errorMessage: String?
): String? {
    val code = errorCode?.trim()?.takeIf { it.isNotBlank() }
    val message = errorMessage?.trim()?.takeIf { it.isNotBlank() }
    if (code == null && message == null) return null
    return buildString {
        code?.let { append("Code: ").append(it) }
        message?.let {
            if (isNotEmpty()) append('\n')
            append("Detail: ").append(it)
        }
    }.let(::redactThreadFailureReason)
}

internal fun threadUserFacingErrorCodeReason(errorCode: String?): ThreadUserFacingErrorReason? {
    val normalized =
        errorCode
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return null
    ThreadUserFacingKnownReasons[normalized]?.let { return it }
    if (normalized in ThreadSendDeadAttachmentCodes) return ThreadUserFacingErrorReason.AttachmentNeedsReupload
    return when {
        normalized == "auth_required" ||
            normalized == "auth_session" ||
            normalized == "unauthenticated" ||
            "auth" in normalized -> ThreadUserFacingErrorReason.AuthenticationRequired
        "dns" in normalized ||
            "host" in normalized ||
            "unreachable" in normalized -> ThreadUserFacingErrorReason.ServerUnreachable
        "timeout" in normalized ||
            "timed_out" in normalized -> ThreadUserFacingErrorReason.RequestTimedOut
        "network" in normalized ||
            "socket" in normalized ||
            normalized == "code_0" ||
            normalized == "0" -> ThreadUserFacingErrorReason.NetworkFailed
        "server_unavailable" in normalized ||
            normalized == "server_missing" ||
            "http_5" in normalized ||
            normalized.endsWith("_503") -> ThreadUserFacingErrorReason.ServerUnavailable
        "not_found" in normalized ||
            "http_404" in normalized -> ThreadUserFacingErrorReason.ItemNotFound
        normalized == "file_upload_quota_exceeded" ||
            normalized == "upload_quota_exceeded" ->
            ThreadUserFacingErrorReason.AttachmentMonthlyQuotaExceeded
        normalized == "external_limit_frozen" ||
            normalized == "channel_read_only" ||
            normalized == "quota_read_only" ->
            ThreadUserFacingErrorReason.ChannelReadOnly
        normalized == "channel_archived" ->
            ThreadUserFacingErrorReason.ChannelArchived
        "permission" in normalized ||
            "forbidden" in normalized ||
            "admin_required" in normalized ||
            "no_permission" in normalized -> ThreadUserFacingErrorReason.PermissionDenied
        normalized == "attachment_too_large" ||
            normalized == "upload_too_large" -> ThreadUserFacingErrorReason.AttachmentTooLarge
        normalized == "attachment_image_compression_failed" ->
            ThreadUserFacingErrorReason.AttachmentImageCompressionFailed
        "rate" in normalized ||
            "too_many" in normalized -> ThreadUserFacingErrorReason.RateLimited
        "contract" in normalized ||
            "parse" in normalized ||
            "mismatch" in normalized -> ThreadUserFacingErrorReason.ResponseUnreadable
        else -> ThreadUserFacingErrorReason.Generic
    }
}

internal fun ThreadUserFacingErrorReason.stringDesc(): StringDesc =
    when (this) {
        ThreadUserFacingErrorReason.AuthenticationRequired -> Res.string.thread_error_reason_auth.desc()
        ThreadUserFacingErrorReason.PermissionDenied -> Res.string.thread_error_reason_permission.desc()
        ThreadUserFacingErrorReason.ServerUnreachable -> Res.string.thread_error_reason_server_unreachable.desc()
        ThreadUserFacingErrorReason.RequestTimedOut -> Res.string.thread_error_reason_timeout.desc()
        ThreadUserFacingErrorReason.NetworkFailed -> Res.string.thread_error_reason_network.desc()
        ThreadUserFacingErrorReason.ServerUnavailable -> Res.string.thread_error_reason_server_unavailable.desc()
        ThreadUserFacingErrorReason.ItemNotFound -> Res.string.thread_error_reason_not_found.desc()
        ThreadUserFacingErrorReason.AttachmentTooLarge -> Res.string.thread_error_reason_attachment_too_large.desc()
        ThreadUserFacingErrorReason.AttachmentMonthlyQuotaExceeded -> Res.string.upgrade_dialog_upload_quota_title.desc()
        ThreadUserFacingErrorReason.AttachmentImageCompressionFailed ->
            Res.string.thread_error_reason_attachment_image_compression_failed.desc()
        ThreadUserFacingErrorReason.AttachmentNeedsReupload ->
            Res.string.thread_error_reason_attachment_needs_reupload.desc()
        ThreadUserFacingErrorReason.ChannelReadOnly -> Res.string.thread_error_reason_channel_read_only.desc()
        ThreadUserFacingErrorReason.ChannelArchived -> Res.string.thread_archived_notice.desc()
        ThreadUserFacingErrorReason.RateLimited -> Res.string.thread_error_reason_rate_limited.desc()
        ThreadUserFacingErrorReason.ResponseUnreadable -> Res.string.thread_error_reason_response_unreadable.desc()
        ThreadUserFacingErrorReason.RequestCancelled -> Res.string.thread_error_reason_cancelled.desc()
        ThreadUserFacingErrorReason.Generic -> Res.string.thread_error_reason_generic.desc()
    }

internal class ThreadUserFacingErrorLabels(
    private val labels: Map<ThreadUserFacingErrorReason, String>
) {
    init {
        require(labels.keys == ThreadUserFacingErrorReason.entries.toSet())
    }

    fun forErrorCode(errorCode: String?): String? =
        threadUserFacingErrorCodeReason(errorCode)?.let(labels::get)

    fun forReason(reason: ThreadUserFacingErrorReason): String = checkNotNull(labels[reason])

    fun generic(): String = checkNotNull(labels[ThreadUserFacingErrorReason.Generic])
}

@Composable
internal fun threadUserFacingErrorLabels(): ThreadUserFacingErrorLabels =
    ThreadUserFacingErrorLabels(
        mapOf(
            ThreadUserFacingErrorReason.AuthenticationRequired to
                ThreadUserFacingErrorReason.AuthenticationRequired.stringDesc().localized(),
            ThreadUserFacingErrorReason.PermissionDenied to
                ThreadUserFacingErrorReason.PermissionDenied.stringDesc().localized(),
            ThreadUserFacingErrorReason.ServerUnreachable to
                ThreadUserFacingErrorReason.ServerUnreachable.stringDesc().localized(),
            ThreadUserFacingErrorReason.RequestTimedOut to
                ThreadUserFacingErrorReason.RequestTimedOut.stringDesc().localized(),
            ThreadUserFacingErrorReason.NetworkFailed to
                ThreadUserFacingErrorReason.NetworkFailed.stringDesc().localized(),
            ThreadUserFacingErrorReason.ServerUnavailable to
                ThreadUserFacingErrorReason.ServerUnavailable.stringDesc().localized(),
            ThreadUserFacingErrorReason.ItemNotFound to
                ThreadUserFacingErrorReason.ItemNotFound.stringDesc().localized(),
            ThreadUserFacingErrorReason.AttachmentTooLarge to
                ThreadUserFacingErrorReason.AttachmentTooLarge.stringDesc().localized(),
            ThreadUserFacingErrorReason.AttachmentMonthlyQuotaExceeded to
                ThreadUserFacingErrorReason.AttachmentMonthlyQuotaExceeded.stringDesc().localized(),
            ThreadUserFacingErrorReason.AttachmentImageCompressionFailed to
                ThreadUserFacingErrorReason.AttachmentImageCompressionFailed.stringDesc().localized(),
            ThreadUserFacingErrorReason.AttachmentNeedsReupload to
                ThreadUserFacingErrorReason.AttachmentNeedsReupload.stringDesc().localized(),
            ThreadUserFacingErrorReason.ChannelReadOnly to
                ThreadUserFacingErrorReason.ChannelReadOnly.stringDesc().localized(),
            ThreadUserFacingErrorReason.ChannelArchived to
                ThreadUserFacingErrorReason.ChannelArchived.stringDesc().localized(),
            ThreadUserFacingErrorReason.RateLimited to
                ThreadUserFacingErrorReason.RateLimited.stringDesc().localized(),
            ThreadUserFacingErrorReason.ResponseUnreadable to
                ThreadUserFacingErrorReason.ResponseUnreadable.stringDesc().localized(),
            ThreadUserFacingErrorReason.RequestCancelled to
                ThreadUserFacingErrorReason.RequestCancelled.stringDesc().localized(),
            ThreadUserFacingErrorReason.Generic to
                ThreadUserFacingErrorReason.Generic.stringDesc().localized()
        )
    )
