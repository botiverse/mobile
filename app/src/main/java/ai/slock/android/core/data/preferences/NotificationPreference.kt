package ai.slock.android.core.data.preferences

import android.content.Context

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
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun readNotificationEnabledSync(): Boolean =
        preferences.getBoolean(KEY_NOTIFICATION_ENABLED, false)

    override suspend fun readNotificationEnabled(): Boolean =
        readNotificationEnabledSync()

    override suspend fun saveNotificationEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_NOTIFICATION_ENABLED, enabled)
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "slock_notification_preferences"
        private const val KEY_NOTIFICATION_ENABLED = "notification_enabled"
    }
}
