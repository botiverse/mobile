package build.raft.app.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put


const val SLOCK_APP_UPDATE_EVENT = "slock.appUpdateStatus"

class KuiklyAppUpdateEvents(
    private val eventBus: SlockEventBus
) {
    fun addStatusListener(onChanged: () -> Unit): RealtimeSubscription =
        eventBus.observe(SLOCK_APP_UPDATE_EVENT) {
            onChanged()
        }

    fun removeStatusListener(subscription: RealtimeSubscription) {
        subscription.close()
    }

    fun notifyStatusChanged() {
        eventBus.post(
            eventName = SLOCK_APP_UPDATE_EVENT,
            eventData = buildJsonObject {},
            source = "shared-app-update"
        )
    }
}
