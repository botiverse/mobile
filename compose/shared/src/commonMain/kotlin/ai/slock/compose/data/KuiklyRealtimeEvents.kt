package ai.slock.compose.data

import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager

private const val REALTIME_EVENT = "slockRealtimeEvent"

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
}
