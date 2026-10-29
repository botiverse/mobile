package build.raft.app.desktop

import build.raft.app.data.RaftDesktopPickedAttachments
import build.raft.app.data.RaftDesktopThreadImageCompressor
import build.raft.app.network.AttachmentDownloadCoordinator
import build.raft.app.network.AttachmentDownloadObserver
import build.raft.app.network.AttachmentDownloadProgress
import build.raft.app.network.AttachmentDownloadResult
import build.raft.app.toRaftJsonString
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Desktop attachment bridge methods, on the Android host's wire contract:
 * - `threadAttachmentUpload`: the system open panel (several files; images only for the image
 *   button). There is no camera on desktop: `capture` answers "picker unavailable".
 * - `compressThreadImage` / `cancelCompressThreadImage`: the Skia compressor.
 * - `saveAttachment` / `cancelAttachmentExport`: the system save panel, then the shared download
 *   coordinator writes the file where the user chose (atomically: nothing appears until it is
 *   complete).
 */
class RaftDesktopAttachments(
    private val dialogs: RaftDesktopFileDialogs = RaftFileKitDialogs,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val startDownload: (downloadId: String, target: File, url: String, observer: AttachmentDownloadObserver) -> Unit =
        { downloadId, target, url, observer ->
            AttachmentDownloadCoordinator.start(
                downloadId = downloadId,
                targetKey = target.absolutePath,
                url = url,
                targetPath = target.absolutePath,
                observer = observer,
            )
        },
) {
    private val pickerOpen = AtomicBoolean(false)
    private val cancelledExports = ConcurrentHashMap.newKeySet<String>()

    fun threadAttachmentUpload(params: JsonObject, callback: ((String?) -> Unit)?) {
        if (params.boolean("capture")) {
            callback?.invoke(pickerFailure(code = 0, errorCode = "attachment_picker_unavailable", message = "No camera on desktop"))
            return
        }
        if (!pickerOpen.compareAndSet(false, true)) {
            callback?.invoke(pickerFailure(code = 409, errorCode = "attachment_picker_busy", message = "Attachment picker already open"))
            return
        }
        val imagesOnly = params.boolean("imageOnly")
        scope.launch {
            val payload =
                try {
                    RaftDesktopPickedAttachments.response(dialogs.openFiles(imagesOnly)).toRaftJsonString()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    log("attachment picker failed: ${error::class.simpleName}")
                    pickerFailure(code = 0, errorCode = "attachment_picker_unavailable", message = "Attachment picker unavailable")
                } finally {
                    pickerOpen.set(false)
                }
            callback?.invoke(payload)
        }
    }

    fun compressThreadImage(params: JsonObject, callback: ((String?) -> Unit)?) {
        if (callback == null) return
        val request =
            RaftDesktopThreadImageCompressor.request(
                requestId = params.string("requestId"),
                fileRef = params.string("fileRef"),
                fileName = params.string("fileName"),
                mimeType = params.string("mimeType"),
            )
        if (request == null) {
            callback(RaftDesktopThreadImageCompressor.failure().toRaftJsonString())
            return
        }
        scope.launch {
            // Null: cancelled, and a cancelled request gets no callback (the Android contract).
            RaftDesktopThreadImageCompressor.run(request)?.let { callback(it.toRaftJsonString()) }
        }
    }

    fun cancelCompressThreadImage(params: JsonObject) = RaftDesktopThreadImageCompressor.cancel(params.string("requestId"))

    fun saveAttachment(params: JsonObject, callback: ((String?) -> Unit)?) {
        val url = params.string("url")
        val downloadId = params.string("downloadId").ifBlank { "desktop-save-${System.nanoTime()}" }
        val operationId = params.string("operationId")
        val trackProgress = params.boolean("trackProgress")
        if (url.isBlank()) {
            callback?.invoke(status("failed", "Unable to download attachment"))
            return
        }
        scope.launch {
            val target =
                try {
                    dialogs.saveFile(params.string("fileName"))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    log("save panel failed: ${error::class.simpleName}")
                    callback?.invoke(status("storage_failed", "Save panel unavailable"))
                    return@launch
                }
            if (target == null || (operationId.isNotEmpty() && cancelledExports.remove(operationId))) {
                callback?.invoke(status("cancelled"))
                return@launch
            }
            val finished = AtomicBoolean(false)
            val observer =
                object : AttachmentDownloadObserver {
                    override fun onProgress(progress: AttachmentDownloadProgress) {
                        if (trackProgress && !finished.get()) callback?.invoke(progress.toBridgePayload())
                    }

                    override fun onComplete(result: AttachmentDownloadResult) {
                        if (!finished.compareAndSet(false, true)) return
                        if (operationId.isNotEmpty()) cancelledExports.remove(operationId)
                        callback?.invoke(saveResult(result, target))
                    }
                }
            runCatching { startDownload(downloadId, target, url, observer) }
                .onFailure {
                    if (finished.compareAndSet(false, true)) callback?.invoke(status("download_failed", "Unable to download attachment"))
                }
        }
    }

    /**
     * A cancel while the save panel is still open: the panel cannot be closed from here, so its
     * answer is dropped. A running download is cancelled by the shared side (download coordinator).
     */
    fun cancelAttachmentExport(params: JsonObject) {
        params.string("operationId").takeIf { it.isNotEmpty() }?.let(cancelledExports::add)
    }

    private fun pickerFailure(code: Int, errorCode: String, message: String): String =
        buildJsonObject {
            put("code", code)
            put("bodyText", "")
            put("errorCode", errorCode)
            put("errorBodyText", message)
        }.toRaftJsonString()

    companion object {
        internal fun status(status: String, message: String? = null): String =
            buildJsonObject {
                put("status", status)
                message?.let { put("message", it) }
            }.toRaftJsonString()

        /** The coordinator's terminal result as the `saveAttachment` answer. */
        internal fun saveResult(result: AttachmentDownloadResult, target: File): String =
            when (result.status) {
                "complete" ->
                    buildJsonObject {
                        put("status", "saved")
                        put("uri", target.toURI().toString())
                        put("sizeBytes", target.length().toString())
                    }.toRaftJsonString()
                "cancelled" -> status("cancelled")
                else -> status("download_failed", result.message ?: result.errorKind ?: "Attachment download failed")
            }

        /** One progress sample, as Android's `raftTransferBridgePayload`. */
        internal fun AttachmentDownloadProgress.toBridgePayload(): String =
            buildJsonObject {
                put("downloadId", downloadId)
                put("attemptToken", attemptToken)
                put("sequence", sequence)
                put("phase", phase)
                put("status", "progress")
                put("downloadedBytes", transfer.bytesDone)
                transfer.bytesTotal?.let { put("totalBytes", it) }
                transfer.percent?.let { put("percent", it) }
                transfer.bytesPerSecond?.let { put("bytesPerSecond", it) }
            }.toRaftJsonString()
    }
}

private fun JsonObject.boolean(key: String): Boolean = string(key).equals("true", ignoreCase = true)
