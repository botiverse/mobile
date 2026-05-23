package ai.slock.android.core.data.preferences

import android.content.Context
import ai.slock.compose.data.KeyValueNotificationPreferenceStore
import ai.slock.compose.data.NotificationPreferenceStore

class SharedPreferencesNotificationPreferenceStore(
    context: Context
) : NotificationPreferenceStore by KeyValueNotificationPreferenceStore(
    AndroidSharedPreferencesKeyValueStore(context, PREFERENCES_NAME)
) {
    companion object {
        private const val PREFERENCES_NAME = "slock_notification_preferences"
    }
}
