package build.raft.app.data

import java.io.File

/**
 * Where the desktop app keeps its data: `~/Library/Application Support/Raft` on macOS,
 * `%APPDATA%\Raft` on Windows, `$XDG_DATA_HOME/raft` (or `~/.local/share/raft`) elsewhere.
 * `-Draft.desktop.dataDir=<dir>` overrides it (tests, a second profile).
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
        dir.apply { mkdirs() }
    }

    val keyValueDir: File get() = File(dataDir, "kv").apply { mkdirs() }

    val databaseDir: File get() = File(dataDir, "databases").apply { mkdirs() }

    val cacheDir: File get() = File(dataDir, "cache").apply { mkdirs() }
}
