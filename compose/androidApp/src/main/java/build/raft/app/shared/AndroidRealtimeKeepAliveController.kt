package build.raft.app.shared

import android.content.Context
import build.raft.app.core.data.auth.AndroidAppScopeMMKV
import build.raft.app.core.data.diagnostics.RaftAppLog
import build.raft.app.data.NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY
import build.raft.app.data.RaftLocalPreferenceStore
import com.tencent.mmkv.MMKV

internal object AndroidRealtimeKeepAliveController {
    private const val LOG_TAG = "SlockRealtimeKeepAlive"

    fun start(context: Context, source: String = "unknown") {
        val appContext = context.applicationContext
        if (!isForegroundKeepAliveEnabled(appContext)) {
            AndroidAppScopeRealtimeOwner.disableBackgroundKeepAlive("preference_disabled")
            RaftAppLog.d(
                LOG_TAG,
                "start_skipped_preference_disabled source=$source"
            )
            return
        }
        if (!AndroidRealtimeSessionAvailability.hasStoredRealtimeSession(appContext)) {
            AndroidAppScopeRealtimeOwner.disableBackgroundKeepAlive("missing_session")
            RaftAppLog.d(
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
            AndroidAppScopeMMKV.ensureInitialized(appContext)
            val store = MMKV.mmkvWithID(RaftLocalPreferenceStore.KuiklyNamespace)
            val value =
                store.decodeString(
                    localPreferenceKey(NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY),
                    null
                ) ?: store.decodeString(NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY, "false")
            value == "true"
        }.getOrElse { error ->
            RaftAppLog.w(
                LOG_TAG,
                "preference_read_failed message=${error.message.orEmpty()}"
            )
            false
        }

    internal fun localPreferenceKey(key: String): String =
        "${RaftLocalPreferenceStore.KuiklyNamespace}:$key"
}
