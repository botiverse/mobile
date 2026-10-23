package build.raft.app.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import coil3.ImageLoader
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.decode.SkiaImageDecoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.use
import org.jetbrains.skia.AnimationDisposalMode
import org.jetbrains.skia.AnimationFrameInfo
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Image as SkiaImage

/**
 * Largest animation played (pixels per frame): it costs two full frames of memory, the decode
 * buffer and the frame on screen. Larger ones show their first frame, as before.
 */
private const val RAFT_ANIMATED_MAX_PIXELS = 2048L * 2048L

internal actual fun raftPlatformAnimatedImageDecoderFactory(): Decoder.Factory? = RaftSkiaAnimatedImageDecoder.Factory()

/**
 * iOS animated GIF / WebP decoder (coil-gif has no iOS artifact). Keeps the encoded bytes in a
 * Skia [Codec] and decodes only the first frame here; [RaftImageAnimationEffect] decodes the rest
 * one at a time while the image is on screen. Single-frame, oversized or unreadable files go
 * through Coil's ordinary [SkiaImageDecoder].
 */
internal class RaftSkiaAnimatedImageDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {
    override suspend fun decode(): DecodeResult {
        val bytes = source.source().use { it.readByteArray() }
        RaftSkiaAnimatedImage.createOrNull(bytes)?.let { image ->
            return DecodeResult(image = image, isSampled = false)
        }
        return SkiaImageDecoder(ImageSource(Buffer().write(bytes), options.fileSystem), options).decode()
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder =
            RaftSkiaAnimatedImageDecoder(result.source, options)
    }
}

/**
 * A playing animation as a Coil [coil3.Image]. Coil's `ImagePainter` calls [draw], which reads
 * the current frame from snapshot state, so a new frame redraws the existing `Image` composable
 * (and its zoom / pan / swipe gestures) without recomposing. Not shareable: Coil never puts it
 * in the memory cache.
 */
internal class RaftSkiaAnimatedImage private constructor(
    private val codec: Codec,
    private val frames: Array<AnimationFrameInfo>,
) : coil3.Image {
    val player = RaftAnimatedFramePlayer(frames.map { it.duration }, codec.repetitionCount)

    override val width: Int = codec.width
    override val height: Int = codec.height

    /** The decode buffer plus the frame on screen. */
    override val size: Long get() = width.toLong() * height * 4 * 2

    override val shareable: Boolean get() = false

    private val decodeLock = Mutex()
    private val canvasBitmap = Bitmap()
    private var bitmapAllocated = false

    /** The frame the canvas bitmap holds, for incremental decodes; -1 when it holds none. */
    private var decodedIndex = -1

    private var frame by mutableStateOf<SkiaImage?>(null)

    /** Decodes frame [index] off the main thread and puts it on screen. */
    suspend fun showFrame(index: Int) {
        val decoded = withContext(Dispatchers.Default) { decodeLock.withLock { decodeFrameLocked(index) } }
        if (decoded != null) frame = decoded
    }

    /**
     * Composites frame [index] into the canvas bitmap and snapshots it. When the bitmap already
     * holds a frame the new one may be drawn over (Skia's `requiredFrame` rules), only that one
     * frame is decoded; otherwise Skia rebuilds it from its last independent frame.
     */
    private fun decodeFrameLocked(index: Int): SkiaImage? {
        if (!bitmapAllocated) {
            if (!canvasBitmap.allocPixels(ImageInfo.makeN32Premul(width, height))) return null
            bitmapAllocated = true
        }
        val required = frames[index].requiredFrame
        val prior = decodedIndex
        val canReusePrior =
            required >= 0 && prior in required until index &&
                frames[prior].disposalMethod != AnimationDisposalMode.RESTORE_PREVIOUS
        val decoded =
            (canReusePrior && runCatching { codec.readPixels(canvasBitmap, index, prior) }.isSuccess) ||
                runCatching {
                    canvasBitmap.erase(0)
                    codec.readPixels(canvasBitmap, index)
                }.isSuccess
        if (!decoded) {
            decodedIndex = -1
            return null
        }
        decodedIndex = index
        // The bitmap is mutable, so this copies it: the frame on screen never changes under a draw.
        return SkiaImage.makeFromBitmap(canvasBitmap)
    }

    override fun draw(canvas: coil3.Canvas) {
        val current = frame ?: return
        val bounds = Rect.makeWH(width.toFloat(), height.toFloat())
        canvas.drawImageRect(current, bounds, bounds, SamplingMode.LINEAR, null, true)
    }

    companion object {
        /** An animation with its first frame decoded, or null for anything to show as a still. */
        suspend fun createOrNull(bytes: ByteArray): RaftSkiaAnimatedImage? {
            val codec = runCatching { Codec.makeFromData(Data.makeFromBytes(bytes)) }.getOrNull() ?: return null
            val frames = runCatching { codec.framesInfo }.getOrNull()
            val playable =
                frames != null && frames.size > 1 && codec.width > 0 && codec.height > 0 &&
                    codec.width.toLong() * codec.height <= RAFT_ANIMATED_MAX_PIXELS
            if (!playable) {
                codec.close()
                return null
            }
            val image = RaftSkiaAnimatedImage(codec, frames!!)
            val first = image.decodeLock.withLock { image.decodeFrameLocked(0) }
            if (first == null) {
                codec.close()
                return null
            }
            image.frame = first
            return image
        }
    }
}

@Composable
internal actual fun RaftImageAnimationEffect(image: coil3.Image?, running: Boolean) {
    val animation = image as? RaftSkiaAnimatedImage ?: return
    LaunchedEffect(animation, running) {
        if (running) animation.player.play { index -> animation.showFrame(index) }
    }
}
