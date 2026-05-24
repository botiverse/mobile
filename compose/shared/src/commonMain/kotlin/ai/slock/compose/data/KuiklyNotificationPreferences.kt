package ai.slock.compose.data

import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager

private const val NOTIFICATION_PREFERENCE_EVENT = "slock.notificationPreferenceChanged"
private const val KEY_ENABLED = "enabled"
private const val KEY_SOURCE = "source"

class KuiklyNotificationPreferenceEvents(
    private val pager: Pager,
    private val source: String = "shared"
) {
    private val module: NotifyModule
        get() = pager.acquireModule(NotifyModule.MODULE_NAME)

    fun addPreferenceListener(onChanged: (NotificationPreferenceSnapshot) -> Unit): CallbackRef =
        module.addNotify(NOTIFICATION_PREFERENCE_EVENT) { data ->
            val enabled = data?.optBoolean(KEY_ENABLED) ?: return@addNotify
            onChanged(NotificationPreferenceSnapshot(enabled = enabled))
        }

    fun removePreferenceListener(callbackRef: CallbackRef) {
        module.removeNotify(NOTIFICATION_PREFERENCE_EVENT, callbackRef)
    }

    fun postPreferenceChanged(snapshot: NotificationPreferenceSnapshot) {
        module.postNotify(
            NOTIFICATION_PREFERENCE_EVENT,
            JSONObject()
                .put(KEY_ENABLED, snapshot.enabled)
                .put(KEY_SOURCE, source)
        )
    }
}

fun kuiklyNotificationPreferenceRepository(
    pager: Pager,
    events: KuiklyNotificationPreferenceEvents
): NotificationPreferenceRepository =
    NotifyingNotificationPreferenceRepository(
        delegate =
            PersistentNotificationPreferenceRepository(
                KeyValueNotificationPreferenceStore(
                    kuiklySharedPreferenceStore(pager)
                )
            ),
        events = events
    )

private class NotifyingNotificationPreferenceRepository(
    private val delegate: NotificationPreferenceRepository,
    private val events: KuiklyNotificationPreferenceEvents
) : NotificationPreferenceRepository {
    override suspend fun getNotificationPreference(): NotificationPreferenceSnapshot =
        delegate.getNotificationPreference()

    override suspend fun setNotificationEnabled(enabled: Boolean): NotificationPreferenceSnapshot {
        val snapshot = delegate.setNotificationEnabled(enabled)
        events.postPreferenceChanged(snapshot)
        return snapshot
    }
}
