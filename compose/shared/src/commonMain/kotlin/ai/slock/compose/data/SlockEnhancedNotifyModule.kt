package ai.slock.compose.data

import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager

data class SlockEnhancedNotifyEvent(
    val eventName: String,
    val eventData: JSONObject,
    val crossProcess: Boolean = false,
    val source: String = "shared"
)

interface SlockEnhancedNotifyEventBus {
    fun observeNotifyEvents(listener: (SlockEnhancedNotifyEvent) -> Unit): RealtimeSubscription
}

object SlockEnhancedNotifyCenter : SlockEnhancedNotifyEventBus {
    private val listeners = mutableListOf<(SlockEnhancedNotifyEvent) -> Unit>()

    override fun observeNotifyEvents(listener: (SlockEnhancedNotifyEvent) -> Unit): RealtimeSubscription {
        listeners += listener
        return RealtimeSubscription {
            listeners -= listener
        }
    }

    fun publish(event: SlockEnhancedNotifyEvent) {
        listeners.toList().forEach { listener ->
            listener(event)
        }
    }
}

internal class SlockEnhancedNotifyModule(
    private val pager: Pager
) {
    private val module: NotifyModule
        get() = pager.acquireModule(NotifyModule.MODULE_NAME)

    fun addNotify(
        eventName: String,
        crossProcess: Boolean = false,
        callback: (JSONObject?) -> Unit
    ): CallbackRef =
        module.addNotify(eventName, crossProcess = crossProcess, cb = callback)

    fun removeNotify(eventName: String, callbackRef: CallbackRef) {
        module.removeNotify(eventName, callbackRef)
    }

    fun postNotify(
        eventName: String,
        eventData: JSONObject,
        crossProcess: Boolean = false,
        source: String = "shared"
    ) {
        SlockEnhancedNotifyCenter.publish(
            SlockEnhancedNotifyEvent(
                eventName = eventName,
                eventData = eventData,
                crossProcess = crossProcess,
                source = source
            )
        )
        module.postNotify(eventName, eventData, crossProcess = crossProcess)
    }
}
