package build.raft.app.core.data.diagnostics

import build.raft.app.BuildConfig
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/**
 * Android local-diagnostics coordinator: logcat routing, persistent sinks, and feedback exports.
 *
 * External crash reporting moved (crash/native/ANR reporting is handled by the Hands SDK;
 * telemetry/scene/user-context reporting is dropped). This object keeps its
 * public API so callers are unchanged, but the reporting methods are now
 * no-ops; only local logging and the feedback archive remain.
 */
object SlockAndroidLocalDiagnostics {
    private const val Tag = "SlockAndroidDiagnostics"
    private const val FeedbackDirName = "raft-feedback"
    private const val FeedbackFilePrefix = "raft-feedback-"
    private const val FeedbackZipExtension = ".zip"
    private const val FeedbackSnapshotName = "raft-diagnostics-summary.txt"
    private const val RecentDiagnosticsMaxChars = 120_000
    private const val MaxValueLength = 512
    private const val MaxQueuedMessageLength = 8_192
    private const val MaxQueuedThreadNameLength = 128

    private val dispatchQueue =
        SlockDiagnosticsDispatchQueue(processor = ::processPendingLog)

    fun init(context: Context) {
        SlockGeneralLogRing.init(context.applicationContext)
        SlockAuthCriticalJournal.init(context.applicationContext)
        AndroidHistoricalProcessExitDiagnostics.recordLatest(context.applicationContext)
        SlockAppLog.d(
            Tag,
            "initialized appVersion=${BuildConfig.VERSION_NAME} debug=${BuildConfig.DEBUG} backend=jsonl hands=crash"
        )
    }

    /** Crash/telemetry reporting moved to the Hands SDK — nothing here now. */
    fun isInitialized(): Boolean = false

    fun isDiagnoseInitialized(): Boolean = false

    fun hasFallbackDiagnosticsLogs(context: Context): Boolean =
        SlockGeneralLogRing.hasFiles(context.applicationContext) ||
            SlockAuthCriticalJournal.hasFiles(context.applicationContext)

    fun recordLog(
        level: String,
        tag: String,
        message: String,
        includeNonCritical: Boolean = true,
    ) {
        enqueueLog(level, tag, message, includeNonCritical, DiagnosticCriticalHint.None)
    }

    internal fun recordCriticalLog(
        level: String,
        tag: String,
        message: String,
        includeNonCritical: Boolean = true,
    ) {
        enqueueLog(level, tag, message, includeNonCritical, DiagnosticCriticalHint.AuthCritical)
    }

    internal fun recordAuthLog(
        level: String,
        tag: String,
        message: String,
        includeNonCritical: Boolean = true,
    ) {
        enqueueLog(level, tag, message, includeNonCritical, criticalHint = null)
    }

    private fun enqueueLog(
        level: String,
        tag: String,
        message: String,
        includeNonCritical: Boolean,
        criticalHint: DiagnosticCriticalHint?,
    ) {
        val queuedMessage = message.take(MaxQueuedMessageLength)
        dispatchQueue.enqueue(
            PendingDiagnosticLog(
                level = level.take(16),
                tag = tag.take(MaxValueLength),
                message = queuedMessage,
                timestampMillis = System.currentTimeMillis(),
                threadName = Thread.currentThread().name.take(MaxQueuedThreadNameLength),
                sequence = 0L,
                includeNonCritical = includeNonCritical,
                criticalHint =
                    criticalHint
                        ?: if (hasFixedCostAuthCriticalHint(queuedMessage)) {
                            DiagnosticCriticalHint.AuthCritical
                        } else {
                            DiagnosticCriticalHint.None
                        },
            )
        )
    }

    private fun processPendingLog(entry: PendingDiagnosticLog, droppedBeforeDispatch: Long) {
        val cleanTag = entry.tag.valueOrNull() ?: return
        val cleanMessage = DiagnosticsSanitizer.sanitize(entry.message).takeIf { it.isNotBlank() } ?: return
        val sanitized =
            SanitizedDiagnosticLog(
                level = entry.level,
                tag = cleanTag,
                message = cleanMessage,
                timestampMillis = entry.timestampMillis,
                threadName = entry.threadName,
                sequence = entry.sequence,
                droppedBeforeDispatch = droppedBeforeDispatch,
            )
        val authEvent = classifyAuthCriticalEvent(sanitized.message)
        if (!shouldEmitDiagnostic(entry.includeNonCritical, authEvent)) return
        writeLogcat(sanitized.level, sanitized.tag, sanitized.message)
        if (authEvent != null) {
            SlockAuthCriticalJournal.record(sanitized, authEvent)
        } else if (SlockPersistentDiagnosticsPolicy.shouldPersist(sanitized.level, sanitized.message)) {
            SlockGeneralLogRing.record(sanitized)
        }
    }

    private fun writeLogcat(level: String, tag: String, message: String) {
        when (level.lowercase(Locale.US)) {
            "verbose" -> Log.v(tag, message)
            "debug" -> Log.d(tag, message)
            "info" -> Log.i(tag, message)
            "warn" -> Log.w(tag, message)
            "error" -> Log.e(tag, message)
            else -> Log.i(tag, message)
        }
    }

    fun prepareDiagnosticsFeedbackArchive(context: Context, summaryText: String): File {
        val appContext = context.applicationContext
        check(flush()) { "diagnostics_flush_timeout operation=prepare_archive" }
        val feedbackDir = feedbackDir(appContext).apply { mkdirs() }
        pruneFeedbackArchives(feedbackDir)
        val archive = File(feedbackDir, "$FeedbackFilePrefix${System.currentTimeMillis()}$FeedbackZipExtension")
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(FeedbackSnapshotName))
            zip.write(summaryText.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()

            val seenNames = mutableSetOf<String>()
            val performanceTraceJsonl = SlockPerformanceTraceRing.exportRecentJsonl()
            if (performanceTraceJsonl.isNotBlank()) {
                zip.putNextEntry(ZipEntry(uniqueDiagnosticsZipEntryName("performance/recent-15s.jsonl", seenNames)))
                zip.write(performanceTraceJsonl.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
            val recentJsonl = SlockGeneralLogRing.exportRecentJsonl()
            if (recentJsonl.isNotBlank()) {
                zip.putNextEntry(ZipEntry(uniqueDiagnosticsZipEntryName("app_logs/recent-memory.jsonl", seenNames)))
                zip.write(recentJsonl.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
            SlockGeneralLogRing.logFiles(appContext).forEach { file ->
                if (file.isFile && file.length() > 0L) {
                    val relativePath = SlockGeneralLogRing.archiveRelativeLogPath(appContext, file)
                    val entryName = uniqueDiagnosticsZipEntryName("app_logs/$relativePath", seenNames)
                    zip.putNextEntry(ZipEntry(entryName))
                    file.inputStream().buffered().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            SlockAuthCriticalJournal.logFiles(appContext).forEach { file ->
                val relativePath = SlockAuthCriticalJournal.relativeLogPath(appContext, file)
                val entryName = uniqueDiagnosticsZipEntryName("auth_logs/$relativePath", seenNames)
                zip.putNextEntry(ZipEntry(entryName))
                file.inputStream().buffered().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
        recordLog(
            "info",
            Tag,
            "diagnostics_feedback_archive file=${archive.name} bytes=${archive.length()} app_log_files=${SlockGeneralLogRing.logFiles(appContext).size}"
        )
        return archive
    }

    fun exportRecentDiagnosticsText(context: Context, maxChars: Int = RecentDiagnosticsMaxChars): String {
        val appContext = context.applicationContext
        if (!flush()) {
            return "Diagnostics snapshot unavailable because pending logs did not flush."
        }
        val authText = SlockAuthCriticalJournal.exportRecentText(appContext, maxChars / 2)
        val generalText = SlockGeneralLogRing.exportRecentText(appContext, maxChars - authText.length)
        val text =
            buildString {
                if (authText.isNotBlank()) appendLine(authText)
                if (generalText.isNotBlank()) append(generalText)
            }.takeLast(maxChars)
        return text.ifBlank { "No local diagnostics log files are currently available." }
    }

    fun uploadDiagnostics(context: Context, attachment: File, summary: String): Boolean {
        recordLog("debug", Tag, "diagnostics_upload skipped attachment=${attachment.name} summary=$summary")
        return false
    }

    fun clearLocalDiagnosticsFiles(context: Context) {
        val appContext = context.applicationContext
        if (!flush()) {
            Log.w(Tag, "diagnostics_flush_timeout operation=clear_files")
            return
        }
        runCatching {
            feedbackDir(appContext).listFiles()
                ?.filter { file -> file.isFile && file.name.startsWith(FeedbackFilePrefix) }
                ?.forEach { it.delete() }
        }
        SlockGeneralLogRing.clearFiles(appContext)
        SlockAuthCriticalJournal.clearFiles(appContext)
        recordLog("info", Tag, "diagnostics_local_files_clear_requested")
    }

    internal fun flush(): Boolean =
        dispatchQueue.flush() &&
            SlockGeneralLogRing.flush() &&
            SlockAuthCriticalJournal.flush()

    // ---- Reporting hooks retained as no-ops (External crash reporting moved) -----------------

    fun registerDiagnosticsAttachment(file: File): Boolean = false

    fun reportDiagnosticsFeedbackPrepared(fileName: String, sizeBytes: Long, attachmentRegistered: Boolean) {
        recordLog("debug", Tag, "diagnostics_feedback_prepared file=$fileName bytes=$sizeBytes")
    }

    fun updateUserContext(
        context: Context,
        userId: String?,
        selectedServerId: String?,
        selectedServerSlug: String?
    ) {
        // No external reporting backend; keep a local breadcrumb only.
        SlockAppLog.d(Tag, "user_context user=${!userId.isNullOrBlank()}")
    }

    fun reportAppFullLaunch() {}

    fun reportCaughtException(
        name: String,
        throwable: Throwable,
        extraData: Map<String, String> = emptyMap()
    ) {
        val safeName = name.ifBlank { throwable.javaClass.name }.take(MaxValueLength)
        recordLog(
            "warn",
            Tag,
            "caught_exception name=$safeName class=${throwable.javaClass.name} message=${throwable.message.orEmpty().take(MaxValueLength)} extras=${extraData.size}"
        )
    }

    fun testCrash(kind: String = "java"): Boolean {
        SlockAppLog.d(Tag, "test_crash kind=$kind ignored=hands_only")
        return false
    }

    fun enterScene(sceneName: String) {}

    fun exitScene(sceneName: String) {}

    fun reportKuiklySnapshot(payload: JSONObject) {
        val summary = payload.optString("summary").ifBlank { "route=unknown reason=unknown status=unknown" }
        SlockAppLog.d("SlockKuiklyPerfSnapshot", summary)
    }

    // ---- helpers ------------------------------------------------------------

    private fun String?.valueOrNull(): String? =
        this?.trim()?.take(MaxValueLength)?.takeIf { it.isNotBlank() }

    private fun pruneFeedbackArchives(feedbackDir: File) {
        feedbackDir.listFiles()
            ?.filter {
                it.isFile &&
                    it.name.startsWith(FeedbackFilePrefix) &&
                    it.name.endsWith(FeedbackZipExtension)
            }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(5)
            ?.forEach { it.delete() }
    }

    private fun feedbackDir(context: Context): File = File(context.cacheDir, FeedbackDirName)

}

internal fun uniqueDiagnosticsZipEntryName(name: String, seenNames: MutableSet<String>): String {
    val safeName = requireSafeDiagnosticsZipEntryName(name)
    if (seenNames.add(safeName)) return safeName
    val slash = safeName.lastIndexOf('/')
    val directory = if (slash >= 0) safeName.substring(0, slash + 1) else ""
    val fileName = if (slash >= 0) safeName.substring(slash + 1) else safeName
    val dot = fileName.lastIndexOf('.')
    val base = if (dot > 0) fileName.substring(0, dot) else fileName
    val extension = if (dot > 0) fileName.substring(dot) else ""
    var index = 2
    while (true) {
        val candidate = "$directory$base-$index$extension"
        if (seenNames.add(candidate)) return candidate
        index += 1
    }
}

internal fun requireSafeDiagnosticsZipEntryName(name: String): String {
    require(name.isNotBlank() && !name.startsWith('/') && !name.contains('\\')) {
        "Unsafe diagnostics zip entry path"
    }
    val segments = name.split('/')
    require(
        segments.all { segment ->
            segment.isNotBlank() && segment != "." && segment != ".." &&
                !segment.contains("slock", ignoreCase = true)
        }
    ) { "Unsafe diagnostics zip entry segment" }
    return name
}

internal fun shouldEmitDiagnostic(includeNonCritical: Boolean, authEvent: String?): Boolean =
    includeNonCritical || authEvent != null
