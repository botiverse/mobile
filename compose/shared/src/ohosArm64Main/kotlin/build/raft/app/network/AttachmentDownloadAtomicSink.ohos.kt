package build.raft.app.network

import com.tencent.tmm.knoi.annotation.ServiceConsumer
import com.tencent.tmm.knoi.service.KNOI
import platform.posix.usleep

internal const val SlockOhosAttachmentDownloadSinkService = "slockOhosAttachmentDownloadSink"

@ServiceConsumer(name = SlockOhosAttachmentDownloadSinkService)
internal interface SlockOhosAttachmentDownloadSink {
    fun begin(targetPath: String, attemptToken: String): Boolean

    fun write(attemptToken: String, bytes: ByteArray): Boolean

    fun commit(attemptToken: String): Boolean

    fun abort(attemptToken: String)

    fun onProgress(payload: Map<String, Any?>)

    fun onComplete(payload: Map<String, Any?>)
}

internal actual fun createAttachmentDownloadAtomicSink(
    targetPath: String,
    attemptToken: String
): AttachmentDownloadAtomicSink = OhosKnoiAttachmentDownloadAtomicSink(targetPath, attemptToken)

internal actual fun attachmentDownloadPauseMillis(durationMillis: Long) {
    usleep((durationMillis.coerceAtLeast(1L) * 1_000L).toUInt())
}

private class OhosKnoiAttachmentDownloadAtomicSink(
    private val targetPath: String,
    private val attemptToken: String
) : AttachmentDownloadAtomicSink {
    private val service: SlockOhosAttachmentDownloadSink
        get() = KNOI.get(SlockOhosAttachmentDownloadSinkService)

    override fun begin(): Boolean = runCatching { service.begin(targetPath, attemptToken) }.getOrDefault(false)

    override fun write(bytes: ByteArray): Boolean =
        runCatching { service.write(attemptToken, bytes) }.getOrDefault(false)

    override fun commit(): Boolean = runCatching { service.commit(attemptToken) }.getOrDefault(false)

    override fun abort() {
        runCatching { service.abort(attemptToken) }
    }
}
