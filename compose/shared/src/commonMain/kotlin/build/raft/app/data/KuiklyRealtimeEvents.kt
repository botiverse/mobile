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

    /**
     * task #354: the canonical AppShell/store ingest additionally observes
     * the Android raw-ingest topic; other platforms never publish on it, so
     * this is a no-op there. Page surfaces must not subscribe here.
     */
    fun addRawIngestListener(onEvent: (JSONObject) -> Unit): RealtimeSubscription =
        eventBus.observe(SlockRealtimeNotifyContract.RawIngestEventName, crossProcess = true) { data ->
            data?.let(onEvent)
        }

    fun removeListener(subscription: RealtimeSubscription) {
        subscription.close()
    }

    fun joinChannels(channelIds: List<String>, serverId: String? = null) {
        channelIds
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .forEach { joinChannel(it, serverId) }
    }

    fun joinChannel(channelId: String, serverId: String? = null) {
        val cleanChannelId = channelId.takeIf { it.isNotBlank() } ?: return
        eventBus.post(
            SlockRealtimeNotifyContract.RoomsEventName,
            JSONObject()
                .put(SlockRealtimeNotifyContract.KeyAction, SlockRealtimeNotifyContract.ActionJoinChannel)
                .put(SlockRealtimeNotifyContract.KeyChannelId, cleanChannelId)
                .also { payload -> serverId?.takeIf { it.isNotBlank() }?.let { payload.put(SlockRealtimeNotifyContract.KeyServerId, it) } },
            source = "realtime_rooms"
        )
    }

    fun rejoinChannels(channelIds: List<String>, serverId: String? = null) {
        channelIds
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .forEach { rejoinChannel(it, serverId) }
    }

    fun rejoinChannel(channelId: String, serverId: String? = null) {
        val cleanChannelId = channelId.takeIf { it.isNotBlank() } ?: return
        eventBus.post(
            SlockRealtimeNotifyContract.RoomsEventName,
            JSONObject()
                .put(SlockRealtimeNotifyContract.KeyAction, SlockRealtimeNotifyContract.ActionRejoinChannel)
                .put(SlockRealtimeNotifyContract.KeyChannelId, cleanChannelId)
                .also { payload -> serverId?.takeIf { it.isNotBlank() }?.let { payload.put(SlockRealtimeNotifyContract.KeyServerId, it) } },
            source = "foreground_reconcile"
        )
    }

    fun leaveChannels(channelIds: List<String>, serverId: String? = null) {
        channelIds
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .forEach { leaveChannel(it, serverId) }
    }

    fun leaveChannel(channelId: String, serverId: String? = null) {
        val cleanChannelId = channelId.takeIf { it.isNotBlank() } ?: return
        eventBus.post(
            SlockRealtimeNotifyContract.RoomsEventName,
            JSONObject()
                .put(SlockRealtimeNotifyContract.KeyAction, SlockRealtimeNotifyContract.ActionLeaveChannel)
                .put(SlockRealtimeNotifyContract.KeyChannelId, cleanChannelId)
                .also { payload -> serverId?.takeIf { it.isNotBlank() }?.let { payload.put(SlockRealtimeNotifyContract.KeyServerId, it) } },
            source = "realtime_rooms"
        )
    }
}
