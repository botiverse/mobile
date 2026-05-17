package ai.slock.android.core.data.preferences

import android.content.Context
import ai.slock.compose.data.SlockKeyValueStore
import ai.slock.compose.data.getBoolean
import ai.slock.compose.data.putBoolean

data class NotificationPreferenceSnapshot(
    val enabled: Boolean
)

interface NotificationPreferenceStore {
    fun readNotificationEnabledSync(): Boolean
    suspend fun readNotificationEnabled(): Boolean
    suspend fun saveNotificationEnabled(enabled: Boolean)
}

class SharedPreferencesNotificationPreferenceStore(
    context: Context
) : NotificationPreferenceStore {
    private val store: SlockKeyValueStore =
        AndroidSharedPreferencesKeyValueStore(context, PREFERENCES_NAME)

    override fun readNotificationEnabledSync(): Boolean =
        store.getBoolean(KEY_NOTIFICATION_ENABLED, false)

    override suspend fun readNotificationEnabled(): Boolean =
        readNotificationEnabledSync()

    override suspend fun saveNotificationEnabled(enabled: Boolean) {
        store.putBoolean(KEY_NOTIFICATION_ENABLED, enabled)
    }

    companion object {
        private const val PREFERENCES_NAME = "slock_notification_preferences"
        private const val KEY_NOTIFICATION_ENABLED = "notification_enabled"
    }
}
