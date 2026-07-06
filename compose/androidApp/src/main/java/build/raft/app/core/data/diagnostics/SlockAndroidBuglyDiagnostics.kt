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
 * App diagnostics: an on-device JSONL log ring + feedback-archive builder.
 *
 * Bugly was removed (crash/native/ANR reporting is handled by the Quiver SDK;
 * telemetry/scene/user-context reporting is dropped). This object keeps its
 * public API so callers are unchanged, but the reporting methods are now
 * no-ops — only local logging and the feedback archive remain. (Named
 * *Bugly for continuity; rename is a follow-up.)
 */
object SlockAndroidBuglyDiagnostics {
    private const val Tag = "SlockAndroidDiagnostics"
    private const val FeedbackDirName = "slock-feedback"
    private const val FeedbackFilePrefix = "slock-feedback-"
    private const val FeedbackZipExtension = ".zip"
    private const val FeedbackSnapshotName = "slock-diagnostics-summary.txt"
    private const val RecentDiagnosticsMaxChars = 120_000
    private const val MaxValueLength = 512

    fun init(context: Context) {
        SlockFileDiagnosticsLogger.init(context.applicationContext)
        SlockDiagnosticsLogger.d(
            Tag,
            "initialized appVersion=${BuildConfig.VERSION_NAME} debug=${BuildConfig.DEBUG} backend=jsonl bugly=removed"
        )
    }

    /** Crash/telemetry reporting moved to the Quiver SDK — nothing here now. */
    fun isInitialized(): Boolean = false

    fun isDiagnoseInitialized(): Boolean = false

    fun hasFallbackDiagnosticsLogs(context: Context): Boolean =
        SlockFileDiagnosticsLogger.hasFiles(context.applicationContext)

    fun recordLog(level: String, tag: String, message: String) {
        val cleanTag = tag.valueOrNull() ?: return
        val cleanMessage = DiagnosticsSanitizer.sanitize(message).takeIf { it.isNotBlank() } ?: return
        writeLogcat(level, cleanTag, cleanMessage)
        SlockFileDiagnosticsLogger.record(level, cleanTag, cleanMessage)
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
        SlockFileDiagnosticsLogger.flush()
        val feedbackDir = feedbackDir(appContext).apply { mkdirs() }
        pruneFeedbackArchives(feedbackDir)
        val archive = File(feedbackDir, "$FeedbackFilePrefix${System.currentTimeMillis()}$FeedbackZipExtension")
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(FeedbackSnapshotName))
            zip.write(summaryText.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()

            val seenNames = mutableSetOf<String>()
            val recentJsonl = SlockFileDiagnosticsLogger.exportRecentJsonl()
            if (recentJsonl.isNotBlank()) {
                zip.putNextEntry(ZipEntry("app_logs/recent-memory.jsonl".uniqueZipEntryName(seenNames)))
                zip.write(recentJsonl.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
            SlockFileDiagnosticsLogger.logFiles(appContext).forEach { file ->
                if (file.isFile && file.length() > 0L) {
                    val entryName = "app_logs/${file.name}".uniqueZipEntryName(seenNames)
                    zip.putNextEntry(ZipEntry(entryName))
                    file.inputStream().buffered().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        recordLog(
            "info",
            Tag,
            "diagnostics_feedback_archive file=${archive.name} bytes=${archive.length()} app_log_files=${SlockFileDiagnosticsLogger.logFiles(appContext).size}"
        )
        return archive
    }

    fun exportRecentDiagnosticsText(context: Context, maxChars: Int = RecentDiagnosticsMaxChars): String {
        val text = SlockFileDiagnosticsLogger.exportRecentText(context.applicationContext, maxChars)
        return text.ifBlank { "No local diagnostics log files are currently available." }
    }

    fun uploadDiagnostics(context: Context, attachment: File, summary: String): Boolean {
        recordLog("debug", Tag, "diagnostics_upload skipped attachment=${attachment.name} summary=$summary")
        return false
    }

    fun clearLocalDiagnosticsFiles(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            feedbackDir(appContext).listFiles()
                ?.filter { it.isFile && it.name.startsWith(FeedbackFilePrefix) }
                ?.forEach { it.delete() }
        }
        SlockFileDiagnosticsLogger.clearFiles(appContext)
        recordLog("info", Tag, "diagnostics_local_files_clear_requested")
    }

    // ---- Reporting hooks retained as no-ops (Bugly removed) -----------------

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
        SlockDiagnosticsLogger.d(Tag, "user_context user=${!userId.isNullOrBlank()}")
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
        SlockDiagnosticsLogger.d(Tag, "test_crash kind=$kind ignored=bugly_removed")
        return false
    }

    fun enterScene(sceneName: String) {}

    fun exitScene(sceneName: String) {}

    fun reportKuiklySnapshot(payload: JSONObject) {
        val summary = payload.optString("summary").ifBlank { "route=unknown reason=unknown status=unknown" }
        SlockDiagnosticsLogger.d("SlockKuiklyPerfSnapshot", summary)
    }

    // ---- helpers ------------------------------------------------------------

    private fun String?.valueOrNull(): String? =
        this?.trim()?.take(MaxValueLength)?.takeIf { it.isNotBlank() }

    private fun pruneFeedbackArchives(feedbackDir: File) {
        feedbackDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(FeedbackFilePrefix) && it.name.endsWith(FeedbackZipExtension) }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(5)
            ?.forEach { it.delete() }
    }

    private fun feedbackDir(context: Context): File = File(context.cacheDir, FeedbackDirName)

    private fun String.uniqueZipEntryName(seenNames: MutableSet<String>): String {
        if (seenNames.add(this)) return this
        val slash = lastIndexOf('/')
        val directory = if (slash >= 0) substring(0, slash + 1) else ""
        val name = if (slash >= 0) substring(slash + 1) else this
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var index = 2
        while (true) {
            val candidate = "$directory$base-$index$extension"
            if (seenNames.add(candidate)) return candidate
            index += 1
        }
    }
}
