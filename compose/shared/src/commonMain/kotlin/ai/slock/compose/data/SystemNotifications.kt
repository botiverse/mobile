package ai.slock.compose.data

data class NotificationPreferenceSnapshot(
    val enabled: Boolean
)

interface NotificationPreferenceStore {
    fun readNotificationEnabledSync(): Boolean
    suspend fun readNotificationEnabled(): Boolean
    suspend fun saveNotificationEnabled(enabled: Boolean)
}

class KeyValueNotificationPreferenceStore(
    private val store: SharedPreferenceStore
) : NotificationPreferenceStore {
    override fun readNotificationEnabledSync(): Boolean =
        store.getBoolean(KEY_NOTIFICATION_ENABLED, false)

    override suspend fun readNotificationEnabled(): Boolean =
        readNotificationEnabledSync()

    override suspend fun saveNotificationEnabled(enabled: Boolean) {
        store.putBoolean(KEY_NOTIFICATION_ENABLED, enabled)
    }

    private companion object {
        const val KEY_NOTIFICATION_ENABLED = "notifications:enabled"
    }
}

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

data class SystemNotificationActionResult(
    val status: SystemNotificationActionStatus,
    val code: String = status.name.lowercase(),
    val message: String = ""
) {
    val ok: Boolean
        get() = status == SystemNotificationActionStatus.Ok || status == SystemNotificationActionStatus.Requested
}

enum class SystemNotificationActionStatus {
    Ok,
    Requested,
    PermissionDenied,
    NotificationsDisabled,
    ChannelBlocked,
    Unsupported,
    Error
}

fun shouldShowSystemInboxNotification(
    messageAuthorId: String?,
    currentMemberId: String?,
    isVisibleThread: Boolean,
    isAppInForeground: Boolean = false
): Boolean {
    if (isAppInForeground) return false
    if (!messageAuthorId.isNullOrBlank() && messageAuthorId == currentMemberId) return false
    if (isVisibleThread) return false
    return true
}
