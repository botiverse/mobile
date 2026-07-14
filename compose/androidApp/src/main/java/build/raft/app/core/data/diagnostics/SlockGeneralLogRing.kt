package build.raft.app.core.data.diagnostics

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

private const val DiagnosticsLogMaxTotalMiB = 30L

/** Ordinary persistent JSONL sink. Routing and auth-critical classification happen upstream. */
object SlockGeneralLogRing {
    private const val LogDirName = "slock_diagnostics_logs"
    private const val LogFileName = "slock-diagnostics.jsonl"
    private const val LegacyLogFileName = "slock-diagnostics.log"
    private const val LogMaxBytes = 1L * 1024L * 1024L
    private const val LogMaxFiles = 30
    private const val LogMaxTotalBytes = DiagnosticsLogMaxTotalMiB * 1024L * 1024L
    private const val LogExportMaxFiles = LogMaxFiles
    private const val MemoryEntryLimit = 512
    private const val MessageMaxChars = 2_048
    private const val RecentDiagnosticsMaxChars = 120_000
    private val LogHistoryMaxAgeMs = TimeUnit.DAYS.toMillis(2)

    private val memoryLock = Any()
    private val fileLock = Any()
    private val droppedEntries = AtomicLong(0L)
    private val memoryEntries = ArrayDeque<DiagnosticEntry>(MemoryEntryLimit)
    private val executor =
        ThreadPoolExecutor(
            1,
            1,
            30L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(512),
            { runnable -> Thread(runnable, "SlockDiagnosticsWriter").apply { isDaemon = true } },
            { runnable, executor ->
                // record() is only called by the diagnostics dispatch worker. Waiting here keeps a
                // successful top-level flush from omitting an already accepted ordinary record.
                try {
                    executor.queue.put(runnable)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    droppedEntries.incrementAndGet()
                }
            }
        )

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    internal fun record(record: SanitizedDiagnosticLog) {
        executor.execute {
            val truncated = record.message.length > MessageMaxChars
            val entry =
                DiagnosticEntry(
                    timestamp = diagnosticsTimestamp(record.timestampMillis),
                    level = record.level.trim().lowercase(Locale.US).ifBlank { "info" },
                    tag = record.tag,
                    event = record.message.substringBefore(' ', missingDelimiterValue = record.message).take(96),
                    message = record.message.take(MessageMaxChars),
                    thread = record.threadName,
                    seq = record.sequence,
                    dropped = droppedEntries.get() + record.droppedBeforeDispatch,
                    truncated = truncated,
                )
            remember(entry)
            append(entry)
        }
    }

    fun hasFiles(context: Context): Boolean =
        logFiles(context.applicationContext).any { it.isFile && it.length() > 0L }

    fun clearFiles(context: Context) {
        runCatching {
            logDir(context.applicationContext).walkBottomUp()
                .filter { it.isFile }
                .forEach { it.delete() }
        }
    }

    fun flush(): Boolean =
        runCatching {
            executor.submit { }.get(2L, TimeUnit.SECONDS)
            true
        }.getOrDefault(false)

    fun logFiles(context: Context): List<File> =
        logFilesIn(
            dir = logDir(context),
            nowMs = System.currentTimeMillis()
        )

    internal fun logFilesIn(dir: File, nowMs: Long): List<File> =
        dir
            .walkTopDown()
            .filter {
                it.isFile &&
                    it.length() > 0L &&
                    isDiagnosticsLogFile(it) &&
                    isRecentDiagnosticsLogFile(it, nowMs)
            }
            .toList()
            .sortedByDescending { it.lastModified() }
            .take(LogExportMaxFiles)

    fun exportRecentJsonl(maxChars: Int = RecentDiagnosticsMaxChars): String =
        snapshotMemoryEntries()
            .joinToString(separator = "\n") { it.toJsonLine() }
            .takeLast(maxChars)

    fun exportRecentText(context: Context, maxChars: Int = RecentDiagnosticsMaxChars): String {
        flush()
        val files = logFiles(context.applicationContext)
        val header =
            buildString {
                appendLine("App-owned recent diagnostics")
                appendLine(diagnosticsRetentionLabel(files.size))
            }
        val memoryJsonl = exportRecentJsonl(maxChars / 2)
        val fileBudget = (maxChars - header.length - memoryJsonl.length).coerceAtLeast(1_000)
        val fileText =
            files
                .take(6)
                .joinToString(separator = "\n\n") { file ->
                    buildString {
                        appendLine("### ${file.name} (${file.length()} bytes)")
                        append(readTailText(file, (fileBudget / files.take(6).size).coerceAtLeast(1_000)))
                    }
                }
        return buildString {
            append(header)
            if (memoryJsonl.isNotBlank()) {
                appendLine()
                appendLine("### recent-memory.jsonl")
                appendLine(memoryJsonl)
            }
            if (fileText.isNotBlank()) {
                appendLine()
                append(fileText)
            }
        }.takeLast(maxChars)
    }

    private fun remember(entry: DiagnosticEntry) {
        synchronized(memoryLock) {
            while (memoryEntries.size >= MemoryEntryLimit) {
                memoryEntries.removeFirst()
            }
            memoryEntries.addLast(entry)
        }
    }

    private fun snapshotMemoryEntries(): List<DiagnosticEntry> =
        synchronized(memoryLock) { memoryEntries.toList() }

    private fun append(entry: DiagnosticEntry) {
        val context = appContext ?: return
        runCatching {
            synchronized(fileLock) {
                val dir = logDir(context).apply { mkdirs() }
                val active = File(dir, LogFileName)
                rotateLogsIfNeeded(active)
                active.appendText(entry.toJsonLine() + "\n", StandardCharsets.UTF_8)
            }
        }.onFailure {
            droppedEntries.incrementAndGet()
        }
    }

    private fun rotateLogsIfNeeded(active: File) {
        if (!active.exists() || active.length() < LogMaxBytes) return
        val dir = active.parentFile ?: return
        for (index in LogMaxFiles - 1 downTo 1) {
            val source = File(dir, "$LogFileName.$index")
            val target = File(dir, "$LogFileName.${index + 1}")
            if (!source.exists()) continue
            if (index == LogMaxFiles - 1) {
                source.delete()
            } else {
                source.renameTo(target)
            }
        }
        active.renameTo(File(dir, "$LogFileName.1"))
    }

    private fun readTailText(file: File, maxChars: Int): String =
        runCatching {
            val maxBytes = (maxChars * 2).coerceAtLeast(1_000)
            val length = file.length()
            file.inputStream().buffered().use { input ->
                if (length > maxBytes) {
                    var remaining = length - maxBytes
                    while (remaining > 0L) {
                        val skipped = input.skip(remaining)
                        if (skipped <= 0L) break
                        remaining -= skipped
                    }
                }
                DiagnosticsSanitizer.sanitize(input.readBytes().toString(StandardCharsets.UTF_8)).takeLast(maxChars)
            }
        }.getOrElse { error ->
            "Unable to read diagnostics file ${file.name}: ${error.javaClass.simpleName}"
        }

    private fun diagnosticsTimestamp(timestampMillis: Long): String =
        requireNotNull(TimestampFormatter.get()).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(timestampMillis))

    private fun logDir(context: Context): File = File(context.filesDir, LogDirName)

    private fun isDiagnosticsLogFile(file: File): Boolean =
        file.name.startsWith(LogFileName) || file.name.startsWith(LegacyLogFileName)

    private fun isRecentDiagnosticsLogFile(file: File, nowMs: Long): Boolean {
        val modifiedAt = file.lastModified()
        if (modifiedAt <= 0L) return false
        return modifiedAt >= nowMs - LogHistoryMaxAgeMs
    }

    private data class DiagnosticEntry(
        val timestamp: String,
        val level: String,
        val tag: String,
        val event: String,
        val message: String,
        val thread: String,
        val seq: Long,
        val dropped: Long,
        val truncated: Boolean
    ) {
        fun toJsonLine(): String =
            JSONObject()
                .put("ts", timestamp)
                .put("level", level)
                .put("tag", tag)
                .put("event", event)
                .put("message", message)
                .put("thread", thread)
                .put("seq", seq)
                .put("dropped", dropped)
                .put("truncated", truncated)
                .toString()
    }

    private val TimestampFormatter =
        ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
        }
}

internal fun diagnosticsRetentionLabel(fileCount: Int): String =
    "Files: $fileCount (up to $DiagnosticsLogMaxTotalMiB MiB, max age 2 days)"
