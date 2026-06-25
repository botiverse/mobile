package ai.slock.android.shared

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger
import ai.slock.compose.data.NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY
import ai.slock.compose.data.SlockLocalPreferenceStore
import com.tencent.mmkv.MMKV

internal object AndroidRealtimeKeepAliveController {
    private const val LOG_TAG = "SlockRealtimeKeepAlive"

    fun start(context: Context, source: String = "unknown") {
        val appContext = context.applicationContext
        if (!isForegroundKeepAliveEnabled(appContext)) {
            AndroidAppScopeRealtimeOwner.disableBackgroundKeepAlive("preference_disabled")
            SlockDiagnosticsLogger.d(
                LOG_TAG,
                "start_skipped_preference_disabled source=$source"
            )
            return
        }
        if (!AndroidRealtimeSessionAvailability.hasStoredRealtimeSession(appContext)) {
            AndroidAppScopeRealtimeOwner.disableBackgroundKeepAlive("missing_session")
            SlockDiagnosticsLogger.d(
                LOG_TAG,
                "start_skipped_no_session source=$source"
            )
            return
        }
        val intent =
            Intent(appContext, SlockRealtimeKeepAliveService::class.java)
                .setAction(SlockRealtimeKeepAliveService.ACTION_START)
                .putExtra(SlockRealtimeKeepAliveService.EXTRA_SOURCE, source)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(appContext, intent)
            } else {
                appContext.startService(intent)
            }
        }.onFailure { error ->
            SlockDiagnosticsLogger.w(
                LOG_TAG,
                "start_failed source=$source message=${error.message.orEmpty()}"
            )
        }
    }

    fun stop(context: Context, reason: String = "unknown") {
        val appContext = context.applicationContext
        val intent =
            Intent(appContext, SlockRealtimeKeepAliveService::class.java)
                .setAction(SlockRealtimeKeepAliveService.ACTION_STOP)
                .putExtra(SlockRealtimeKeepAliveService.EXTRA_REASON, reason)
        runCatching {
            appContext.startService(intent)
        }.onFailure { error ->
            SlockDiagnosticsLogger.w(
                LOG_TAG,
                "stop_failed reason=$reason message=${error.message.orEmpty()}"
            )
        }
    }

    fun isForegroundKeepAliveEnabled(context: Context): Boolean =
        runCatching {
            val appContext = context.applicationContext
            SlockKuiklyMMKVModule.ensureInitialized(appContext)
            val store = MMKV.mmkvWithID(SlockLocalPreferenceStore.KuiklyNamespace)
            val value =
                store.decodeString(
                    localPreferenceKey(NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY),
                    null
                ) ?: store.decodeString(NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY, "false")
            value == "true"
        }.getOrElse { error ->
            SlockDiagnosticsLogger.w(
                LOG_TAG,
                "preference_read_failed message=${error.message.orEmpty()}"
            )
            false
        }

    internal fun localPreferenceKey(key: String): String =
        "${SlockLocalPreferenceStore.KuiklyNamespace}:$key"
}
