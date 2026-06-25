package ai.slock.compose.data

import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager

private const val REALTIME_EVENT = "slockRealtimeEvent"
private const val REALTIME_ROOMS_EVENT = "slockRealtimeRooms"
private const val ACTION_JOIN_CHANNEL = "joinChannel"
private const val KEY_ACTION = "action"
private const val KEY_CHANNEL_ID = "channelId"

internal class KuiklyRealtimeEvents(
    private val pager: Pager
) {
    private val module: NotifyModule
        get() = pager.acquireModule(NotifyModule.MODULE_NAME)

    fun addListener(onEvent: (JSONObject) -> Unit): CallbackRef =
        module.addNotify(REALTIME_EVENT, crossProcess = true) { data ->
            data?.let(onEvent)
        }

    fun removeListener(callbackRef: CallbackRef) {
        module.removeNotify(REALTIME_EVENT, callbackRef)
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
            REALTIME_ROOMS_EVENT,
            JSONObject()
                .put(KEY_ACTION, ACTION_JOIN_CHANNEL)
                .put(KEY_CHANNEL_ID, cleanChannelId)
        )
    }
}
