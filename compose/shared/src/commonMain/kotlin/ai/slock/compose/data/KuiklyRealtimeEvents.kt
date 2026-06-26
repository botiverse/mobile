package ai.slock.compose.data

import ai.slock.compose.data.realtime.SlockRealtimeNotifyContract
import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager

internal class KuiklyRealtimeEvents(
    private val pager: Pager
) {
    private val module: NotifyModule
        get() = pager.acquireModule(NotifyModule.MODULE_NAME)

    fun addListener(onEvent: (JSONObject) -> Unit): CallbackRef =
        module.addNotify(SlockRealtimeNotifyContract.EventName, crossProcess = true) { data ->
            data?.let(onEvent)
        }

    fun removeListener(callbackRef: CallbackRef) {
        module.removeNotify(SlockRealtimeNotifyContract.EventName, callbackRef)
    }

    fun joinChannels(channelIds: List<String>) {
        channelIds
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .forEach(::joinChannel)
    }

    fun joinChannel(channelId: String) {
        val cleanChannelId = channelId.takeIf { it.isNotBlank() } ?: return
        module.postNotify(
            SlockRealtimeNotifyContract.RoomsEventName,
            JSONObject()
                .put(SlockRealtimeNotifyContract.KeyAction, SlockRealtimeNotifyContract.ActionJoinChannel)
                .put(SlockRealtimeNotifyContract.KeyChannelId, cleanChannelId)
        )
    }
}
