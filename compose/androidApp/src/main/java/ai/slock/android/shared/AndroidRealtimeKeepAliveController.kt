package ai.slock.android.shared

import android.content.Context
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
        AndroidAppScopeRealtimeOwner.install(appContext)
        AndroidAppScopeRealtimeOwner.enableBackgroundKeepAlive(
            context = appContext,
            source = source
        )
    }

    fun stop(context: Context, reason: String = "unknown") {
        AndroidAppScopeRealtimeOwner.disableBackgroundKeepAlive(reason)
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
