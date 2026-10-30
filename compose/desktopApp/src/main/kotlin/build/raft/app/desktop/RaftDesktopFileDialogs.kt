package build.raft.app.desktop

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import java.io.File
import io.github.vinceglb.filekit.PlatformFile as FileKitFile

/**
 * The system open / save panels (macOS NSOpenPanel / NSSavePanel, the Windows and Linux system
 * dialogs) through FileKit. Only `java.io.File` crosses this boundary: FileKit's `PlatformFile`
 * would clash with our own [build.raft.app.data.PlatformFile].
 */
interface RaftDesktopFileDialogs {
    /** Files chosen in the open panel (several allowed); empty when the user cancels. */
    suspend fun openFiles(imagesOnly: Boolean): List<File>

    /** Where the user chose to save [suggestedName]; null when the user cancels. */
    suspend fun saveFile(suggestedName: String): File?
}

object RaftFileKitDialogs : RaftDesktopFileDialogs {
    /**
     * What the image button offers: the raster formats the editor and the Skia compressor decode.
     * HEIC and the rest still go through the paperclip as files.
     */
    val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")

    override suspend fun openFiles(imagesOnly: Boolean): List<File> {
        val type = if (imagesOnly) FileKitType.File(IMAGE_EXTENSIONS) else FileKitType.File()
        val picked: List<FileKitFile>? = FileKit.openFilePicker(type = type, mode = FileKitMode.Multiple())
        return picked.orEmpty().map { it.file }
    }

    override suspend fun saveFile(suggestedName: String): File? =
        // The full name, no extension filter: the panel keeps the attachment's own extension.
        FileKit.openFileSaver(suggestedName = raftDesktopSaveName(suggestedName), defaultExtension = null)?.file
}

/** A file name for the save panel: no path, no control characters, never blank. */
internal fun raftDesktopSaveName(fileName: String): String =
    fileName.substringAfterLast('/').substringAfterLast('\\')
        .filterNot { it.isISOControl() || it == ':' }
        .trim()
        .ifBlank { "attachment" }
