package build.raft.app.core.data.diagnostics

import build.raft.app.BuildConfig
import android.content.Context
import android.os.Build
import android.util.Log
import com.tencent.bugly.library.Bugly
import com.tencent.bugly.library.BuglyAppVersionMode
import com.tencent.bugly.library.BuglyBuilder
import com.tencent.bugly.library.BuglyConstants
import com.tencent.bugly.library.BuglyLogLevel
import com.tencent.bugly.launch.AppLaunchProxy
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

object SlockAndroidBuglyDiagnostics {
    private const val Tag = "SlockAndroidBugly"
    private const val SceneTag = "SlockPerfScene"
    private const val SnapshotTag = "SlockKuiklyPerfSnapshot"
    private const val SnapshotEvent = "kuikly_perf_snapshot"
    private const val LooperMetricMonitor = "looper_metric"
    private const val LooperStackMonitor = "looper_stack"
    private const val MaxBuglyValueLength = 512
    private const val TdLogDirName = "bugly_tdlog"
    private const val FeedbackDirName = "slock-feedback"
    private const val FeedbackFilePrefix = "slock-feedback-"
    private const val FeedbackZipExtension = ".zip"
    private const val FeedbackSnapshotName = "slock-diagnostics-summary.txt"
    private const val RecentDiagnosticsMaxChars = 120_000

    private val initialized = AtomicBoolean(false)
    private val initAttempted = AtomicBoolean(false)
    private val fullLaunchReported = AtomicBoolean(false)
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        SlockFileDiagnosticsLogger.init(context.applicationContext)
        if (!initAttempted.compareAndSet(false, true)) return
        val appId = BuildConfig.BUGLY_ANDROID_APP_ID.trim()
        val appKey = BuildConfig.BUGLY_ANDROID_APP_KEY.trim()
        if (appId.isBlank() || appKey.isBlank()) {
            SlockDiagnosticsLogger.d(Tag, "disabled reason=missing_android_bugly_config")
            return
        }

        runCatching {
            val appContext = context.applicationContext
            val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            val builder =
                BuglyBuilder(appId, appKey).apply {
                    setServerHostType(BuglyBuilder.ServerHostTypeBuglyPro)
                    appVersion = BuildConfig.VERSION_NAME
                    buildNumber = BuildConfig.VERSION_CODE.toString()
                    appVersionType =
                        if (BuildConfig.DEBUG) {
                            BuglyAppVersionMode.DEBUG
                        } else {
                            BuglyAppVersionMode.RELEASE
                        }
                    appChannel = "android"
                    this.deviceModel = deviceModel
                    debugMode = false
                    logLevel = BuglyLogLevel.LEVEL_WARN
                    enableAllThreadStackCrash = true
                    enableAllThreadStackAnr = true
                    enableCrashProtect = true
                    initAppState = BuglyBuilder.APP_STATE_FOREGROUND
                }
            val result = Bugly.init(appContext, builder)
            if (result) {
                initialized.set(true)
                Bugly.setPerformanceMonitorAble(LooperMetricMonitor, true)
                Bugly.setPerformanceMonitorAble(LooperStackMonitor, true)
                Bugly.updateDeviceModel(appContext, deviceModel)
                SlockDiagnosticsLogger.d(
                    Tag,
                    "initialized appVersion=${BuildConfig.VERSION_NAME} debug=${BuildConfig.DEBUG} tdlog=disabled appLogger=jsonl"
                )
            } else {
                SlockDiagnosticsLogger.w(Tag, "init_returned_false")
            }
        }.onFailure { error ->
            SlockDiagnosticsLogger.w(Tag, "init_failed ${error.javaClass.simpleName}:${error.message.orEmpty()}")
        }
    }

    fun isInitialized(): Boolean = initialized.get()

    fun isDiagnoseInitialized(): Boolean = false

    fun hasFallbackDiagnosticsLogs(context: Context): Boolean =
        SlockFileDiagnosticsLogger.hasFiles(context.applicationContext)

    fun recordLog(level: String, tag: String, message: String) {
        val cleanTag = tag.buglyValueOrNull() ?: return
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
        recordLog(
            "debug",
            Tag,
            "diagnostics_upload skipped reason=tdlog_disabled attachment=${attachment.name} summary=$summary"
        )
        return false
    }

    fun clearLocalDiagnosticsFiles(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            feedbackDir(appContext).listFiles()
                ?.filter { it.isFile && it.name.startsWith(FeedbackFilePrefix) }
                ?.forEach { it.delete() }
        }
        runCatching {
            tdLogDir(appContext).walkBottomUp()
                .filter { it.isFile }
                .forEach { it.delete() }
        }
        SlockFileDiagnosticsLogger.clearFiles(appContext)
        recordLog("info", Tag, "diagnostics_local_files_clear_requested")
    }

    fun registerDiagnosticsAttachment(file: File): Boolean {
        if (!initialized.get()) {
            SlockDiagnosticsLogger.d(Tag, "diagnostics_attachment bugly=disabled")
            return false
        }
        if (!file.isFile || file.length() <= 0L) {
            SlockDiagnosticsLogger.w(Tag, "diagnostics_attachment bugly=skipped reason=invalid_file")
            return false
        }
        return runCatching {
            Bugly.setAdditionalAttachmentPaths(arrayOf(file.absolutePath))
        }.onSuccess { registered ->
            SlockDiagnosticsLogger.d(
                Tag,
                "diagnostics_attachment bugly=${if (registered) "registered" else "not_registered"} bytes=${file.length()}"
            )
        }.onFailure { error ->
            SlockDiagnosticsLogger.w(
                Tag,
                "diagnostics_attachment bugly=failed ${error.javaClass.simpleName}:${error.message.orEmpty()}"
            )
        }.getOrDefault(false)
    }

    fun reportDiagnosticsFeedbackPrepared(
        fileName: String,
        sizeBytes: Long,
        attachmentRegistered: Boolean
    ) {
        if (!initialized.get()) {
            SlockDiagnosticsLogger.d(Tag, "diagnostics_feedback bugly=disabled")
            return
        }
        val metrics = mapOf(
            "size_bytes" to sizeBytes.coerceAtLeast(0L).toDouble()
        )
        val dimensions = mapOf(
            "file_name" to (fileName.buglyValueOrNull() ?: "slock-feedback.txt"),
            "attachment_registered" to attachmentRegistered.toString()
        )
        runCatching {
            Bugly.monitorEvent("slock_diagnostics_feedback_prepared", metrics, dimensions)
        }.onSuccess { reported ->
            SlockDiagnosticsLogger.d(Tag, "diagnostics_feedback bugly_event=${if (reported) "reported" else "not_reported"}")
        }.onFailure { error ->
            SlockDiagnosticsLogger.w(
                Tag,
                "diagnostics_feedback bugly_event=failed ${error.javaClass.simpleName}:${error.message.orEmpty()}"
            )
        }
    }

    fun updateUserContext(
        context: Context,
        userId: String?,
        selectedServerId: String?,
        selectedServerSlug: String?
    ) {
        if (!initialized.get()) {
            SlockDiagnosticsLogger.d(Tag, "user_context bugly=disabled")
            return
        }
        val appContext = context.applicationContext
        runCatching {
            val cleanUserId = userId.buglyValueOrNull()
            Bugly.updateUserId(appContext, cleanUserId.orEmpty())
            putOrRemoveUserData(appContext, "slock_user_id", cleanUserId)
            putOrRemoveUserData(appContext, "slock_server_id", selectedServerId.buglyValueOrNull())
            putOrRemoveUserData(appContext, "slock_server_slug", selectedServerSlug.buglyValueOrNull())
            putOrRemoveUserData(appContext, "slock_platform", "android")
        }.onSuccess {
            SlockDiagnosticsLogger.d(Tag, "user_context bugly=updated user=${userId.isNullOrBlank().not()}")
        }.onFailure { error ->
            SlockDiagnosticsLogger.w(
                Tag,
                "user_context bugly=failed ${error.javaClass.simpleName}:${error.message.orEmpty()}"
            )
        }
    }

    fun reportAppFullLaunch() {
        if (!initialized.get()) {
            SlockDiagnosticsLogger.d(Tag, "full_launch bugly=disabled")
            return
        }
        if (!fullLaunchReported.compareAndSet(false, true)) return
        runCatching {
            AppLaunchProxy.getAppLaunch().reportAppFullLaunch()
        }.onSuccess {
            SlockDiagnosticsLogger.d(Tag, "full_launch bugly=reported")
        }.onFailure { error ->
            SlockDiagnosticsLogger.w(
                Tag,
                "full_launch bugly=failed ${error.javaClass.simpleName}:${error.message.orEmpty()}"
            )
        }
    }

    fun reportCaughtException(
        name: String,
        throwable: Throwable,
        extraData: Map<String, String> = emptyMap()
    ) {
        if (!initialized.get()) {
            SlockDiagnosticsLogger.d(Tag, "caught_exception name=$name bugly=disabled")
            return
        }

        val safeName = name.ifBlank { throwable.javaClass.name }.take(MaxBuglyValueLength)
        val message = throwable.message.orEmpty().take(MaxBuglyValueLength)
        val stackTrace = android.util.Log.getStackTraceString(throwable)
        val values =
            buildMap {
                put("exceptionClass", throwable.javaClass.name.take(MaxBuglyValueLength))
                putAll(extraData.sanitizedForBugly())
            }

        runCatching {
            Bugly.postException(
                Thread.currentThread(),
                BuglyConstants.ISSUE_TYPE_JAVA_CATCHED,
                safeName,
                message,
                stackTrace,
                values
            )
        }.onSuccess {
            SlockDiagnosticsLogger.w(Tag, "caught_exception name=$safeName bugly=reported")
        }.onFailure { error ->
            SlockDiagnosticsLogger.w(
                Tag,
                "caught_exception name=$safeName bugly=failed ${error.javaClass.simpleName}:${error.message.orEmpty()}"
            )
        }
    }

    fun testCrash(kind: String = "java"): Boolean {
        if (!initialized.get()) {
            SlockDiagnosticsLogger.d(Tag, "test_crash kind=$kind bugly=disabled")
            return false
        }
        val type =
            when (kind.lowercase()) {
                "native", "jni", "so" -> BuglyConstants.NATIVE_CRASH
                "anr" -> BuglyConstants.ANR_CRASH
                else -> BuglyConstants.JAVA_CRASH
            }
        return runCatching {
            Bugly.testCrash(type)
            true
        }.getOrElse { error ->
            SlockDiagnosticsLogger.w(
                Tag,
                "test_crash kind=$kind bugly=failed ${error.javaClass.simpleName}:${error.message.orEmpty()}"
            )
            false
        }
    }

    fun enterScene(sceneName: String) {
        updateScene("enter", sceneName)
    }

    fun exitScene(sceneName: String) {
        updateScene("exit", sceneName)
    }

    private fun updateScene(action: String, sceneName: String) {
        if (!initialized.get()) {
            SlockDiagnosticsLogger.d(SceneTag, "$action scene=$sceneName bugly=disabled")
            return
        }
        runCatching {
            if (action == "exit") {
                Bugly.exitScene(sceneName)
            } else {
                Bugly.enterScene(sceneName)
            }
            Bugly.setTestLabels(sceneName)
            SlockDiagnosticsLogger.d(SceneTag, "$action scene=$sceneName bugly=ok")
        }.onFailure { error ->
            SlockDiagnosticsLogger.w(
                SceneTag,
                "$action scene=$sceneName bugly=failed ${error.javaClass.simpleName}:${error.message.orEmpty()}"
            )
        }
    }

    fun reportKuiklySnapshot(payload: JSONObject) {
        val summary = payload.optString("summary").ifBlank { "route=unknown reason=unknown status=unknown" }

        if (!initialized.get()) {
            SlockDiagnosticsLogger.d(SnapshotTag, "$summary bugly=disabled")
            return
        }

        val metrics = payload.optJSONObjectOrString("metrics").toDoubleMap()
        val dimensions = payload.optJSONObjectOrString("dimensions").toStringMap()
        val reported =
            runCatching {
                Bugly.monitorEvent(SnapshotEvent, metrics, dimensions)
            }.getOrElse { error ->
                SlockDiagnosticsLogger.w(
                    SnapshotTag,
                    "$summary bugly=failed ${error.javaClass.simpleName}:${error.message.orEmpty()}"
                )
                false
            }
        SlockDiagnosticsLogger.d(
            SnapshotTag,
            "$summary bugly=${if (reported) "reported" else "not_reported"} metrics=${metrics.size}"
        )
    }

    private fun JSONObject.optJSONObjectOrString(key: String): JSONObject? {
        optJSONObject(key)?.let { return it }
        val raw = optString(key).takeIf { it.isNotBlank() } ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun JSONObject?.toDoubleMap(): Map<String, Double> {
        if (this == null) return emptyMap()
        val result = mutableMapOf<String, Double>()
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = optDouble(key)
            if (!value.isNaN()) {
                result[key] = value
            }
        }
        return result
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        val result = mutableMapOf<String, String>()
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = optString(key)
            if (value.isNotBlank()) {
                result[key] = value
            }
        }
        return result
    }

    private fun Map<String, String>.sanitizedForBugly(): Map<String, String> =
        entries
            .asSequence()
            .mapNotNull { (key, value) ->
                val safeKey = key.buglyValueOrNull() ?: return@mapNotNull null
                val safeValue = value.buglyValueOrNull() ?: return@mapNotNull null
                safeKey to safeValue
            }
            .toMap()

    private fun String?.buglyValueOrNull(): String? =
        this
            ?.trim()
            ?.take(MaxBuglyValueLength)
            ?.takeIf { it.isNotBlank() }

    private fun putOrRemoveUserData(context: Context, key: String, value: String?) {
        if (value.isNullOrBlank()) {
            Bugly.removeUserData(context, key)
        } else {
            Bugly.putUserData(context, key, value)
        }
    }

    private fun pruneFeedbackArchives(feedbackDir: File) {
        feedbackDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(FeedbackFilePrefix) && it.name.endsWith(FeedbackZipExtension) }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(5)
            ?.forEach { it.delete() }
    }

    private fun tdLogDir(context: Context): File = File(context.filesDir, TdLogDirName)

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
