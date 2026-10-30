package build.raft.app.shared

import android.content.Context
import build.raft.app.core.data.auth.AndroidAppScopeMMKV
import build.raft.app.core.data.diagnostics.RaftAppLog
import build.raft.app.data.NOTIFICATION_ENABLED_PREFERENCE_KEY
import build.raft.app.data.RaftLocalPreferenceStore
import com.tencent.mmkv.MMKV

/**
 * The device's "receive notifications here" switch (`notifications:enabled`), read the same way by
 * every Android path that posts: FCM ([build.raft.app.push.RaftFirebaseMessagingService]) and the
 * local realtime path. The server also stops alerting a device whose registration reports
 * `alertsEnabled: false` (slock docs/contracts/notifications.md §3.1); this is the second guard for
 * the pushes already on their way, or a registration the server has not seen yet.
 */
internal object RaftNotificationAlertsPreference {
    private const val LOG_TAG = "SlockNotificationAlerts"

    fun isEnabled(context: Context): Boolean =
        runCatching {
            AndroidAppScopeMMKV.ensureInitialized(context.applicationContext)
            val store = MMKV.mmkvWithID(RaftLocalPreferenceStore.KuiklyNamespace)
            val value =
                store.decodeString(
                    AndroidRealtimeKeepAliveController.localPreferenceKey(NOTIFICATION_ENABLED_PREFERENCE_KEY),
                    null
                ) ?: store.decodeString(NOTIFICATION_ENABLED_PREFERENCE_KEY, "true")
            value == "true"
        }.getOrElse { error ->
            RaftAppLog.w(LOG_TAG, "notification_alerts_preference_failed message=${error.message.orEmpty()}")
            // Unreadable: keep notifying. A switched-off device is also stopped server-side.
            true
        }
}
