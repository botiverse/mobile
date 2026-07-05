package build.raft.app.data

import build.raft.app.data.realtime.SlockRealtimeNotifyContract
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

internal class KuiklyRealtimeEvents(
    private val eventBus: SlockEventBus
) {
    fun addListener(onEvent: (JSONObject) -> Unit): RealtimeSubscription =
        eventBus.observe(SlockRealtimeNotifyContract.EventName, crossProcess = true) { data ->
            data?.let(onEvent)
        }

    fun removeListener(subscription: RealtimeSubscription) {
        subscription.close()
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
        eventBus.post(
            SlockRealtimeNotifyContract.RoomsEventName,
            JSONObject()
                .put(SlockRealtimeNotifyContract.KeyAction, SlockRealtimeNotifyContract.ActionJoinChannel)
                .put(SlockRealtimeNotifyContract.KeyChannelId, cleanChannelId),
            source = "realtime_rooms"
        )
    }

    fun rejoinChannels(channelIds: List<String>) {
        channelIds
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .forEach(::rejoinChannel)
    }

    fun rejoinChannel(channelId: String) {
        val cleanChannelId = channelId.takeIf { it.isNotBlank() } ?: return
        eventBus.post(
            SlockRealtimeNotifyContract.RoomsEventName,
            JSONObject()
                .put(SlockRealtimeNotifyContract.KeyAction, SlockRealtimeNotifyContract.ActionRejoinChannel)
                .put(SlockRealtimeNotifyContract.KeyChannelId, cleanChannelId),
            source = "foreground_reconcile"
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
        eventBus.post(
            SlockRealtimeNotifyContract.RoomsEventName,
            JSONObject()
                .put(SlockRealtimeNotifyContract.KeyAction, SlockRealtimeNotifyContract.ActionLeaveChannel)
                .put(SlockRealtimeNotifyContract.KeyChannelId, cleanChannelId),
            source = "realtime_rooms"
        )
    }
}
