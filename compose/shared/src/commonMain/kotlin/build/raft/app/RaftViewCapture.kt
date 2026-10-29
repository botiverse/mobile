package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handle to a composable subtree that can be snapshotted into an image file (CMP replacement for
 * Kuikly `nativeRef` + `DeclarativeBaseView.toImage(ImageType.FILE, 1)`).
 *
 * Obtain it with [Modifier.raftCaptureRef]; the subtree is recorded into a [GraphicsLayer] on every
 * draw, so [captureToPngFile] snapshots exactly the pixels last drawn (device resolution).
 */
class RaftViewCaptureRef internal constructor(
    internal val layer: GraphicsLayer,
    private val writer: RaftPngFileWriter,
    private val render: (suspend () -> ImageBitmap?)? = null,
) {
    /**
     * Encodes the current layer content as PNG into an app-cache temporary file. The returned
     * [RaftViewCaptureResult.fileRef] is a `file://` URI owned by the caller (retire it through the
     * normal temporary-file retirement path).
     *
     * When the owner supplied a renderer (see [raftCaptureRef]) its image is encoded instead of the
     * on-screen pixels, e.g. the image editor re-rendering at the photo's own resolution. A
     * renderer that fails or returns null falls back to the on-screen snapshot.
     */
    suspend fun captureToPngFile(): RaftViewCaptureResult {
        if (layer.isReleased || layer.size == IntSize.Zero) {
            return RaftViewCaptureResult(fileRef = null, errorMessage = "capture view not laid out")
        }
        val rendered =
            render?.let { renderer ->
                try {
                    renderer()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // Includes OutOfMemoryError on the JVM: the screen-sized snapshot still saves.
                    null
                }
            }
        val bitmap =
            rendered ?: try {
                layer.toImageBitmap()
            } catch (error: Throwable) {
                return RaftViewCaptureResult(fileRef = null, errorMessage = error.message)
            }
        return withContext(Dispatchers.Default) {
            try {
                val fileRef = writer.writePng(bitmap, RAFT_VIEW_CAPTURE_FILE_PREFIX)
                if (fileRef.isNullOrBlank()) {
                    RaftViewCaptureResult(fileRef = null, errorMessage = "snapshot encode failed")
                } else {
                    RaftViewCaptureResult(fileRef = fileRef, errorMessage = null)
                }
            } catch (error: Throwable) {
                RaftViewCaptureResult(fileRef = null, errorMessage = error.message)
            }
        }
    }
}

/** Outcome of [RaftViewCaptureRef.captureToPngFile]: a file ref on success, else an error. */
data class RaftViewCaptureResult(
    val fileRef: String?,
    val errorMessage: String?,
)

/** Platform PNG encoder + app-cache temporary file writer; returns a `file://` URI or null. */
fun interface RaftPngFileWriter {
    fun writePng(image: ImageBitmap, namePrefix: String): String?
}

@Composable
internal expect fun rememberRaftPngFileWriter(): RaftPngFileWriter

private const val RAFT_VIEW_CAPTURE_FILE_PREFIX = "raft_snapshot_"

/**
 * Kuikly `nativeRef` stand-in for snapshotting: records this node's drawing into a graphics layer
 * and hands a [RaftViewCaptureRef] to [onRef] (again whenever [onRef] changes identity).
 *
 * [render], when given, produces the image to save instead of the on-screen pixels; the latest
 * lambda is used at capture time.
 */
fun Modifier.raftCaptureRef(
    onRef: (RaftViewCaptureRef) -> Unit,
    render: (suspend () -> ImageBitmap?)? = null,
): Modifier =
    composed {
        val layer = rememberGraphicsLayer()
        val writer = rememberRaftPngFileWriter()
        val latestRender = rememberUpdatedState(render)
        val hasRender = render != null
        val ref =
            remember(layer, writer, hasRender) {
                RaftViewCaptureRef(
                    layer = layer,
                    writer = writer,
                    render = if (hasRender) suspend { latestRender.value?.invoke() } else null,
                )
            }
        LaunchedEffect(ref, onRef) { onRef(ref) }
        drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }
    }
