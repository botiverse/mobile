package build.raft.app.data

import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLConnection
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import javax.imageio.ImageIO
import build.raft.app.ThreadComposerClipboardImageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import build.raft.app.toRaftJsonString

/**
 * Attachment file reads on desktop: a `fileRef` is an absolute local path (a file pasted from
 * Finder, or a pasted image written to the cache). Same contract as the Android resolver.
 */
internal class RaftDesktopPlatformFileResolver : RaftPlatformFileResolver {
    override suspend fun readFile(fileRef: String): RaftPlatformFileReadResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = readableFile(fileRef).readBytes()
                RaftPlatformFileReadResult(code = 200, bytes = bytes, sizeBytes = bytes.size.toLong())
            }.getOrElse {
                RaftPlatformFileReadResult(
                    code = 400,
                    bytes = ByteArray(0),
                    errorCode = "attachment_read_failed",
                    message = it.message ?: "Unable to read file",
                )
            }
        }

    override suspend fun statFile(fileRef: String): RaftPlatformFileStatResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val size = readableFile(fileRef).length().takeIf { it > 0L } ?: throw IOException("Empty file")
                RaftPlatformFileStatResult(code = 200, sizeBytes = size)
            }.getOrElse {
                RaftPlatformFileStatResult(
                    code = 400,
                    errorCode = "attachment_read_failed",
                    message = it.message ?: "Unable to read file",
                )
            }
        }

    override suspend fun openFileStream(fileRef: String, expectedSizeBytes: Long): RaftPlatformFileChunkSource =
        withContext(Dispatchers.IO) {
            val file = readableFile(fileRef)
            if (file.length() != expectedSizeBytes) {
                throw IOException("Attachment size changed: expected=$expectedSizeBytes actual=${file.length()}")
            }
            file.inputStream().toChunkSource()
        }

    /** An absolute path, or the `file:` URI the image editor writes its output as. */
    private fun readableFile(fileRef: String): File {
        val file = raftDesktopLocalFile(fileRef)
        if (file == null || !file.isFile || !file.canRead()) throw IOException("No readable file")
        return file
    }
}

private fun InputStream.toChunkSource(): RaftPlatformFileChunkSource {
    val input = this
    return object : RaftPlatformFileChunkSource {
        override suspend fun read(maxBytes: Int): ByteArray =
            withContext(Dispatchers.IO) { input.readNBytes(maxBytes) }

        override fun close() {
            runCatching { input.close() }
        }
    }
}

/** What a composer paste takes from the clipboard. */
enum class RaftDesktopClipboardPasteKind { Files, Image, Text, None }

/**
 * A Finder file list wins (Finder also offers the file names as text and the icon as an image).
 * Otherwise text wins over an image: Excel / Numbers / Word copies carry both, and the user means
 * the text. A clipboard with only an image (a screenshot, "Copy Image") attaches the image.
 */
fun raftDesktopClipboardPasteKind(hasFileList: Boolean, hasImage: Boolean, hasText: Boolean): RaftDesktopClipboardPasteKind =
    when {
        hasFileList -> RaftDesktopClipboardPasteKind.Files
        hasText -> RaftDesktopClipboardPasteKind.Text
        hasImage -> RaftDesktopClipboardPasteKind.Image
        else -> RaftDesktopClipboardPasteKind.None
    }

/**
 * The system clipboard as composer attachments, like the web composer's paste: files copied in
 * Finder (a file list), else a copied image with no text (written as a PNG under the clipboard
 * cache, owned and retired by the composer). Text is left to the text field's own paste.
 */
internal object RaftDesktopClipboardAttachments {
    internal val clipboardDir: File get() = File(RaftDesktopPaths.cacheDir, "clipboard").apply { mkdirs() }

    private fun currentKind(clipboard: Clipboard): RaftDesktopClipboardPasteKind =
        raftDesktopClipboardPasteKind(
            hasFileList = runCatching { clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) }.getOrDefault(false),
            hasImage = runCatching { clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor) }.getOrDefault(false),
            hasText = runCatching { clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor) }.getOrDefault(false),
        )

    fun available(): Boolean =
        runCatching { currentKind(Toolkit.getDefaultToolkit().systemClipboard) }
            .getOrDefault(RaftDesktopClipboardPasteKind.None)
            .let { it == RaftDesktopClipboardPasteKind.Files || it == RaftDesktopClipboardPasteKind.Image }

    fun read(): List<PlatformFile> {
        val clipboard = runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull() ?: return emptyList()
        return when (currentKind(clipboard)) {
            RaftDesktopClipboardPasteKind.Files ->
                runCatching { (clipboard.getData(DataFlavor.javaFileListFlavor) as? List<*>)?.filterIsInstance<File>() }
                    .getOrNull()
                    ?.let(::pastedFiles)
                    .orEmpty()
            RaftDesktopClipboardPasteKind.Image ->
                runCatching { clipboard.getData(DataFlavor.imageFlavor) as? Image }
                    .getOrNull()
                    ?.let(::pastedImage)
                    ?.let(::listOf)
                    .orEmpty()
            RaftDesktopClipboardPasteKind.Text, RaftDesktopClipboardPasteKind.None -> emptyList()
        }
    }

    /** Readable regular files only (a copied folder is skipped). */
    internal fun pastedFiles(files: List<File>): List<PlatformFile> =
        files.filter { it.isFile && it.canRead() && it.length() > 0L }.mapIndexed { index, file ->
            PlatformFile(
                id = "picked-paste-${System.nanoTime()}-$index",
                fileName = file.name,
                mimeType = raftDesktopMimeType(file.name),
                sizeBytes = file.length(),
                fileRef = file.absolutePath,
                source = PASTED_FILE_SOURCE,
            )
        }

    internal fun pastedImage(image: Image, dir: File = clipboardDir): PlatformFile? {
        val width = image.getWidth(null)
        val height = image.getHeight(null)
        if (width <= 0 || height <= 0) return null
        val buffered =
            image as? BufferedImage
                ?: BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also {
                    val graphics = it.createGraphics()
                    graphics.drawImage(image, 0, 0, null)
                    graphics.dispose()
                }
        val file = File(dir, "$PASTED_IMAGE_PREFIX${UUID.randomUUID()}.png")
        if (!runCatching { ImageIO.write(buffered, "png", file) }.getOrDefault(false) || file.length() <= 0L) {
            file.delete()
            return null
        }
        return PlatformFile(
            id = "picked-paste-${System.nanoTime()}-0",
            fileName = "image.png",
            mimeType = "image/png",
            sizeBytes = file.length(),
            fileRef = file.absolutePath,
            source = ThreadComposerClipboardImageSource,
        )
    }

    /** The user's own files (Finder): never retired by the composer. */
    private const val PASTED_FILE_SOURCE = "paste"
    internal const val PASTED_IMAGE_PREFIX = "pasted-"
}

/** MIME type from a file name: common attachment types first (the JDK table lacks webp, heic, ...). */
fun raftDesktopMimeType(fileName: String): String {
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
    return DESKTOP_MIME_TYPES[extension]
        ?: URLConnection.guessContentTypeFromName(fileName)
        ?: "application/octet-stream"
}

private val DESKTOP_MIME_TYPES =
    mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "bmp" to "image/bmp",
        "webp" to "image/webp",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "avif" to "image/avif",
        "svg" to "image/svg+xml",
        "tif" to "image/tiff",
        "tiff" to "image/tiff",
        "mp4" to "video/mp4",
        "m4v" to "video/x-m4v",
        "mov" to "video/quicktime",
        "webm" to "video/webm",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "wav" to "audio/wav",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "json" to "application/json",
        "md" to "text/markdown",
        "txt" to "text/plain",
        "csv" to "text/csv",
    )

/** One file in a host files envelope (picker result, compressor output). */
data class RaftDesktopResponseFile(
    val fileRef: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val source: String,
    val id: String = "picked-${System.nanoTime()}",
)

/**
 * The Android host's files envelope: `code`, the files both top-level and inside `bodyText`, and
 * for a partial result `skippedCount` with `errorCode`.
 */
fun raftDesktopFilesResponse(
    code: Int,
    files: List<RaftDesktopResponseFile>,
    skippedCount: Int = 0,
    errorCode: String? = null,
    errorBodyText: String? = null,
): JsonObject {
    val filesJson =
        buildJsonArray {
            files.forEach { file ->
                add(
                    buildJsonObject {
                        put("id", file.id)
                        put("fileName", file.fileName)
                        put("mimeType", file.mimeType)
                        put("sizeBytes", file.sizeBytes)
                        put("previewUri", file.fileRef)
                        put("fileRef", file.fileRef)
                        put("source", file.source)
                    }
                )
            }
        }
    val body =
        if (files.isEmpty()) {
            ""
        } else {
            buildJsonObject {
                put("files", filesJson)
                if (skippedCount > 0) put("skippedCount", skippedCount)
            }.toRaftJsonString()
        }
    return buildJsonObject {
        put("code", code)
        put("bodyText", body)
        if (files.isNotEmpty()) put("files", filesJson)
        if (skippedCount > 0) put("skippedCount", skippedCount)
        errorCode?.let { put("errorCode", it) }
        errorBodyText?.let { put("errorBodyText", it) }
    }
}

/**
 * Files the user picked in the desktop open panel. They are the user's own files, referenced in
 * place like a Finder paste (no copy, never retired by the composer); the image compressor may
 * read them, as it reads a picker's cache copy on Android.
 */
object RaftDesktopPickedAttachments {
    /** Not a composer-owned temporary source: the composer never deletes the user's files. */
    const val SOURCE = "desktop_picker"

    private val picked = ConcurrentHashMap.newKeySet<String>()

    /**
     * The `threadAttachmentUpload` answer for the files chosen in the panel (empty: the user
     * cancelled). Unreadable, empty or non-regular files are skipped and counted.
     */
    fun response(chosen: List<File>): JsonObject {
        if (chosen.isEmpty()) return raftDesktopFilesResponse(code = 204, files = emptyList())
        val readable = chosen.filter { it.isFile && it.canRead() && it.length() > 0L }
        val skipped = chosen.size - readable.size
        if (readable.isEmpty()) {
            return raftDesktopFilesResponse(
                code = 400,
                files = emptyList(),
                errorCode = "attachment_read_failed",
                errorBodyText = "No readable attachments",
            )
        }
        val stamp = System.nanoTime()
        val files =
            readable.mapIndexed { index, file ->
                val canonical = runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile)
                picked += canonical.path
                RaftDesktopResponseFile(
                    id = "picked-$stamp-$index",
                    fileRef = canonical.path,
                    fileName = canonical.name,
                    mimeType = raftDesktopMimeType(canonical.name),
                    sizeBytes = canonical.length(),
                    source = SOURCE,
                )
            }
        return if (skipped > 0) {
            raftDesktopFilesResponse(
                code = 206,
                files = files,
                skippedCount = skipped,
                errorCode = "attachment_read_partial",
                errorBodyText = "$skipped attachment(s) could not be read",
            )
        } else {
            raftDesktopFilesResponse(code = 200, files = files)
        }
    }

    internal fun isUserPicked(canonical: File): Boolean = canonical.path in picked
}
