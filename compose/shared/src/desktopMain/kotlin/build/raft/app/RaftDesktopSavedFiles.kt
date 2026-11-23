package build.raft.app

import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual fun raftPlatformSavedFileHost(): RaftSavedFileHost? = RaftDesktopSavedFileHost

/**
 * The desktop file manager for a saved attachment, through the platform's own commands
 * ([RaftDesktopExternalOpen]): Finder's reveal on macOS, the default app.
 */
private object RaftDesktopSavedFileHost : RaftSavedFileHost {
    private val osName: String = System.getProperty("os.name").orEmpty()

    override val revealsInFinder: Boolean = raftDesktopIsMac(osName)

    override fun exists(uri: String): Boolean = raftDesktopSavedFile(uri)?.isFile == true

    override fun folderName(uri: String): String? = raftDesktopSavedFile(uri)?.absoluteFile?.parentFile?.let { it.name.ifBlank { it.path } }

    override suspend fun reveal(uri: String): Boolean =
        withContext(Dispatchers.IO) {
            val file = raftDesktopSavedFile(uri)?.takeIf(File::exists) ?: return@withContext false
            RaftDesktopExternalOpen.reveal(file)
        }

    override suspend fun open(uri: String): Boolean =
        withContext(Dispatchers.IO) {
            val file = raftDesktopSavedFile(uri)?.takeIf(File::exists) ?: return@withContext false
            RaftDesktopExternalOpen.open(file)
        }
}

/**
 * Hands a web address, a file or a folder to the operating system: `open` on macOS, Explorer on
 * Windows, `xdg-open` on Linux. Not `java.awt.Desktop`: that starts AWT next to the app's Tao window,
 * and AWT drives the macOS application object too (Nucleus: AWT and Tao each set up NSApp).
 * Returns whether the command started.
 */
object RaftDesktopExternalOpen {
    private val osName: String = System.getProperty("os.name").orEmpty()

    /** Opens a web or mail address in its default app (the caller checks the scheme). */
    fun browse(uri: URI): Boolean = launch(raftDesktopOpenFileCommand(osName, uri.toString()))

    /** Opens a file or a folder with its default app. */
    fun open(file: File): Boolean = launch(raftDesktopOpenFileCommand(osName, file.absolutePath))

    /** Shows [file] selected in the file manager; where no command selects a file (Linux), its folder opens. */
    fun reveal(file: File): Boolean =
        launch(raftDesktopRevealFileCommand(osName, file.absolutePath)) ||
            file.absoluteFile.parentFile?.let(::open) == true

    private fun launch(command: List<String>?): Boolean {
        // An argument the command could read as an option is never passed.
        if (command == null || command.last().startsWith("-")) return false
        return runCatching {
            ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            true
        }.getOrDefault(false)
    }
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

/** Opens [path] (a file, a folder or a web address) with its default app. */
fun raftDesktopOpenFileCommand(osName: String, path: String): List<String> =
    when {
        raftDesktopIsMac(osName) -> listOf("open", path)
        raftDesktopIsWindows(osName) -> listOf("explorer.exe", path)
        else -> listOf("xdg-open", path)
    }
