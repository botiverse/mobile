package build.raft.app.data

import com.tencent.tmm.knoi.annotation.ServiceConsumer
import com.tencent.tmm.knoi.service.KNOI

private const val SlockOhosEnhancedNotifySinkService = "slockOhosEnhancedNotifySink"

@ServiceConsumer(name = SlockOhosEnhancedNotifySinkService)
interface SlockOhosEnhancedNotifySink {
    fun onEvent(eventName: String, eventDataJson: String, envelopeJson: String)
}

private object OhosSlockPlatformEventDispatcher {
    fun dispatch(event: SlockEnhancedNotifyEvent) {
        runCatching {
            KNOI.get<SlockOhosEnhancedNotifySink>(SlockOhosEnhancedNotifySinkService)
                .onEvent(
                    event.eventName,
                    event.eventData.toString(),
                    event.toEnvelopeJson().toString()
                )
        }.onFailure { error ->
            println("[SlockOhosEnhancedNotify] sink_missing message=${error.message.orEmpty()}")
        }
    }
}

internal actual fun createSlockPlatformEventBus(): SlockEventBus =
    SlockPlatformDispatchingEventBus(OhosSlockPlatformEventDispatcher::dispatch)
