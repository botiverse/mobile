package build.raft.app.data

import build.raft.app.has
import build.raft.app.optBoolean
import build.raft.app.optString
import kotlinx.serialization.json.JsonObject

object SlockHostNetworkNotifyContract {
    const val EventName = "slock.hostNetworkStatus"
    const val KeyOnline = "online"
    const val KeyStatus = "status"
    const val KeySource = "source"
    const val StatusOnline = "online"
    const val StatusOffline = "offline"
}

internal data class HostNetworkStatusSignal(
    val online: Boolean,
    val source: String
)

internal class KuiklyHostNetworkEvents(
    private val eventBus: SlockEventBus
) {
    fun addListener(onChanged: (HostNetworkStatusSignal) -> Unit): RealtimeSubscription =
        eventBus.observe(SlockHostNetworkNotifyContract.EventName, crossProcess = true) { data ->
            data?.toHostNetworkStatusSignal()?.let(onChanged)
        }

    /** Recovery work accepts only the host's explicit, internally-consistent online signal. */
    fun addAuthoritativeOnlineListener(onAvailable: () -> Unit): RealtimeSubscription =
        eventBus.observe(SlockHostNetworkNotifyContract.EventName, crossProcess = true) { data ->
            if (data?.isAuthoritativeOnlineSignal() == true) onAvailable()
        }

    fun removeListener(subscription: RealtimeSubscription) {
        subscription.close()
    }
}

private fun JsonObject.isAuthoritativeOnlineSignal(): Boolean =
    optString(SlockHostNetworkNotifyContract.KeyStatus) == SlockHostNetworkNotifyContract.StatusOnline &&
        has(SlockHostNetworkNotifyContract.KeyOnline) &&
        optBoolean(SlockHostNetworkNotifyContract.KeyOnline, false)

private fun JsonObject.toHostNetworkStatusSignal(): HostNetworkStatusSignal {
    val status = optString(SlockHostNetworkNotifyContract.KeyStatus)
    val online =
        status != SlockHostNetworkNotifyContract.StatusOffline &&
            optBoolean(SlockHostNetworkNotifyContract.KeyOnline, true)
    return HostNetworkStatusSignal(
        online = online,
        source = optString(SlockHostNetworkNotifyContract.KeySource).ifBlank { "unknown" }
    )
}
