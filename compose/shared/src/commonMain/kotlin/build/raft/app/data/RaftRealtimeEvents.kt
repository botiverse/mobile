package build.raft.app.data

import build.raft.app.data.realtime.RaftRealtimeNotifyContract
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class RaftRealtimeEvents(
    private val eventBus: RaftEventBus
) {
    fun addListener(onEvent: (JsonObject) -> Unit): RealtimeSubscription =
        eventBus.observe(RaftRealtimeNotifyContract.EventName, crossProcess = true) { data ->
            data?.let(onEvent)
        }

    /**
     * task #354: the canonical AppShell/store ingest additionally observes
     * the Android raw-ingest topic; other platforms never publish on it, so
     * this is a no-op there. Page surfaces must not subscribe here.
     */
    fun addRawIngestListener(onEvent: (JsonObject) -> Unit): RealtimeSubscription =
        eventBus.observe(RaftRealtimeNotifyContract.RawIngestEventName, crossProcess = true) { data ->
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
            RaftRealtimeNotifyContract.RoomsEventName,
            buildJsonObject {
                put(RaftRealtimeNotifyContract.KeyAction, RaftRealtimeNotifyContract.ActionJoinChannel)
                put(RaftRealtimeNotifyContract.KeyChannelId, cleanChannelId)
                serverId?.takeIf { it.isNotBlank() }?.let { put(RaftRealtimeNotifyContract.KeyServerId, it) }
            },
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
            RaftRealtimeNotifyContract.RoomsEventName,
            buildJsonObject {
                put(RaftRealtimeNotifyContract.KeyAction, RaftRealtimeNotifyContract.ActionRejoinChannel)
                put(RaftRealtimeNotifyContract.KeyChannelId, cleanChannelId)
                serverId?.takeIf { it.isNotBlank() }?.let { put(RaftRealtimeNotifyContract.KeyServerId, it) }
            },
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
            RaftRealtimeNotifyContract.RoomsEventName,
            buildJsonObject {
                put(RaftRealtimeNotifyContract.KeyAction, RaftRealtimeNotifyContract.ActionLeaveChannel)
                put(RaftRealtimeNotifyContract.KeyChannelId, cleanChannelId)
                serverId?.takeIf { it.isNotBlank() }?.let { put(RaftRealtimeNotifyContract.KeyServerId, it) }
            },
            source = "realtime_rooms"
        )
    }

    fun applyRoomTransitions(transitions: Iterable<RealtimeRoomTransition>) {
        transitions.forEach { transition ->
            when (transition.action) {
                RealtimeRoomTransitionAction.Join ->
                    joinChannel(transition.roomId, transition.serverId)
                RealtimeRoomTransitionAction.Leave ->
                    leaveChannel(transition.roomId, transition.serverId)
            }
        }
    }
}
