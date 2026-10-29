package build.raft.app.data

import build.raft.app.sanitizeRaftLogMessage
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The desktop app's persistent log: every host and shared log line that reaches stdout / stderr is
 * also appended to `<dataDir>/logs/raft-desktop.log` ([RaftDesktopPaths.dataDir]), rotated at
 * [MAX_BYTES] into `raft-desktop.log.1` and `.2`. Off until [install] (the app's `main`), so tests
 * never write into the real data directory. Lines go through the shared log redaction (tokens and
 * keys masked); the directory and files are owner-only.
 */
object RaftDesktopLogFile {
    const val FILE_NAME = "raft-desktop.log"
    const val MAX_BYTES = 5L * 1024 * 1024
    const val MAX_FILES = 3

    val directory: File get() = File(RaftDesktopPaths.dataDir, "logs")

    @Volatile private var file: RaftDesktopRotatingLogFile? = null

    fun install() {
        if (file == null) file = RaftDesktopRotatingLogFile(directory, FILE_NAME, MAX_BYTES, MAX_FILES)
    }

    /** [level] is the one-letter level stdout shows (D / I / W / E). Never throws. */
    fun append(level: String, tag: String, message: String) {
        val target = file ?: return
        target.append(raftDesktopLogLine(System.currentTimeMillis(), level, tag, message))
    }
}

private val LogTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

/** One log line: local time, level, tag, redacted message (single line). */
fun raftDesktopLogLine(timeMillis: Long, level: String, tag: String, message: String, zone: ZoneId = ZoneId.systemDefault()): String {
    val time = LogTimeFormat.format(Instant.ofEpochMilli(timeMillis).atZone(zone))
    val safe = message.sanitizeRaftLogMessage().replace("\r\n", "\n").replace('\n', ' ')
    return "$time $level $tag: $safe"
}

/**
 * Appends lines to [directory]/[baseName]; when a line would push it past [maxBytes] the file moves
 * to `.1` (`.1` to `.2`, ...), keeping [maxFiles] files in all. Synchronous and thread-safe; I/O
 * errors drop the line (logging must never fail the app).
 */
class RaftDesktopRotatingLogFile(
    private val directory: File,
    private val baseName: String,
    private val maxBytes: Long,
    private val maxFiles: Int,
) {
    val current: File get() = File(directory, baseName)

    @Synchronized
    fun append(line: String) {
        runCatching {
            if (!directory.isDirectory) {
                directory.mkdirs()
                RaftDesktopPrivateFiles.restrictToOwner(directory.toPath(), directory = true)
            }
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
            val file = current
            if (file.exists() && file.length() > 0 && file.length() + bytes.size > maxBytes) rotate()
            if (!file.exists()) {
                file.createNewFile()
                RaftDesktopPrivateFiles.restrictToOwner(file.toPath(), directory = false)
            }
            FileOutputStream(file, true).use { it.write(bytes) }
        }
    }

    private fun rotate() {
        for (index in maxFiles - 1 downTo 1) {
            val source = if (index == 1) current else File(directory, "$baseName.${index - 1}")
            if (source.exists()) {
                Files.move(source.toPath(), File(directory, "$baseName.$index").toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        if (maxFiles <= 1) Files.deleteIfExists(current.toPath())
    }
}
