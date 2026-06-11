package ai.slock.android.core.data.diagnostics

import ai.slock.android.BuildConfig
import android.content.Context
import android.os.Build
import com.tencent.bugly.library.Bugly
import com.tencent.bugly.library.BuglyAppVersionMode
import com.tencent.bugly.library.BuglyBuilder
import com.tencent.bugly.library.BuglyLogLevel
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

object SlockAndroidBuglyDiagnostics {
    private const val Tag = "SlockAndroidBugly"
    private const val SceneTag = "SlockPerfScene"
    private const val SnapshotTag = "SlockKuiklyPerfSnapshot"
    private const val SnapshotEvent = "kuikly_perf_snapshot"
    private const val LooperMetricMonitor = "looper_metric"
    private const val LooperStackMonitor = "looper_stack"

    private val initialized = AtomicBoolean(false)
    private val initAttempted = AtomicBoolean(false)

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
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
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
}
