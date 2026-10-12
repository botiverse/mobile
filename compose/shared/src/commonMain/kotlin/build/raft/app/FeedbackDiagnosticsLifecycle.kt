package build.raft.app

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Identity carried by every feedback diagnostics request and event. */
data class FeedbackDiagnosticsBinding(
    val accountId: String,
    val sessionGeneration: Long,
    val webViewGeneration: Long,
) {
    fun isValid(): Boolean =
        accountId.length in 1..256 &&
            accountId.none { it.code < 0x20 || it.code == 0x7f } &&
            sessionGeneration >= 0L &&
            webViewGeneration >= 0L
}

enum class FeedbackDiagnosticsRequestPhase {
    Preparing,
    Uploading,
    Done,
    Failed,
    Cancelled,
}

sealed interface FeedbackDiagnosticsLifecycleEvent {
    val requestId: String
    val binding: FeedbackDiagnosticsBinding

    data class Progress(
        override val requestId: String,
        override val binding: FeedbackDiagnosticsBinding,
        val sent: Long,
        val total: Long,
    ) : FeedbackDiagnosticsLifecycleEvent

    data class Done(
        override val requestId: String,
        override val binding: FeedbackDiagnosticsBinding,
        val ref: FeedbackDiagnosticsReference,
    ) : FeedbackDiagnosticsLifecycleEvent

    data class Failed(
        override val requestId: String,
        override val binding: FeedbackDiagnosticsBinding,
        val reason: FeedbackDiagnosticsFailureReason,
    ) : FeedbackDiagnosticsLifecycleEvent
}

data class FeedbackDiagnosticsReference(
    val r2Key: String,
    val filename: String,
    val contentType: String,
    val size: Long,
) {
    fun isValid(): Boolean =
        r2Key.isNotBlank() &&
            filename.isNotBlank() &&
            contentType == "application/zip" &&
            size in 1..FeedbackDiagnosticsLifecycle.MAX_BYTES
}

enum class FeedbackDiagnosticsFailureReason {
    GenerateFailed,
    TooLarge,
    UploadFailed,
}

/**
 * Small platform-neutral admission ledger for the optional diagnostics upload.
 *
 * Hosts perform archive generation and transport. This class owns only the request identity,
 * generation fences, monotonic progress, and one-shot terminal transition so a late native
 * callback can never create a second ticket after cancellation, close, logout, or WebView reuse.
 */
class FeedbackDiagnosticsLifecycle {
    private data class ActiveRequest(
        val requestId: String,
        val binding: FeedbackDiagnosticsBinding,
        var phase: FeedbackDiagnosticsRequestPhase,
        var total: Long = 0L,
        var sent: Long = 0L,
    )

    // Native upload callbacks can arrive from an IO thread while WebView/auth invalidation is
    // running on the UI thread. Keep the complete admission/terminal transition atomic so a
    // late progress or done callback cannot race cancellation into a second terminal event.
    private val lock = SynchronizedObject()
    private var active: ActiveRequest? = null

    fun start(requestId: String, binding: FeedbackDiagnosticsBinding): Boolean = synchronized(lock) {
        if (!FeedbackDiagnosticsBridge.validRequestId(requestId) || !binding.isValid()) return false
        if (active != null) return false
        active = ActiveRequest(requestId, binding, FeedbackDiagnosticsRequestPhase.Preparing)
        return true
    }

    fun beginUpload(requestId: String, binding: FeedbackDiagnosticsBinding, total: Long): Boolean = synchronized(lock) {
        val current = active ?: return false
        if (!matches(current, requestId, binding) || total !in 1..MAX_BYTES) return false
        current.phase = FeedbackDiagnosticsRequestPhase.Uploading
        current.total = total
        current.sent = 0L
        return true
    }

    fun progress(
        requestId: String,
        binding: FeedbackDiagnosticsBinding,
        sent: Long,
        total: Long,
    ): FeedbackDiagnosticsLifecycleEvent.Progress? = synchronized(lock) {
        val current = active ?: return null
        if (!matches(current, requestId, binding) || current.phase != FeedbackDiagnosticsRequestPhase.Uploading) return null
        if (total != current.total || total !in 1..MAX_BYTES || sent !in current.sent..total) return null
        current.sent = sent
        return FeedbackDiagnosticsLifecycleEvent.Progress(requestId, binding, sent, total)
    }

    fun done(
        requestId: String,
        binding: FeedbackDiagnosticsBinding,
        ref: FeedbackDiagnosticsReference,
    ): FeedbackDiagnosticsLifecycleEvent.Done? = synchronized(lock) {
        val current = active ?: return null
        if (!matches(current, requestId, binding) || current.phase != FeedbackDiagnosticsRequestPhase.Uploading || !ref.isValid()) return null
        current.phase = FeedbackDiagnosticsRequestPhase.Done
        active = null
        return FeedbackDiagnosticsLifecycleEvent.Done(requestId, binding, ref)
    }

    fun failed(
        requestId: String,
        binding: FeedbackDiagnosticsBinding,
        reason: FeedbackDiagnosticsFailureReason,
    ): FeedbackDiagnosticsLifecycleEvent.Failed? = synchronized(lock) {
        val current = active ?: return null
        if (!matches(current, requestId, binding) || current.phase == FeedbackDiagnosticsRequestPhase.Done || current.phase == FeedbackDiagnosticsRequestPhase.Failed || current.phase == FeedbackDiagnosticsRequestPhase.Cancelled) return null
        current.phase = FeedbackDiagnosticsRequestPhase.Failed
        active = null
        return FeedbackDiagnosticsLifecycleEvent.Failed(requestId, binding, reason)
    }

    /** Invalidates a request without producing a ticket or a terminal WebView event. */
    fun cancel(requestId: String, binding: FeedbackDiagnosticsBinding): Boolean = synchronized(lock) {
        val current = active ?: return false
        if (!matches(current, requestId, binding)) return false
        current.phase = FeedbackDiagnosticsRequestPhase.Cancelled
        active = null
        return true
    }

    fun invalidateBinding(binding: FeedbackDiagnosticsBinding) = synchronized(lock) {
        if (active?.binding == binding) active = null
    }

    fun phase(requestId: String, binding: FeedbackDiagnosticsBinding): FeedbackDiagnosticsRequestPhase? =
        synchronized(lock) { active?.takeIf { matches(it, requestId, binding) }?.phase }

    private fun matches(
        current: ActiveRequest,
        requestId: String,
        binding: FeedbackDiagnosticsBinding,
    ): Boolean = current.requestId == requestId && current.binding == binding

    companion object {
        const val MAX_BYTES: Long = 200L * 1024L * 1024L
    }
}
