package build.raft.app.data

import build.raft.app.has
import build.raft.app.optBoolean
import build.raft.app.optString
import kotlinx.serialization.json.JsonObject

object RaftHostNetworkNotifyContract {
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

internal class RaftHostNetworkEvents(
    private val eventBus: RaftEventBus
) {
    fun addListener(onChanged: (HostNetworkStatusSignal) -> Unit): RealtimeSubscription =
        eventBus.observe(RaftHostNetworkNotifyContract.EventName, crossProcess = true) { data ->
            data?.toHostNetworkStatusSignal()?.let(onChanged)
        }

    /** Recovery work accepts only the host's explicit, internally-consistent online signal. */
    fun addAuthoritativeOnlineListener(onAvailable: () -> Unit): RealtimeSubscription =
        eventBus.observe(RaftHostNetworkNotifyContract.EventName, crossProcess = true) { data ->
            if (data?.isAuthoritativeOnlineSignal() == true) onAvailable()
        }

    fun removeListener(subscription: RealtimeSubscription) {
        subscription.close()
    }
}

private fun JsonObject.isAuthoritativeOnlineSignal(): Boolean =
    optString(RaftHostNetworkNotifyContract.KeyStatus) == RaftHostNetworkNotifyContract.StatusOnline &&
        has(RaftHostNetworkNotifyContract.KeyOnline) &&
        optBoolean(RaftHostNetworkNotifyContract.KeyOnline, false)

private fun JsonObject.toHostNetworkStatusSignal(): HostNetworkStatusSignal {
    val status = optString(RaftHostNetworkNotifyContract.KeyStatus)
    val online =
        status != RaftHostNetworkNotifyContract.StatusOffline &&
            optBoolean(RaftHostNetworkNotifyContract.KeyOnline, true)
    return HostNetworkStatusSignal(
        online = online,
        source = optString(RaftHostNetworkNotifyContract.KeySource).ifBlank { "unknown" }
    )
}
