package build.raft.app

import build.raft.app.data.RaftDesktopPaths
import build.raft.app.data.RaftDesktopPrivateFiles
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Size-capped log files: [baseName].log is the current file; when the next line would take it past
 * [maxFileBytes] it becomes [baseName].1.log, the older ones shift up and [baseName].(maxFiles-1).log
 * is dropped, so the folder never holds more than [maxFiles] x [maxFileBytes] (plus one line).
 * Not thread-safe on its own: [RaftDesktopFileLog] serializes the calls.
 */
class RaftRollingLogWriter(
    val directory: File,
    private val baseName: String = "raft",
    private val maxFileBytes: Long = 5L * 1024 * 1024,
    private val maxFiles: Int = 5,
) {
    init {
        require(maxFiles >= 1) { "maxFiles must be at least 1" }
        require(maxFileBytes > 0) { "maxFileBytes must be positive" }
    }

    private var writer: Writer? = null
    private var size = 0L

    val currentFile: File get() = fileAt(0)

    /** The current file first, then the rotated ones from newest to oldest (only those that exist). */
    fun files(): List<File> = (0 until maxFiles).map(::fileAt).filter { it.isFile }

    fun append(line: String) {
        val bytes = line.toByteArray(Charsets.UTF_8).size + 1L
        val out = open()
        if (size > 0 && size + bytes > maxFileBytes) {
            rotate()
            return append(line)
        }
        out.write(line)
        out.write("\n")
        size += bytes
    }

    fun flush() {
        runCatching { writer?.flush() }
    }

    fun close() {
        runCatching { writer?.close() }
        writer = null
    }

    private fun open(): Writer =
        writer ?: run {
            directory.mkdirs()
            val file = currentFile
            val existed = file.exists()
            size = if (existed) file.length() else 0L
            OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8).buffered().also {
                if (!existed) RaftDesktopPrivateFiles.restrictToOwner(file.toPath(), directory = false)
                writer = it
            }
        }

    private fun rotate() {
        close()
        fileAt(maxFiles - 1).delete()
        for (index in maxFiles - 2 downTo 0) {
            val from = fileAt(index)
            if (from.exists()) from.renameTo(fileAt(index + 1))
        }
        size = 0L
    }

    private fun fileAt(index: Int): File =
        File(directory, if (index == 0) "$baseName.log" else "$baseName.$index.log")
}

/**
 * The desktop app's log on disk (Android has its diagnostics journal, iOS its app log): shared
 * logs ([raftPlatformLog]), the host's own lines and crash reports go to [RaftDesktopPaths.logDir]
 * as `raft.log` + up to four rotated files of 5 MB each. Every line goes through the shared
 * credential redaction ([raftRedactLogMessage]) and is cut to a bounded length.
 *
 * [write] never blocks the caller (the UI thread logs too): lines are queued and written by one
 * background thread; when the queue is full the line is dropped and counted. [writeNow] is for the
 * crash path: it writes everything queued so far plus its lines, synchronously, and flushes.
 * Nothing is written to disk until [install] (unit tests and other JVM hosts stay file-free).
 */
object RaftDesktopFileLog {
    private const val QUEUE_CAPACITY = 8_192
    internal const val LINE_MAX_CHARS = 4_096

    private val lock = Any()
    private val queue = LinkedBlockingQueue<String>(QUEUE_CAPACITY)
    private val dropped = AtomicLong(0L)

    @Volatile private var writer: RaftRollingLogWriter? = null

    /** Where the files are, once [install]ed. */
    val directory: File? get() = writer?.directory

    /** Starts writing to [directory] (idempotent: the first call wins). */
    fun install(directory: File = RaftDesktopPaths.logDir) {
        synchronized(lock) {
            if (writer != null) return
            writer = RaftRollingLogWriter(directory)
        }
        Thread({ drainLoop() }, "RaftDesktopFileLog").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
        Runtime.getRuntime().addShutdownHook(Thread({ flush() }, "RaftDesktopFileLog-shutdown"))
    }

    /** Queues one line (level: `D` / `I` / `W` / `E` / `F`, or the shared wire value). */
    fun write(level: String, tag: String, message: String) {
        if (writer == null) return
        if (!queue.offer(formatLine(level, tag, message))) dropped.incrementAndGet()
    }

    /** Writes the queued lines and then [lines], synchronously, and flushes (crash reports). */
    fun writeNow(level: String, tag: String, lines: List<String>) {
        synchronized(lock) {
            val out = writer ?: return
            drainQueued(out)
            lines.forEach { line -> runCatching { out.append(formatLine(level, tag, line)) } }
            out.flush()
        }
    }

    /** Writes what is queued and flushes it to disk. */
    fun flush() {
        synchronized(lock) {
            val out = writer ?: return
            drainQueued(out)
            out.flush()
        }
    }

    /**
     * The newest lines of the current log file, oldest first (Settings > Diagnostics): at most
     * [maxLines], read from its last [maxBytes] only.
     */
    fun recentLines(maxLines: Int, maxBytes: Int = 256 * 1024): List<String> {
        flush()
        val file = synchronized(lock) { writer?.currentFile } ?: return emptyList()
        val tail =
            runCatching {
                java.io.RandomAccessFile(file, "r").use { raf ->
                    val start = maxOf(0L, raf.length() - maxBytes)
                    raf.seek(start)
                    val bytes = ByteArray((raf.length() - start).toInt())
                    raf.readFully(bytes)
                    val text = String(bytes, Charsets.UTF_8)
                    // A cut first line is dropped.
                    if (start > 0) text.substringAfter('\n', "") else text
                }
            }.getOrDefault("")
        return tail.lineSequence().filter { it.isNotEmpty() }.toList().takeLast(maxLines)
    }

    fun formatLine(level: String, tag: String, message: String): String {
        val safe = raftRedactLogMessage(message).replace("\r", "").let {
            if (it.length > LINE_MAX_CHARS) it.take(LINE_MAX_CHARS) + "…(cut)" else it
        }
        val levelLetter =
            when (level.lowercase()) {
                "debug", "d" -> "D"
                "info", "i" -> "I"
                "warn", "w" -> "W"
                "error", "e" -> "E"
                "fatal", "f" -> "F"
                else -> level.take(1).uppercase()
            }
        return "${OffsetDateTime.now().format(TIMESTAMP)} $levelLetter [${Thread.currentThread().name}] ${tag.take(64)}: $safe"
    }

    private fun drainLoop() {
        while (true) {
            val first = runCatching { queue.poll(5, TimeUnit.SECONDS) }.getOrNull()
            synchronized(lock) {
                val out = writer ?: return
                if (first != null) runCatching { out.append(first) }
                drainQueued(out)
                out.flush()
            }
        }
    }

    /** Caller holds [lock]. */
    private fun drainQueued(out: RaftRollingLogWriter) {
        val lost = dropped.getAndSet(0L)
        if (lost > 0) runCatching { out.append(formatLine("W", "RaftDesktopFileLog", "dropped $lost line(s): queue full")) }
        while (true) {
            val line = queue.poll() ?: break
            runCatching { out.append(line) }
        }
    }

    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
}

/**
 * A throwable as log lines: the class, a bounded message (it can quote server or user text) and
 * the frames, causes and suppressed included, at most [maxFrames] frames per throwable.
 */
fun raftDesktopThrowableLines(throwable: Throwable, maxFrames: Int = 120, maxMessageChars: Int = 300): List<String> {
    val lines = mutableListOf<String>()
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())

    fun describe(t: Throwable): String {
        val message = t.message?.let { if (it.length > maxMessageChars) it.take(maxMessageChars) + "…" else it }
        return if (message == null) t.javaClass.name else "${t.javaClass.name}: $message"
    }

    fun walk(t: Throwable, prefix: String) {
        if (!seen.add(t)) {
            lines += "$prefix[circular reference: ${t.javaClass.name}]"
            return
        }
        lines += prefix + describe(t)
        val frames = t.stackTrace
        frames.take(maxFrames).forEach { lines += "\tat $it" }
        if (frames.size > maxFrames) lines += "\t... ${frames.size - maxFrames} more"
        t.suppressed.forEach { walk(it, "Suppressed: ") }
        t.cause?.let { walk(it, "Caused by: ") }
    }
    walk(throwable, "")
    return lines
}
