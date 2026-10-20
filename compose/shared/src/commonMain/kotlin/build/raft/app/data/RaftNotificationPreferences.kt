package build.raft.app.data

import build.raft.app.optBoolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put


private const val NOTIFICATION_PREFERENCE_EVENT = "slock.notificationPreferenceChanged"
private const val KEY_ENABLED = "enabled"
private const val KEY_FOREGROUND_KEEP_ALIVE_ENABLED = "foregroundKeepAliveEnabled"
private const val KEY_OFFLINE_COMPUTER_ALERTS_ENABLED = "offlineComputerAlertsEnabled"
private const val KEY_SOURCE = "source"

class RaftNotificationPreferenceEvents(
    private val eventBus: RaftEventBus,
    private val source: String = "shared"
) {
    fun addPreferenceListener(onChanged: (NotificationPreferenceSnapshot) -> Unit): RealtimeSubscription =
        eventBus.observe(NOTIFICATION_PREFERENCE_EVENT) { data ->
            val enabled = data?.optBoolean(KEY_ENABLED) ?: return@observe
            val foregroundKeepAliveEnabled = data.optBoolean(KEY_FOREGROUND_KEEP_ALIVE_ENABLED)
            val offlineComputerAlertsEnabled = data.optBoolean(KEY_OFFLINE_COMPUTER_ALERTS_ENABLED)
            onChanged(
                NotificationPreferenceSnapshot(
                    enabled = enabled,
                    foregroundKeepAliveEnabled = foregroundKeepAliveEnabled,
                    offlineComputerAlertsEnabled = offlineComputerAlertsEnabled,
                )
            )
        }

    fun removePreferenceListener(subscription: RealtimeSubscription) {
        subscription.close()
    }

    fun postPreferenceChanged(snapshot: NotificationPreferenceSnapshot) {
        eventBus.post(
            NOTIFICATION_PREFERENCE_EVENT,
            buildJsonObject {
                put(KEY_ENABLED, snapshot.enabled)
                put(KEY_FOREGROUND_KEEP_ALIVE_ENABLED, snapshot.foregroundKeepAliveEnabled)
                put(KEY_OFFLINE_COMPUTER_ALERTS_ENABLED, snapshot.offlineComputerAlertsEnabled)
                put(KEY_SOURCE, source)
            }
        )
    }
}

fun raftNotificationPreferenceRepository(
    events: RaftNotificationPreferenceEvents
): NotificationPreferenceRepository =
    NotifyingNotificationPreferenceRepository(
        delegate =
            PersistentNotificationPreferenceRepository(
                KeyValueNotificationPreferenceStore(
                    raftSharedPreferenceStore()
                )
            ),
        events = events
    )

private class NotifyingNotificationPreferenceRepository(
    private val delegate: NotificationPreferenceRepository,
    private val events: RaftNotificationPreferenceEvents
) : NotificationPreferenceRepository {
    override suspend fun getNotificationPreference(): NotificationPreferenceSnapshot =
        delegate.getNotificationPreference()

    override suspend fun setNotificationEnabled(enabled: Boolean): NotificationPreferenceSnapshot {
        val snapshot = delegate.setNotificationEnabled(enabled)
        events.postPreferenceChanged(snapshot)
        return snapshot
    }

    override suspend fun setForegroundKeepAliveEnabled(enabled: Boolean): NotificationPreferenceSnapshot {
        val snapshot = delegate.setForegroundKeepAliveEnabled(enabled)
        events.postPreferenceChanged(snapshot)
        return snapshot
    }

    override suspend fun setOfflineComputerAlertsEnabled(enabled: Boolean): NotificationPreferenceSnapshot {
        val snapshot = delegate.setOfflineComputerAlertsEnabled(enabled)
        events.postPreferenceChanged(snapshot)
        return snapshot
    }
}
