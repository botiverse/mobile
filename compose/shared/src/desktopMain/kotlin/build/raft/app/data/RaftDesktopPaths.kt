package build.raft.app.data

import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Where the desktop app keeps its data: `~/Library/Application Support/Raft` on macOS,
 * `%APPDATA%\Raft` on Windows, `$XDG_DATA_HOME/raft` (or `~/.local/share/raft`) elsewhere.
 * `-Draft.desktop.dataDir=<dir>` overrides it (tests, a second profile). The directory holds the
 * auth session, so it is private to the user (0700) where the file system has POSIX permissions.
 */
object RaftDesktopPaths {
    val dataDir: File by lazy {
        val override = System.getProperty("raft.desktop.dataDir")?.takeIf { it.isNotBlank() }
        val home = System.getProperty("user.home").orEmpty()
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val dir =
            when {
                override != null -> File(override)
                "mac" in os -> File(home, "Library/Application Support/Raft")
                "win" in os -> File(System.getenv("APPDATA") ?: home, "Raft")
                else -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "raft")
            }
        dir.apply {
            mkdirs()
            RaftDesktopPrivateFiles.restrictToOwner(toPath(), directory = true)
        }
    }

    val keyValueDir: File get() = File(dataDir, "kv").apply { mkdirs() }

    val databaseDir: File get() = File(dataDir, "databases").apply { mkdirs() }

    val cacheDir: File get() = File(dataDir, "cache").apply { mkdirs() }
}

/** Owner-only files for the desktop app's private data (POSIX file systems; no-op elsewhere). */
object RaftDesktopPrivateFiles {
    private val posix: Boolean by lazy { "posix" in FileSystems.getDefault().supportedFileAttributeViews() }

    /** Sets 0700 (directory) / 0600 (file) on [path]. Never throws. */
    fun restrictToOwner(path: Path, directory: Boolean) {
        if (!posix) return
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------")) }
    }

    /**
     * Writes [text] to [target] atomically: a temporary sibling created 0600 (before any byte is
     * written), then moved over [target]. Throws [IOException] on failure.
     */
    @Throws(IOException::class)
    fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp").toPath()
        Files.deleteIfExists(tmp)
        if (posix) {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            Files.createFile(tmp)
        }
        Files.writeString(tmp, text)
        Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
