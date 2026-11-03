package build.raft.app

import java.awt.Desktop
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual fun raftPlatformSavedFileHost(): RaftSavedFileHost? = RaftDesktopSavedFileHost

/**
 * The desktop file manager for a saved attachment: `java.awt.Desktop` (Finder's reveal on macOS,
 * the default app), else the platform's own command ([raftDesktopRevealFileCommand] /
 * [raftDesktopOpenFileCommand]).
 */
private object RaftDesktopSavedFileHost : RaftSavedFileHost {
    private val osName: String = System.getProperty("os.name").orEmpty()

    override val revealsInFinder: Boolean = raftDesktopIsMac(osName)

    override fun exists(uri: String): Boolean = raftDesktopSavedFile(uri)?.isFile == true

    override fun folderName(uri: String): String? = raftDesktopSavedFile(uri)?.absoluteFile?.parentFile?.let { it.name.ifBlank { it.path } }

    override suspend fun reveal(uri: String): Boolean =
        withContext(Dispatchers.IO) {
            val file = raftDesktopSavedFile(uri)?.takeIf(File::exists) ?: return@withContext false
            desktopAction(Desktop.Action.BROWSE_FILE_DIR) { it.browseFileDirectory(file) } ||
                run(raftDesktopRevealFileCommand(osName, file.absolutePath)) ||
                // Last resort: the folder itself, without the file selected.
                file.absoluteFile.parentFile?.let { folder ->
                    desktopAction(Desktop.Action.OPEN) { it.open(folder) } || run(raftDesktopOpenFileCommand(osName, folder.absolutePath))
                } == true
        }

    override suspend fun open(uri: String): Boolean =
        withContext(Dispatchers.IO) {
            val file = raftDesktopSavedFile(uri)?.takeIf(File::exists) ?: return@withContext false
            desktopAction(Desktop.Action.OPEN) { it.open(file) } || run(raftDesktopOpenFileCommand(osName, file.absolutePath))
        }

    private fun desktopAction(action: Desktop.Action, block: (Desktop) -> Unit): Boolean =
        runCatching {
            val desktop = Desktop.getDesktop().takeIf { Desktop.isDesktopSupported() && it.isSupported(action) }
            desktop?.let(block) != null
        }.getOrDefault(false)

    private fun run(command: List<String>?): Boolean =
        command != null && runCatching { ProcessBuilder(command).start(); true }.getOrDefault(false)
}

/** The file a host's saved `uri` names: a `file:` URI (the desktop save answer), or a plain path. */
fun raftDesktopSavedFile(uri: String): File? =
    runCatching {
        val trimmed = uri.trim()
        when {
            trimmed.isEmpty() -> null
            trimmed.startsWith("file:", ignoreCase = true) -> File(URI(trimmed))
            "://" in trimmed -> null
            else -> File(trimmed)
        }
    }.getOrNull()

fun raftDesktopIsMac(osName: String): Boolean = osName.startsWith("Mac", ignoreCase = true)

private fun raftDesktopIsWindows(osName: String): Boolean = osName.startsWith("Windows", ignoreCase = true)

/** Shows [path] selected in the file manager: Finder (`open -R`), Explorer (`/select,`); none on Linux. */
fun raftDesktopRevealFileCommand(osName: String, path: String): List<String>? =
    when {
        raftDesktopIsMac(osName) -> listOf("open", "-R", path)
        raftDesktopIsWindows(osName) -> listOf("explorer.exe", "/select,$path")
        else -> null
    }

/** Opens [path] (a file or a folder) with its default app. */
fun raftDesktopOpenFileCommand(osName: String, path: String): List<String> =
    when {
        raftDesktopIsMac(osName) -> listOf("open", path)
        raftDesktopIsWindows(osName) -> listOf("explorer.exe", path)
        else -> listOf("xdg-open", path)
    }
