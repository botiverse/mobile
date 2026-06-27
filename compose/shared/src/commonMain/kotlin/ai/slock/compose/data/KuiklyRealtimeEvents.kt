package ai.slock.compose.data

import ai.slock.compose.data.realtime.SlockRealtimeNotifyContract
import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager

internal class KuiklyRealtimeEvents(
    private val pager: Pager
) {
    private val module: SlockEnhancedNotifyModule
        get() = SlockEnhancedNotifyModule(pager)

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
                .put(SlockRealtimeNotifyContract.KeyChannelId, cleanChannelId),
            source = "realtime_rooms"
        )
    }

    fun leaveChannels(channelIds: List<String>) {
        channelIds
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .forEach(::leaveChannel)
    }

    fun leaveChannel(channelId: String) {
        val cleanChannelId = channelId.takeIf { it.isNotBlank() } ?: return
        module.postNotify(
            SlockRealtimeNotifyContract.RoomsEventName,
            JSONObject()
                .put(SlockRealtimeNotifyContract.KeyAction, SlockRealtimeNotifyContract.ActionLeaveChannel)
                .put(SlockRealtimeNotifyContract.KeyChannelId, cleanChannelId),
            source = "realtime_rooms"
        )
    }
}
