package build.raft.app.data

import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLConnection
import java.util.UUID
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    private fun readableFile(fileRef: String): File {
        val file = File(fileRef)
        if (!file.isAbsolute || !file.isFile || !file.canRead()) throw IOException("No readable file")
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

/**
 * The system clipboard as composer attachments, like the web composer's paste: files copied in
 * Finder (a file list), else a copied image (written as a PNG under the cache). Text is left to
 * the text field's own paste.
 */
internal object RaftDesktopClipboardAttachments {
    private val clipboardDir: File get() = File(RaftDesktopPaths.cacheDir, "clipboard").apply { mkdirs() }

    fun available(): Boolean =
        runCatching {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) ||
                clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)
        }.getOrDefault(false)

    fun read(): List<PlatformFile> {
        val clipboard = runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull() ?: return emptyList()
        val files =
            runCatching {
                if (clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor)) {
                    (clipboard.getData(DataFlavor.javaFileListFlavor) as? List<*>)?.filterIsInstance<File>()
                } else {
                    null
                }
            }.getOrNull()
        if (files != null) return pastedFiles(files)
        val image =
            runCatching {
                if (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                    clipboard.getData(DataFlavor.imageFlavor) as? Image
                } else {
                    null
                }
            }.getOrNull() ?: return emptyList()
        return listOfNotNull(pastedImage(image))
    }

    /** Readable regular files only (a copied folder is skipped). */
    internal fun pastedFiles(files: List<File>): List<PlatformFile> =
        files.filter { it.isFile && it.canRead() && it.length() > 0L }.mapIndexed { index, file ->
            PlatformFile(
                id = "picked-paste-${System.nanoTime()}-$index",
                fileName = file.name,
                mimeType = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream",
                sizeBytes = file.length(),
                fileRef = file.absolutePath,
                source = PASTE_SOURCE,
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
        val file = File(dir, "pasted-${UUID.randomUUID()}.png")
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
            source = PASTE_SOURCE,
        )
    }

    private const val PASTE_SOURCE = "paste"
}
