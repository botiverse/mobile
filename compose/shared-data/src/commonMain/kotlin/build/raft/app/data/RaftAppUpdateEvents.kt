package build.raft.app.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put


const val RAFT_APP_UPDATE_EVENT = "slock.appUpdateStatus"

class RaftAppUpdateEvents(
    private val eventBus: RaftEventBus
) {
    fun addStatusListener(onChanged: () -> Unit): RealtimeSubscription =
        eventBus.observe(RAFT_APP_UPDATE_EVENT) {
            onChanged()
        }

    fun removeStatusListener(subscription: RealtimeSubscription) {
        subscription.close()
    }

    fun notifyStatusChanged() {
        eventBus.post(
            eventName = RAFT_APP_UPDATE_EVENT,
            eventData = buildJsonObject {},
            source = "shared-app-update"
        )
    }
}
