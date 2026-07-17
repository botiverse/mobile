package build.raft.app.data

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

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
            eventData = JSONObject(),
            source = "shared-app-update"
        )
    }
}
