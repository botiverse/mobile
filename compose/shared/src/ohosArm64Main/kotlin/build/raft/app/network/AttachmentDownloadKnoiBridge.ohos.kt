package build.raft.app.network

import com.tencent.tmm.knoi.annotation.KNExport
import com.tencent.tmm.knoi.annotation.ServiceConsumer
import com.tencent.tmm.knoi.service.KNOI

private const val SlockOhosAttachmentDownloadObserverService = "slockOhosAttachmentDownloadObserver"

@ServiceConsumer(name = SlockOhosAttachmentDownloadObserverService)
interface SlockOhosAttachmentDownloadObserverSink {
    fun onProgress(payload: Map<String, Any?>)

    fun onComplete(payload: Map<String, Any?>)
}

@KNExport
fun slockAttachmentDownloadStart(
    hostRequestKey: String,
    downloadId: String,
    targetKey: String,
    url: String,
    targetPath: String
): String =
    AttachmentDownloadCoordinator.start(
        downloadId = downloadId,
        targetKey = targetKey,
        url = url,
        targetPath = targetPath,
        observer = OhosKnoiAttachmentDownloadObserver(hostRequestKey)
    )

@KNExport
fun slockAttachmentDownloadControl(downloadId: String, expectedAttemptToken: String, action: String): String =
    AttachmentDownloadCoordinator.control(downloadId, expectedAttemptToken, action)

private class OhosKnoiAttachmentDownloadObserver(
    private val hostRequestKey: String
) : AttachmentDownloadObserver {
    private val service: SlockOhosAttachmentDownloadObserverSink
        get() = KNOI.get(SlockOhosAttachmentDownloadObserverService)

    override fun onProgress(progress: AttachmentDownloadProgress) {
        service.onProgress(
            buildMap {
                put("hostRequestKey", hostRequestKey)
                put("downloadId", progress.downloadId)
                put("attemptToken", progress.attemptToken)
                put("sequence", progress.sequence)
                put("phase", progress.phase)
                put("status", "progress")
                put("downloadedBytes", progress.downloadedBytes)
                progress.totalBytes?.let { put("totalBytes", it) }
                progress.percent?.let { put("percent", it) }
                progress.bytesPerSecond?.let { put("bytesPerSecond", it) }
            }
        )
    }

    override fun onComplete(result: AttachmentDownloadResult) {
        service.onComplete(
            buildMap {
                put("hostRequestKey", hostRequestKey)
                put("downloadId", result.downloadId)
                put("attemptToken", result.attemptToken)
                put("status", result.status)
                put("downloadedBytes", result.downloadedBytes)
                result.totalBytes?.let { put("totalBytes", it) }
                result.contentType?.let { put("contentType", it) }
                result.errorKind?.let { put("errorKind", it) }
                result.message?.let { put("message", it) }
            }
        )
    }
}
