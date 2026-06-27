package ai.slock.compose.data

import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager

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
    private val pager: Pager
) {
    private val module: NotifyModule
        get() = pager.acquireModule(NotifyModule.MODULE_NAME)

    fun addListener(onChanged: (HostNetworkStatusSignal) -> Unit): CallbackRef =
        module.addNotify(SlockHostNetworkNotifyContract.EventName, crossProcess = true) { data ->
            data?.toHostNetworkStatusSignal()?.let(onChanged)
        }

    fun removeListener(callbackRef: CallbackRef) {
        module.removeNotify(SlockHostNetworkNotifyContract.EventName, callbackRef)
    }
}

private fun JSONObject.toHostNetworkStatusSignal(): HostNetworkStatusSignal {
    val status = optString(SlockHostNetworkNotifyContract.KeyStatus)
    val online =
        status != SlockHostNetworkNotifyContract.StatusOffline &&
            optBoolean(SlockHostNetworkNotifyContract.KeyOnline, true)
    return HostNetworkStatusSignal(
        online = online,
        source = optString(SlockHostNetworkNotifyContract.KeySource).ifBlank { "unknown" }
    )
}
