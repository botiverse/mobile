package build.raft.app.data

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
}
