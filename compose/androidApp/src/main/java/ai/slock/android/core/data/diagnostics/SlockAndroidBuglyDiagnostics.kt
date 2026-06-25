package ai.slock.android.core.data.diagnostics

import ai.slock.android.BuildConfig
import android.content.Context
import android.os.Build
import com.tencent.bugly.library.Bugly
import com.tencent.bugly.library.BuglyAppVersionMode
import com.tencent.bugly.library.BuglyBuilder
import com.tencent.bugly.library.BuglyConstants
import com.tencent.bugly.library.BuglyLogLevel
import com.tencent.bugly.launch.AppLaunchProxy
import com.tencent.tddiag.TDDiag
import com.tencent.tddiag.TDDiagConfig
import com.tencent.tddiag.UploadParam
import com.tencent.tddiag.logger.TDLog
import com.tencent.tddiag.logger.TDLogConfig
import com.tencent.tddiag.protocol.LogLevel
import com.tencent.tddiag.protocol.UploadListener
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
    private const val FallbackLogDirName = "slock_diagnostics_logs"
    private const val FallbackLogFileName = "slock-diagnostics.log"
    private const val FallbackLogMaxBytes = 2L * 1024L * 1024L
    private const val FallbackLogMaxFiles = 4
    private const val TdLogMaxFileSize = 50L * 1024L * 1024L
    private const val TdLogMaxAliveDay = 7
    private const val DiagnoseUploadTimeWindowMillis = 7L * 24L * 60L * 60L * 1000L
    private const val RecentDiagnosticsMaxChars = 120_000
    private const val BinaryPreviewSampleBytes = 512
    private const val BinaryPreviewSuspiciousPercent = 5
    private val BinaryDiagnosticExtensions = setOf("xlog", "mmap", "zip", "gz", "bin")

    private val initialized = AtomicBoolean(false)
    private val diagnoseInitialized = AtomicBoolean(false)
    private val initAttempted = AtomicBoolean(false)
    private val fullLaunchReported = AtomicBoolean(false)
    private val fallbackLogLock = Any()
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
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
                    debugMode = BuildConfig.DEBUG
                    logLevel =
                        if (BuildConfig.DEBUG) {
                            BuglyLogLevel.LEVEL_DEBUG
                        } else {
                            BuglyLogLevel.LEVEL_WARN
                        }
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
                initializeDiagnose(appContext, appId, appKey)
                SlockDiagnosticsLogger.d(
                    Tag,
                    "initialized appVersion=${BuildConfig.VERSION_NAME} debug=${BuildConfig.DEBUG} diagnose=${diagnoseInitialized.get()}"
                )
            } else {
                SlockDiagnosticsLogger.w(Tag, "init_returned_false")
            }
        }.onFailure { error ->
            SlockDiagnosticsLogger.w(Tag, "init_failed ${error.javaClass.simpleName}:${error.message.orEmpty()}")
        }
    }

    fun isInitialized(): Boolean = initialized.get()

    fun isDiagnoseInitialized(): Boolean = diagnoseInitialized.get()

    fun hasFallbackDiagnosticsLogs(context: Context): Boolean =
        fallbackLogFiles(context.applicationContext).any { it.isFile && it.length() > 0L }

    fun recordLog(level: String, tag: String, message: String) {
        val cleanTag = tag.buglyValueOrNull() ?: return
        val cleanMessage = DiagnosticsSanitizer.sanitize(message).buglyValueOrNull() ?: return
        writeFallbackLog(level, cleanTag, cleanMessage)
        // Keep Logcat single-sourced through SlockDiagnosticsLogger/direct host logs.
        // TDLog and BuglyCustomLog mirror app logs back into Logcat on some devices,
        // which turns one diagnostic event into duplicate plain/bracketed entries.
        // The fallback log files written above are still attached to feedback archives.
    }

    fun prepareDiagnosticsFeedbackArchive(context: Context, summaryText: String): File {
        val appContext = context.applicationContext
        flushDiagnoseLogs()
        val feedbackDir = feedbackDir(appContext).apply { mkdirs() }
        pruneFeedbackArchives(feedbackDir)
        val archive = File(feedbackDir, "$FeedbackFilePrefix${System.currentTimeMillis()}$FeedbackZipExtension")
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(FeedbackSnapshotName))
            zip.write(summaryText.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()

            val files = diagnoseLogFiles(appContext)
            val seenNames = mutableSetOf<String>()
            files.forEach { file ->
                if (file.isFile && file.length() > 0L) {
                    val entryName = "bugly_tdlog/${file.name}".uniqueZipEntryName(seenNames)
                    zip.putNextEntry(ZipEntry(entryName))
                    file.inputStream().buffered().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            fallbackLogFiles(appContext).forEach { file ->
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
            "diagnostics_feedback_archive file=${archive.name} bytes=${archive.length()} tdlog_files=${diagnoseLogFiles(appContext).size} app_log_files=${fallbackLogFiles(appContext).size}"
        )
        return archive
    }

    fun exportRecentDiagnosticsText(context: Context, maxChars: Int = RecentDiagnosticsMaxChars): String {
        val appContext = context.applicationContext
        flushDiagnoseLogs()
        val files = diagnoseLogFiles(appContext)
        val fallbackFiles = fallbackLogFiles(appContext)
        if (files.isEmpty() && fallbackFiles.isEmpty()) {
            return "No local diagnostics log files are currently available."
        }
        val sourceFiles = if (files.isNotEmpty()) files else fallbackFiles
        val sourceLabel =
            if (files.isNotEmpty()) {
                "Bugly TDLog recent diagnostics"
            } else {
                "App-owned recent diagnostics"
            }
        val header =
            buildString {
                appendLine(sourceLabel)
                appendLine("Files: ${sourceFiles.size}")
            }
        val budget = (maxChars - header.length).coerceAtLeast(1)
        val body =
            sourceFiles
                .take(6)
                .joinToString(separator = "\n\n") { file ->
                    buildString {
                        appendLine("### ${file.name} (${file.length()} bytes)")
                        append(readTailText(file, (budget / sourceFiles.take(6).size).coerceAtLeast(1_000)))
                    }
                }
                .takeLast(budget)
        return header + body
    }

    fun uploadDiagnostics(context: Context, attachment: File, summary: String): Boolean {
        if (!diagnoseInitialized.get()) {
            recordLog("debug", Tag, "diagnostics_upload diagnose=disabled")
            return false
        }
        flushDiagnoseLogs()
        val appContext = context.applicationContext
        val uploadPaths =
            buildList {
                val logDir = tdLogDir(appContext)
                if (logDir.exists()) add(logDir.absolutePath)
                if (attachment.isFile && attachment.length() > 0L) add(attachment.absolutePath)
            }
        if (uploadPaths.isEmpty()) {
            recordLog("warn", Tag, "diagnostics_upload diagnose=skipped reason=no_files")
            return false
        }
        val queryKey = "slock-${System.currentTimeMillis()}"
        return runCatching {
            val now = System.currentTimeMillis()
            val param =
                UploadParam(queryKey)
                    .setTimestamp(now - DiagnoseUploadTimeWindowMillis, now)
                    .setExtraPaths(uploadPaths)
                    .setExtraInfo("summary", summary.take(MaxBuglyValueLength))
                    .setExtraInfo("platform", "android")
                    .setListener(
                        object : UploadListener {
                            override fun onStart() {
                                recordLog("info", Tag, "diagnostics_upload started key=$queryKey files=${uploadPaths.size}")
                            }

                            override fun onProgress(percent: Int) {
                                if (percent >= 100) {
                                    recordLog("debug", Tag, "diagnostics_upload progress=$percent key=$queryKey")
                                }
                            }

                            override fun onSuccess() {
                                recordLog("info", Tag, "diagnostics_upload success key=$queryKey")
                            }

                            override fun onFailure(reason: Int) {
                                recordLog("warn", Tag, "diagnostics_upload failed key=$queryKey reason=$reason")
                            }
                        },
                        false
                    )
            TDDiag.upload(param)
            true
        }.onFailure { error ->
            recordLog("warn", Tag, "diagnostics_upload failed ${error.javaClass.simpleName}:${error.message.orEmpty()}")
        }.getOrDefault(false)
    }

    fun clearLocalDiagnosticsFiles(context: Context) {
        val appContext = context.applicationContext
        flushDiagnoseLogs()
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
        runCatching {
            fallbackLogDir(appContext).walkBottomUp()
                .filter { it.isFile }
                .forEach { it.delete() }
        }
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
            if (diagnoseInitialized.get()) {
                TDDiag.setUserId(cleanUserId.orEmpty())
            }
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

    private fun initializeDiagnose(context: Context, appId: String, appKey: String) {
        runCatching {
            val logDir = tdLogDir(context).apply { mkdirs() }
            val logConfig =
                TDLogConfig.Builder(context)
                    .setLogPath(logDir.absolutePath)
                    .setLogLevel(LogLevel.VERBOSE)
                    .setConsoleLog(BuildConfig.DEBUG)
                    .setMaxFileSize(TdLogMaxFileSize)
                    .setMaxAliveDay(TdLogMaxAliveDay)
                    .build()
            TDLog.initialize(context, logConfig)
            val diagConfig =
                TDDiagConfig.Builder()
                    .setAppId(appId)
                    .setAppKey(appKey)
                    .setLoggerAdapter(TDLog.getLogImpl())
                    .setAppVersion(BuildConfig.VERSION_NAME)
                    .setImportantLabels("android", "slock", if (BuildConfig.DEBUG) "debug" else "release")
                    .build()
            TDDiag.initialize(context, diagConfig)
            diagnoseInitialized.set(true)
            TDLog.i(Tag, "bugly diagnose initialized path=${logDir.absolutePath}")
        }.onFailure { error ->
            diagnoseInitialized.set(false)
            SlockDiagnosticsLogger.w(Tag, "diagnose_init_failed ${error.javaClass.simpleName}:${error.message.orEmpty()}")
        }
    }

    private fun writeFallbackLog(level: String, tag: String, message: String) {
        val context = appContext ?: return
        runCatching {
            synchronized(fallbackLogLock) {
                val dir = fallbackLogDir(context).apply { mkdirs() }
                val active = File(dir, FallbackLogFileName)
                rotateFallbackLogsIfNeeded(active)
                active.appendText(
                    "${diagnosticsTimestamp()} ${level.uppercase(Locale.US)} $tag $message\n",
                    StandardCharsets.UTF_8
                )
            }
        }
    }

    private fun rotateFallbackLogsIfNeeded(active: File) {
        if (!active.exists() || active.length() < FallbackLogMaxBytes) return
        val dir = active.parentFile ?: return
        for (index in FallbackLogMaxFiles - 1 downTo 1) {
            val source = File(dir, "$FallbackLogFileName.$index")
            val target = File(dir, "$FallbackLogFileName.${index + 1}")
            if (!source.exists()) continue
            if (index == FallbackLogMaxFiles - 1) {
                source.delete()
            } else {
                source.renameTo(target)
            }
        }
        active.renameTo(File(dir, "$FallbackLogFileName.1"))
    }

    private fun diagnosticsTimestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date())

    private fun flushDiagnoseLogs() {
        if (!diagnoseInitialized.get()) return
        runCatching { TDLog.flushLog() }
    }

    private fun diagnoseLogFiles(context: Context): List<File> {
        val fromAdapter =
            if (diagnoseInitialized.get()) {
                runCatching {
                    val now = System.currentTimeMillis()
                    TDLog.getLogImpl().getLogFiles(now - DiagnoseUploadTimeWindowMillis, now)
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
        val fromDir =
            tdLogDir(context)
                .walkTopDown()
                .filter { it.isFile && it.length() > 0L }
                .toList()
        return (fromAdapter + fromDir)
            .distinctBy { it.absolutePath }
            .sortedByDescending { it.lastModified() }
    }

    private fun fallbackLogFiles(context: Context): List<File> =
        fallbackLogDir(context)
            .walkTopDown()
            .filter { it.isFile && it.length() > 0L && it.name.startsWith(FallbackLogFileName) }
            .toList()
            .sortedByDescending { it.lastModified() }

    private fun readTailText(file: File, maxChars: Int): String =
        runCatching {
            if (file.shouldSkipTextPreview()) {
                return@runCatching "Binary TDLog file; included raw in diagnostics zip, text preview skipped."
            }
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
            "Unable to read TDLog file ${file.name}: ${error.javaClass.simpleName}"
        }

    private fun File.shouldSkipTextPreview(): Boolean {
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.US)
        if (extension in BinaryDiagnosticExtensions) return true
        val sampleSize = minOf(length(), BinaryPreviewSampleBytes.toLong()).toInt()
        if (sampleSize <= 0) return false
        return runCatching {
            inputStream().buffered().use { input ->
                val sample = ByteArray(sampleSize)
                val read = input.read(sample)
                if (read <= 0) {
                    false
                } else {
                    val suspiciousBytes =
                        sample.take(read).count { byte ->
                            val value = byte.toInt() and 0xff
                            value == 0 || value < 0x09 || value in 0x0e..0x1f
                        }
                    suspiciousBytes * 100 / read > BinaryPreviewSuspiciousPercent
                }
            }
        }.getOrDefault(false)
    }

    private fun pruneFeedbackArchives(feedbackDir: File) {
        feedbackDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(FeedbackFilePrefix) && it.name.endsWith(FeedbackZipExtension) }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(5)
            ?.forEach { it.delete() }
    }

    private fun tdLogDir(context: Context): File = File(context.filesDir, TdLogDirName)

    private fun fallbackLogDir(context: Context): File = File(context.filesDir, FallbackLogDirName)

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
