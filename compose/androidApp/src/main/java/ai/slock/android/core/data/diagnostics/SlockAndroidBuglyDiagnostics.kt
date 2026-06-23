package ai.slock.android.core.data.diagnostics

import ai.slock.android.BuildConfig
import android.content.Context
import android.os.Build
import com.tencent.bugly.library.Bugly
import com.tencent.bugly.library.BuglyAppVersionMode
import com.tencent.bugly.library.BuglyBuilder
import com.tencent.bugly.library.BuglyCustomLog
import com.tencent.bugly.library.BuglyConstants
import com.tencent.bugly.library.BuglyLogLevel
import com.tencent.bugly.launch.AppLaunchProxy
import java.io.File
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

    private val initialized = AtomicBoolean(false)
    private val initAttempted = AtomicBoolean(false)
    private val fullLaunchReported = AtomicBoolean(false)

    fun init(context: Context) {
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
                SlockDiagnosticsLogger.d(
                    Tag,
                    "initialized appVersion=${BuildConfig.VERSION_NAME} debug=${BuildConfig.DEBUG}"
                )
            } else {
                SlockDiagnosticsLogger.w(Tag, "init_returned_false")
            }
        }.onFailure { error ->
            SlockDiagnosticsLogger.w(Tag, "init_failed ${error.javaClass.simpleName}:${error.message.orEmpty()}")
        }
    }

    fun isInitialized(): Boolean = initialized.get()

    fun recordLog(level: String, tag: String, message: String) {
        if (!initialized.get()) return
        val cleanTag = tag.buglyValueOrNull() ?: return
        val cleanMessage = SanitizedDiagnosticsLogBuffer.sanitize(message).buglyValueOrNull() ?: return
        runCatching {
            when (level.lowercase()) {
                "verbose" -> BuglyCustomLog.v(cleanTag, cleanMessage)
                "debug" -> BuglyCustomLog.d(cleanTag, cleanMessage)
                "info" -> BuglyCustomLog.i(cleanTag, cleanMessage)
                "warn" -> BuglyCustomLog.w(cleanTag, cleanMessage)
                "error" -> BuglyCustomLog.e(cleanTag, cleanMessage)
                else -> BuglyCustomLog.i(cleanTag, cleanMessage)
            }
        }
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
}
