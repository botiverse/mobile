package build.raft.app.data

import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.EncodedOrigin
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Desktop `compressThreadImage`: the Android / iOS rule with Skia. The longest side is scaled down
 * to [MAX_EDGE_PX] (never up), EXIF orientation is applied, transparent pixels land on white, and
 * the result is a JPEG at quality [JPEG_QUALITY]. Animated images give their first frame, as
 * BitmapFactory / ImageIO do on mobile; anything Skia cannot decode (SVG, HEIC, videos) fails.
 *
 * Output goes to a `.part` sibling first and is published by an atomic rename, so a failure or
 * cancellation never leaves a half-written JPEG under the final name.
 */
object RaftDesktopImageCompression {
    const val MAX_EDGE_PX = 2048
    const val JPEG_QUALITY = 82
    const val OUTPUT_MIME_TYPE = "image/jpeg"
    const val OUTPUT_SOURCE = "composer_image_compressor"

    /** Generated JPEGs live in the composer cache next to pasted images and are retired the same way. */
    const val OUTPUT_PREFIX = "composer-image-"

    private val VIDEO_EXTENSIONS =
        setOf("3gp", "avi", "flv", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "webm", "wmv")

    /** Output size for a [width] x [height] (already oriented) image: longest side at most [MAX_EDGE_PX]. */
    fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        val longest = max(width, height).coerceAtLeast(1)
        if (longest <= MAX_EDGE_PX) return width to height
        val scale = MAX_EDGE_PX.toDouble() / longest
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    /** An image MIME type and no video extension on either the display name or the source path. */
    fun isCompressibleInput(fileName: String, mimeType: String, source: File): Boolean {
        val mime = mimeType.substringBefore(';').trim().lowercase(Locale.US)
        if (!mime.startsWith("image/")) return false
        val nameExtension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        return nameExtension !in VIDEO_EXTENSIONS && source.extension.lowercase(Locale.US) !in VIDEO_EXTENSIONS
    }

    /** `image.png` -> `image.jpg`; path separators and control characters cannot reach the name. */
    fun outputFileName(fileName: String): String {
        val leaf = fileName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "image" }
        val stem =
            leaf.substringBeforeLast('.', leaf)
                .map { if (it.isLetterOrDigit() || it in ".-_ ") it else '_' }
                .joinToString("")
                .trim()
                .take(200)
        return "${stem.ifBlank { "image" }}.jpg"
    }

    /**
     * Compresses [source] into [output]. Throws [IOException] when the image cannot be decoded or
     * written, and [RaftDesktopImageCompressionCancelled] once [isCancelled] turns true; in both
     * cases nothing is left at [output] or its `.part` sibling.
     */
    @Throws(IOException::class)
    fun compress(source: File, output: File, isCancelled: () -> Boolean = { false }) {
        fun checkCancelled() {
            if (isCancelled()) throw RaftDesktopImageCompressionCancelled()
        }
        val part = File(output.parentFile, "${output.name}.part")
        var committed = false
        try {
            part.delete()
            val bytes = source.readBytes()
            checkCancelled()
            val codec =
                runCatching { Codec.makeFromData(Data.makeFromBytes(bytes)) }.getOrNull()
                    ?: throw IOException("Unable to decode image attachment")
            val encoded =
                codec.use {
                    val bitmap = runCatching { codec.readPixels() }.getOrNull() ?: throw IOException("Unable to decode image attachment")
                    bitmap.use {
                        checkCancelled()
                        Image.makeFromBitmap(bitmap).use { image -> encodeOriented(image, codec.encodedOrigin) }
                    }
                }
            checkCancelled()
            val parent = output.parentFile ?: throw IOException("Attachment cache unavailable")
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Attachment cache unavailable")
            part.writeBytes(encoded)
            checkCancelled()
            Files.move(part.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            committed = true
            checkCancelled()
            if (!output.isFile || output.length() <= 0L) throw IOException("Unable to encode image attachment")
        } catch (cancelled: RaftDesktopImageCompressionCancelled) {
            throw cancelled
        } catch (error: IOException) {
            throw error
        } catch (error: Throwable) {
            throw IOException(error.message ?: "Unable to compress image attachment", error)
        } finally {
            part.delete()
            if (!committed || isCancelled()) output.delete()
        }
    }

    private fun encodeOriented(image: Image, origin: EncodedOrigin): ByteArray {
        val sourceWidth = image.width
        val sourceHeight = image.height
        if (sourceWidth <= 0 || sourceHeight <= 0) throw IOException("Unable to decode image attachment")
        val swaps = origin.swapsWidthHeight()
        val orientedWidth = if (swaps) sourceHeight else sourceWidth
        val orientedHeight = if (swaps) sourceWidth else sourceHeight
        val (targetWidth, targetHeight) = targetSize(orientedWidth, orientedHeight)
        val surface = Surface.makeRasterN32Premul(targetWidth, targetHeight)
        return surface.use {
            val canvas = surface.canvas
            // JPEG has no alpha: transparent pixels go on white (the iOS camera path's choice).
            canvas.clear(WHITE)
            canvas.scale(targetWidth.toFloat() / orientedWidth, targetHeight.toFloat() / orientedHeight)
            // Maps the stored pixels onto the upright image; Skia takes the upright (destination)
            // size here (SkEncodedOriginToMatrix), which differs from the stored one on a 90° turn.
            canvas.concat(origin.toMatrix(orientedWidth, orientedHeight))
            Paint().use { paint ->
                canvas.drawImageRect(
                    image,
                    Rect.makeWH(sourceWidth.toFloat(), sourceHeight.toFloat()),
                    Rect.makeWH(sourceWidth.toFloat(), sourceHeight.toFloat()),
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR),
                    paint,
                    true,
                )
            }
            surface.makeImageSnapshot().use { snapshot ->
                snapshot.encodeToData(EncodedImageFormat.JPEG, JPEG_QUALITY)?.bytes
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw IOException("Unable to encode image attachment")
            }
        }
    }

    private const val WHITE = -0x1 // 0xFFFFFFFF
}

/** The compression was cancelled; outputs are already removed. */
class RaftDesktopImageCompressionCancelled : IOException("cancelled")

/**
 * The bridge side of desktop `compressThreadImage` / `cancelCompressThreadImage`: request checks,
 * which source files may be read, the per-request cancellation fence and the response envelope
 * (the Android host's shape, which `parseRaftFilePreviewCompressedImageResult` checks strictly).
 */
object RaftDesktopThreadImageCompressor {
    private const val MAX_REQUEST_ID_LENGTH = 128
    private const val MAX_FILE_NAME_LENGTH = 256
    const val ERROR_CODE = "attachment_image_compression_failed"

    private val active = ConcurrentHashMap<String, Operation>()

    private class Operation {
        @Volatile var cancelled = false
    }

    /** Validated request, or null (answer [failure]). */
    data class Request(val requestId: String, val fileRef: String, val fileName: String, val mimeType: String)

    fun request(requestId: String, fileRef: String, fileName: String, mimeType: String): Request? {
        val id = requestId.trim()
        val name = fileName.trim()
        val mime = mimeType.substringBefore(';').trim().lowercase(Locale.US)
        val ref = fileRef.trim()
        val valid =
            id.isNotEmpty() && id.length <= MAX_REQUEST_ID_LENGTH && id.none(Char::isISOControl) &&
                name.isNotEmpty() && name.length <= MAX_FILE_NAME_LENGTH && name.none { it == '/' || it == '\\' || it.isISOControl() } &&
                mime.startsWith("image/") && ref.isNotEmpty() && ref.none(Char::isISOControl)
        return if (valid) Request(id, ref, name, mime) else null
    }

    /**
     * Runs one request on the calling (worker) thread and returns the bridge payload, or null when
     * the request was cancelled (no callback, as on Android). [outputDir] is the composer cache.
     */
    fun run(request: Request, outputDir: File, sourceAllowed: (File) -> Boolean): JsonObject? {
        val operation = Operation()
        if (active.putIfAbsent(request.requestId, operation) != null) return failure()
        val output = File(outputDir, "${RaftDesktopImageCompression.OUTPUT_PREFIX}${UUID.randomUUID()}.jpg")
        return try {
            val source = raftDesktopLocalFile(request.fileRef)?.takeIf { it.isFile && it.length() > 0L && sourceAllowed(it) }
            if (source == null || !RaftDesktopImageCompression.isCompressibleInput(request.fileName, request.mimeType, source)) {
                failure()
            } else {
                RaftDesktopImageCompression.compress(source, output) { operation.cancelled }
                if (operation.cancelled) {
                    output.delete()
                    null
                } else {
                    success(output, RaftDesktopImageCompression.outputFileName(request.fileName))
                }
            }
        } catch (_: RaftDesktopImageCompressionCancelled) {
            null
        } catch (_: Throwable) {
            output.delete()
            failure()
        } finally {
            active.remove(request.requestId, operation)
        }
    }

    /** [run] into the composer cache, reading only app-cache files and files the user picked. */
    fun run(request: Request): JsonObject? =
        run(request, RaftDesktopClipboardAttachments.clipboardDir, ::raftDesktopCompressionSourceAllowed)

    fun cancel(requestId: String) {
        active[requestId.trim()]?.cancelled = true
    }

    fun failure(): JsonObject =
        buildJsonObject {
            put("code", 400)
            put("errorCode", ERROR_CODE)
            put("errorBodyText", "Unable to compress image attachment")
        }

    private fun success(output: File, fileName: String): JsonObject =
        raftDesktopFilesResponse(
            code = 200,
            files =
                listOf(
                    RaftDesktopResponseFile(
                        fileRef = output.absolutePath,
                        fileName = fileName,
                        mimeType = RaftDesktopImageCompression.OUTPUT_MIME_TYPE,
                        sizeBytes = output.length(),
                        source = RaftDesktopImageCompression.OUTPUT_SOURCE,
                    )
                ),
        )
}

/** Compression reads app-cache files (pasted images, editor output) and files the user picked. */
internal fun raftDesktopCompressionSourceAllowed(file: File): Boolean {
    val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return false
    val cacheRoot = runCatching { RaftDesktopPaths.cacheDir.canonicalFile }.getOrNull()
    return (cacheRoot != null && canonical.toPath().startsWith(cacheRoot.toPath())) ||
        RaftDesktopPickedAttachments.isUserPicked(canonical)
}

/** A desktop `fileRef` (an absolute path, or a host-less `file:` URI from the editor) as a file. */
fun raftDesktopLocalFile(fileRef: String): File? {
    val raw = fileRef.trim()
    if (raw.isEmpty() || raw.any(Char::isISOControl)) return null
    val file =
        if (raw.startsWith("file:", ignoreCase = true)) {
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            if (!uri.authority.isNullOrEmpty() || uri.query != null || uri.fragment != null) return null
            runCatching { File(uri) }.getOrNull() ?: return null
        } else {
            File(raw)
        }
    return file.takeIf { it.isAbsolute }
}
