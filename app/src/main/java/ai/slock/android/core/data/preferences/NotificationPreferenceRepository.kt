package ai.slock.android.core.data.preferences

interface NotificationPreferenceRepository {
    suspend fun getNotificationPreference(): NotificationPreferenceSnapshot
    suspend fun setNotificationEnabled(enabled: Boolean): NotificationPreferenceSnapshot
}

class PersistentNotificationPreferenceRepository(
    private val store: NotificationPreferenceStore
) : NotificationPreferenceRepository {
    override suspend fun getNotificationPreference(): NotificationPreferenceSnapshot =
        NotificationPreferenceSnapshot(enabled = store.readNotificationEnabled())

    override suspend fun setNotificationEnabled(enabled: Boolean): NotificationPreferenceSnapshot {
        store.saveNotificationEnabled(enabled)
        return NotificationPreferenceSnapshot(enabled = enabled)
    }
}
