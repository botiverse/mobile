package build.raft.app.core.data.diagnostics

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

object SlockAuthCriticalJournal {
    private const val JournalDirName = "slock_auth_critical"
    private const val JournalFileName = "slock-auth-critical.jsonl"
    private const val EntryLimit = 50
    private const val MessageMaxChars = 2_048
    private const val ExportMaxChars = 120_000

    private val fileLock = Any()
    private val droppedEntries = AtomicLong(0L)
    private val executor =
        ThreadPoolExecutor(
            1,
            1,
            30L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(128),
            { runnable -> Thread(runnable, "SlockAuthJournalWriter").apply { isDaemon = true } },
            { runnable, executor ->
                // record() is only called by the diagnostics dispatch worker. Waiting here preserves
                // accepted auth-critical FIFO without ever backpressuring the original UI caller.
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

    fun isCritical(message: String): Boolean = classifyAuthCriticalEvent(message) != null

    internal fun record(record: SanitizedDiagnosticLog, event: String) {
        executor.execute {
            val line =
                buildSanitizedAuthCriticalJournalLine(
                    timestamp = diagnosticsTimestamp(record.timestampMillis),
                    level = record.level.trim().lowercase(Locale.US).ifBlank { "info" },
                    tag = record.tag.takeIf { it.isNotBlank() } ?: "SlockAuth",
                    event = event,
                    message = record.message.take(MessageMaxChars),
                    thread = record.threadName,
                    seq = record.sequence,
                    dropped = droppedEntries.get() + record.droppedBeforeDispatch,
                )
            append(line)
        }
    }

    fun flush(): Boolean =
        runCatching {
            executor.submit { }.get(2L, TimeUnit.SECONDS)
            true
        }.getOrDefault(false)

    fun hasFiles(context: Context): Boolean =
        logFiles(context.applicationContext).any { it.isFile && it.length() > 0L }

    fun logFiles(context: Context): List<File> =
        listOf(journalFile(context.applicationContext)).filter { it.isFile && it.length() > 0L }

    fun recentEntries(context: Context): List<AuthCriticalJournalEntry> {
        flush()
        val file = journalFile(context.applicationContext)
        if (!file.isFile || file.length() == 0L) return emptyList()
        return runCatching {
            parseAuthCriticalJournalEntries(file.readLines(StandardCharsets.UTF_8))
        }.getOrElse { emptyList() }
    }

    fun exportRecentText(context: Context, maxChars: Int = ExportMaxChars): String {
        flush()
        val file = journalFile(context.applicationContext)
        if (!file.isFile || file.length() == 0L) return ""
        val content =
            runCatching {
                DiagnosticsSanitizer.sanitize(file.readText(StandardCharsets.UTF_8)).takeLast(maxChars)
            }.getOrElse { error ->
                "Unable to read auth critical journal: ${error.javaClass.simpleName}"
            }
        return buildString {
            appendLine("Auth critical journal (latest $EntryLimit events)")
            append(content)
        }.takeLast(maxChars)
    }

    fun clearFiles(context: Context) {
        runCatching {
            journalDir(context.applicationContext).walkBottomUp()
                .filter { it.isFile }
                .forEach { it.delete() }
        }
    }

    private fun append(line: String) {
        val context = appContext ?: return
        runCatching {
            synchronized(fileLock) {
                val file = journalFile(context)
                file.parentFile?.mkdirs()
                val existing =
                    if (file.isFile) {
                        file.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }
                    } else {
                        emptyList()
                    }
                val retained = retainLatestAuthJournalLines(existing, line, EntryLimit)
                file.writeText(retained.joinToString(separator = "\n", postfix = "\n"), StandardCharsets.UTF_8)
            }
        }.onFailure {
            droppedEntries.incrementAndGet()
        }
    }

    private fun journalFile(context: Context): File = File(journalDir(context), JournalFileName)

    private fun journalDir(context: Context): File = File(context.filesDir, JournalDirName)

    private fun diagnosticsTimestamp(timestampMillis: Long): String =
        requireNotNull(TimestampFormatter.get()).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(timestampMillis))

    private val TimestampFormatter =
        ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
        }
}

internal fun classifyAuthCriticalEvent(message: String): String? {
    val normalized = message.lowercase(Locale.US)
    AuthSessionClearMarker.find(normalized)?.let { return it.value }
    AuthRealtimeRefreshMarker.find(normalized)?.let { return it.value }
    SharedRealtimeAuthRefreshMarker.find(normalized)?.let { return it.value }
    AuthTokenRefreshMarker.find(normalized)?.let { return it.value.trim() }
    AuthRetryMarker.find(normalized)?.let { return it.value.trim() }
    if (HttpUnauthorizedMarker.containsMatchIn(normalized)) return "auth_http_401"
    return null
}

internal fun hasFixedCostAuthCriticalHint(message: String): Boolean =
    FixedCostAuthCriticalMarkers.any { marker -> message.contains(marker) }

internal fun retainLatestAuthJournalLines(
    existing: List<String>,
    newLine: String,
    maxEntries: Int = 50
): List<String> {
    if (maxEntries <= 0) return emptyList()
    return (existing.asSequence().filter { it.isNotBlank() } + sequenceOf(newLine))
        .toList()
        .takeLast(maxEntries)
}

internal fun buildAuthCriticalJournalLine(
    timestamp: String,
    level: String,
    tag: String,
    event: String,
    message: String,
    thread: String,
    seq: Long,
    dropped: Long
): String =
    buildSanitizedAuthCriticalJournalLine(
        timestamp = timestamp,
        level = level,
        tag = tag,
        event = event,
        message = DiagnosticsSanitizer.sanitize(message),
        thread = thread,
        seq = seq,
        dropped = dropped,
    )

private fun buildSanitizedAuthCriticalJournalLine(
    timestamp: String,
    level: String,
    tag: String,
    event: String,
    message: String,
    thread: String,
    seq: Long,
    dropped: Long,
): String =
    JSONObject()
        .put("ts", timestamp)
        .put("level", level)
        .put("tag", tag)
        .put("event", event)
        .put("message", message)
        .put("thread", thread)
        .put("seq", seq)
        .put("dropped", dropped)
        .toString()

data class AuthCriticalJournalEntry(
    val timestampLabel: String,
    val event: String,
    val tag: String,
    val message: String
)

internal fun parseAuthCriticalJournalEntries(lines: List<String>): List<AuthCriticalJournalEntry> =
    lines.takeLast(50).mapNotNull { line ->
        runCatching {
            val json = JSONObject(line)
            AuthCriticalJournalEntry(
                timestampLabel = json.optString("ts"),
                event = json.optString("event").ifBlank { "auth_critical" },
                tag = json.optString("tag").ifBlank { "SlockAuth" },
                message = DiagnosticsSanitizer.sanitize(json.optString("message"))
            )
        }.getOrNull()
    }

private val AuthSessionClearMarker = Regex("""\bauth_session_clear(?:_[a-z0-9]+)*\b""")
private val AuthRealtimeRefreshMarker = Regex("""\bauth_realtime_refresh_(?:start|result)\b""")
private val SharedRealtimeAuthRefreshMarker =
    Regex("""\bshared_realtime_auth_refresh_(?:skip|success|failed)\b""")
private val AuthTokenRefreshMarker =
    Regex(
        """(?:^|\s)(?:refresh_start|refresh_success|refresh_failed|refresh_unauthorized_session_cleared|refresh_unauthorized_session_preserved)(?:\s|$)"""
    )
private val AuthRetryMarker = Regex("""(?:^|\s)auth_retry_unauthorized_session_preserved(?:\s|$)""")
private val HttpUnauthorizedMarker = Regex("""(?:^|[\s,])(?:code|status|http)\s*[:=]\s*401(?:[\s,]|$)""")
private val FixedCostAuthCriticalMarkers =
    listOf(
        "auth_session_clear",
        "auth_realtime_refresh_start",
        "auth_realtime_refresh_result",
        "shared_realtime_auth_refresh_skip",
        "shared_realtime_auth_refresh_success",
        "shared_realtime_auth_refresh_failed",
        "refresh_start",
        "refresh_success",
        "refresh_failed",
        "refresh_unauthorized_session_cleared",
        "refresh_unauthorized_session_preserved",
        "auth_retry_unauthorized_session_preserved",
        "code=401",
        "code:401",
        "status=401",
        "status:401",
        "http=401",
        "http:401",
    )
