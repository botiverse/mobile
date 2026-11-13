package build.raft.app.core.data.diagnostics

import build.raft.app.BuildConfig
import build.raft.app.RaftDiagnosticsVolumePolicy
import build.raft.app.RaftFeedbackDiagnosticsBudget
import build.raft.app.RaftFeedbackLogFile
import build.raft.app.RaftFeedbackLogPick
import build.raft.app.RaftVerboseDiagnostics
import android.app.Application
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
object RaftAndroidLocalDiagnostics {
    private const val Tag = "SlockAndroidDiagnostics"
    private const val InFlightTag = "SlockSharedNetwork"
    private const val FeedbackDirName = "raft-feedback"
    private const val FeedbackFilePrefix = "raft-feedback-"
    private const val FeedbackZipExtension = ".zip"
    private const val FeedbackSnapshotName = "raft-diagnostics-summary.txt"
    private const val RecentDiagnosticsMaxChars = 120_000
    /** Raw bytes of app logs (in-memory tail + shards) a feedback zip carries. */
    private const val FeedbackLogBudgetBytes = RaftFeedbackDiagnosticsBudget.ReportBudgetBytes
    /**
     * `recent-memory.jsonl`: the last [RaftGeneralLogRing.RecentMaxAgeMillis] at full detail
     * (including the lines the volume policy summarized out of the log), at most this many chars.
     */
    private const val RecentMemoryMaxChars = 192_000
    private const val MaxUnfinishedRequestLines = 8
    private const val MaxValueLength = 512
    private const val MaxQueuedMessageLength = 8_192
    private const val MaxQueuedThreadNameLength = 128

    /** Android-native lines (RaftAppLog); shared lines are admitted by the shared queue's own policy. */
    private val volumePolicy = RaftDiagnosticsVolumePolicy()
    private val inFlightRequests = RaftInFlightRequests()

    private val dispatchQueue =
        RaftDiagnosticsDispatchQueue(processor = ::processPendingLog)

    fun init(context: Context) {
        RaftGeneralLogRing.init(context.applicationContext)
        RaftAuthCriticalJournal.init(context.applicationContext)
        // On the telemetry thread, with the earlier processes' memory telemetry, before this
        // process writes its own (also keeps the exit-history binder call off the main thread).
        val appContext = context.applicationContext
        val application = appContext as? Application
        if (application != null) {
            RaftMemoryTelemetry.install(application) { previous ->
                AndroidHistoricalProcessExitDiagnostics.recordLatest(appContext, previous)
            }
        } else {
            AndroidHistoricalProcessExitDiagnostics.recordLatest(appContext)
        }
        RaftAppLog.d(
            Tag,
            "initialized appVersion=${BuildConfig.VERSION_NAME} debug=${BuildConfig.DEBUG} backend=jsonl hands=crash"
        )
    }

    /** Crash/telemetry reporting moved to the Hands SDK — nothing here now. */
    fun isInitialized(): Boolean = false

    fun isDiagnoseInitialized(): Boolean = false

    fun hasFallbackDiagnosticsLogs(context: Context): Boolean =
        RaftGeneralLogRing.hasFiles(context.applicationContext) ||
            RaftAuthCriticalJournal.hasFiles(context.applicationContext)

    fun recordLog(
        level: String,
        tag: String,
        message: String,
        includeNonCritical: Boolean = true,
    ) {
        enqueueLog(level, tag, message, includeNonCritical, DiagnosticCriticalHint.None)
    }

    /**
     * A line from the shared log queue; [persist] false keeps it out of the persistent log, and
     * [recent] (a summarized line) still puts it in the in-memory ring of the last minutes.
     */
    fun recordSharedLog(level: String, tag: String, message: String, persist: Boolean, recent: Boolean = persist) {
        enqueueLog(
            level,
            tag,
            message,
            includeNonCritical = true,
            criticalHint = DiagnosticCriticalHint.None,
            persist = persist,
            volumeAdmitted = true,
            recent = recent,
        )
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
        persist: Boolean = true,
        volumeAdmitted: Boolean = false,
        recent: Boolean = persist,
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
                persist = persist,
                volumeAdmitted = volumeAdmitted,
                recent = recent,
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
        inFlightRequests.observe(sanitized.message, sanitized.timestampMillis)
        if (authEvent != null) {
            RaftAuthCriticalJournal.record(sanitized, authEvent)
        } else if (!entry.persist) {
            // Summarized out of the log by the shared policy: the full-detail ring only.
            if (entry.recent && RaftPersistentDiagnosticsPolicy.shouldPersist(sanitized.level, sanitized.message)) {
                RaftGeneralLogRing.recordRecentOnly(sanitized)
            }
        } else if (RaftPersistentDiagnosticsPolicy.shouldPersist(sanitized.level, sanitized.message)) {
            // Logcat above still gets every line; only the persistent ring is rate limited.
            if (entry.volumeAdmitted) {
                RaftGeneralLogRing.record(sanitized)
                return
            }
            val admission =
                volumePolicy.admit(
                    sanitized.level,
                    sanitized.tag,
                    sanitized.message,
                    sanitized.timestampMillis,
                    verbose = RaftVerboseDiagnostics.enabled,
                )
            admission.summaries.forEach { summary ->
                RaftGeneralLogRing.record(
                    sanitized.copy(level = "info", tag = RaftDiagnosticsVolumePolicy.SummaryTag, message = summary)
                )
            }
            if (admission.persist) {
                RaftGeneralLogRing.record(sanitized)
            } else if (admission.recent) {
                RaftGeneralLogRing.recordRecentOnly(sanitized)
            }
        }
    }

    /**
     * Before an export reads the ring: the counts the volume policy is still holding back, and the
     * requests that started and have not finished (their start lines are not persisted).
     */
    private fun drainDiagnosticsSummaries() {
        val now = System.currentTimeMillis()
        volumePolicy.drain(now).forEach { summary ->
            recordLog("info", RaftDiagnosticsVolumePolicy.SummaryTag, summary)
        }
        // Requests that hang stay open across exports (fb-93123592: 20 such lines per report, some
        // 4.7 h old, repeated in every report): the newest few, and one line for the rest.
        val unfinished = inFlightRequests.unfinished(now)
        if (unfinished.size > MaxUnfinishedRequestLines) {
            recordLog(
                "warn",
                InFlightTag,
                "network_request_unfinished_more count=${unfinished.size - MaxUnfinishedRequestLines} " +
                    "listed=$MaxUnfinishedRequestLines total=${unfinished.size}"
            )
        }
        unfinished.takeLast(MaxUnfinishedRequestLines).forEach { line -> recordLog("warn", InFlightTag, line) }
    }

    /** Writes the summaries of closed windows (memory-telemetry thread, every 30s in foreground). */
    internal fun sweepDiagnosticsSummaries() {
        volumePolicy.sweep(System.currentTimeMillis()).forEach { summary ->
            recordLog("info", RaftDiagnosticsVolumePolicy.SummaryTag, summary)
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
        drainDiagnosticsSummaries()
        check(flush()) { "diagnostics_flush_timeout operation=prepare_archive" }
        val feedbackDir = feedbackDir(appContext).apply { mkdirs() }
        pruneFeedbackArchives(feedbackDir)
        val archive = File(feedbackDir, "$FeedbackFilePrefix${System.currentTimeMillis()}$FeedbackZipExtension")
        var selectedFiles = 0
        var selectedBytes = 0L
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(FeedbackSnapshotName))
            zip.write(summaryText.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()

            val seenNames = mutableSetOf<String>()
            val performanceTraceJsonl = RaftPerformanceTraceRing.exportRecentJsonl()
            if (performanceTraceJsonl.isNotBlank()) {
                zip.putNextEntry(ZipEntry(uniqueDiagnosticsZipEntryName("performance/recent-15s.jsonl", seenNames)))
                zip.write(performanceTraceJsonl.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
            val recentBytes =
                RaftGeneralLogRing.exportRecentJsonl(RecentMemoryMaxChars).toByteArray(StandardCharsets.UTF_8)
            if (recentBytes.isNotEmpty()) {
                zip.putNextEntry(ZipEntry(uniqueDiagnosticsZipEntryName("app_logs/recent-memory.jsonl", seenNames)))
                zip.write(recentBytes)
                zip.closeEntry()
            }
            // Only the newest launch(es) within the budget, not every rotated shard.
            val selection = selectFeedbackLogFiles(appContext, budgetBytes = FeedbackLogBudgetBytes - recentBytes.size)
            selection.picks.forEach { pick ->
                val file = File(pick.file.path)
                val entryName = uniqueDiagnosticsZipEntryName("app_logs/${selection.entryNames.getValue(pick.file.path)}", seenNames)
                zip.putNextEntry(ZipEntry(entryName))
                file.inputStream().buffered().use { input ->
                    if (pick.truncated) copyTailFromLineStart(input, file.length(), pick.includedBytes, zip)
                    else input.copyTo(zip)
                }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry(uniqueDiagnosticsZipEntryName("app_logs/feedback-selection.txt", seenNames)))
            zip.write(selection.description.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
            selectedFiles = selection.picks.size
            selectedBytes = selection.picks.sumOf { it.includedBytes }
            RaftAuthCriticalJournal.logFiles(appContext).forEach { file ->
                val relativePath = RaftAuthCriticalJournal.relativeLogPath(appContext, file)
                val entryName = uniqueDiagnosticsZipEntryName("auth_logs/$relativePath", seenNames)
                zip.putNextEntry(ZipEntry(entryName))
                file.inputStream().buffered().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
            AndroidHistoricalProcessExitDiagnostics.traceFiles(appContext).forEach { file ->
                val entryName =
                    uniqueDiagnosticsZipEntryName("process_exit_traces/${file.name}", seenNames)
                zip.putNextEntry(ZipEntry(entryName))
                file.inputStream().buffered().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
        recordLog(
            "info",
            Tag,
            "diagnostics_feedback_archive file=${archive.name} bytes=${archive.length()} " +
                "app_log_files=$selectedFiles/${RaftGeneralLogRing.logFiles(appContext).size} app_log_bytes=$selectedBytes"
        )
        return archive
    }

    fun exportRecentDiagnosticsText(context: Context, maxChars: Int = RecentDiagnosticsMaxChars): String {
        val appContext = context.applicationContext
        drainDiagnosticsSummaries()
        if (!flush()) {
            return "Diagnostics snapshot unavailable because pending logs did not flush."
        }
        val authText = RaftAuthCriticalJournal.exportRecentText(appContext, maxChars / 2)
        val generalText = RaftGeneralLogRing.exportRecentText(appContext, maxChars - authText.length)
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
        RaftGeneralLogRing.clearFiles(appContext)
        RaftAuthCriticalJournal.clearFiles(appContext)
        recordLog("info", Tag, "diagnostics_local_files_clear_requested")
    }

    internal fun flush(): Boolean =
        dispatchQueue.flush() &&
            RaftGeneralLogRing.flush() &&
            RaftAuthCriticalJournal.flush()

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
        RaftAppLog.d(Tag, "user_context user=${!userId.isNullOrBlank()}")
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
        RaftAppLog.d(Tag, "test_crash kind=$kind ignored=hands_only")
        return false
    }

    fun enterScene(sceneName: String) {}

    fun exitScene(sceneName: String) {}

    fun reportKuiklySnapshot(payload: JSONObject) {
        val summary = payload.optString("summary").ifBlank { "route=unknown reason=unknown status=unknown" }
        RaftAppLog.d("SlockKuiklyPerfSnapshot", summary)
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

    internal fun feedbackDir(context: Context): File = File(context.cacheDir, FeedbackDirName)

    internal class FeedbackLogSelection(
        val picks: List<RaftFeedbackLogPick>,
        val entryNames: Map<String, String>,
        val description: String,
    )

    internal fun selectFeedbackLogFiles(context: Context, budgetBytes: Long): FeedbackLogSelection {
        val appContext = context.applicationContext
        val files = RaftGeneralLogRing.logFiles(appContext).filter { it.isFile && it.length() > 0L }
        val entryNames = files.associate { it.path to RaftGeneralLogRing.archiveRelativeLogPath(appContext, it) }
        val candidates =
            files.map { file ->
                RaftFeedbackLogFile(
                    path = file.path,
                    launchId = file.parentFile?.name.orEmpty(),
                    sizeBytes = file.length(),
                    modifiedMillis = file.lastModified(),
                )
            }
        val budget = budgetBytes.coerceAtLeast(RaftFeedbackDiagnosticsBudget.MinTailBytes)
        val picks =
            RaftFeedbackDiagnosticsBudget.select(
                candidates,
                budget,
                nowMillis = System.currentTimeMillis(),
                maxAgeMillis = RaftFeedbackDiagnosticsBudget.ReportMaxAgeMillis,
            )
        val description =
            RaftFeedbackDiagnosticsBudget.describe(candidates, picks, budget) { entryNames.getValue(it.path) }
        return FeedbackLogSelection(picks, entryNames, description)
    }

}

/**
 * Copies the last [tailBytes] of [input] (length [length]) from the first full line on, so a
 * truncated shard in the feedback zip still parses as JSONL.
 */
internal fun copyTailFromLineStart(input: java.io.InputStream, length: Long, tailBytes: Long, output: java.io.OutputStream) {
    if (length > tailBytes) {
        // Stop one byte early: if the tail starts exactly at a line, that byte is the newline.
        var remaining = length - tailBytes - 1L
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped <= 0L) break
            remaining -= skipped
        }
        while (true) {
            val next = input.read()
            if (next < 0 || next == '\n'.code) break
        }
    }
    input.copyTo(output)
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
