package build.raft.app.data

import build.raft.app.parseSlockJsonObject
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class SlockEnhancedNotifyEvent(
    val eventName: String,
    val eventData: JsonObject,
    val crossProcess: Boolean = false,
    val source: String = "shared"
)

object SlockEnhancedNotifyContract {
    const val EventName = "slockEnhancedNotify"
    const val KeyEventName = "eventName"
    const val KeyData = "data"
    const val KeyCrossProcess = "crossProcess"
    const val KeySource = "source"
}

interface SlockEnhancedNotifyEventBus {
    fun observeNotifyEvents(listener: (SlockEnhancedNotifyEvent) -> Unit): RealtimeSubscription
}

interface SlockEventBus {
    fun observe(
        eventName: String,
        crossProcess: Boolean = false,
        listener: (JsonObject?) -> Unit
    ): RealtimeSubscription

    fun post(
        eventName: String,
        eventData: JsonObject,
        crossProcess: Boolean = false,
        source: String = "shared"
    )
}

object SlockEnhancedNotifyCenter : SlockEnhancedNotifyEventBus, SlockEventBus {
    private val listenersLock = SynchronizedObject()
    private val enhancedListeners = mutableListOf<(SlockEnhancedNotifyEvent) -> Unit>()
    private val listenersByEvent = mutableMapOf<String, MutableList<(JsonObject?) -> Unit>>()
    private val pendingEvents = ArrayDeque<SlockEnhancedNotifyEvent>()
    private var deliveringEvents = false

    override fun observeNotifyEvents(listener: (SlockEnhancedNotifyEvent) -> Unit): RealtimeSubscription {
        synchronized(listenersLock) {
            enhancedListeners += listener
        }
        return RealtimeSubscription {
            synchronized(listenersLock) {
                enhancedListeners -= listener
            }
        }
    }

    override fun observe(
        eventName: String,
        crossProcess: Boolean,
        listener: (JsonObject?) -> Unit
    ): RealtimeSubscription {
        synchronized(listenersLock) {
            listenersByEvent.getOrPut(eventName) { mutableListOf() } += listener
        }
        return RealtimeSubscription {
            synchronized(listenersLock) {
                listenersByEvent[eventName]?.let { listeners ->
                    listeners -= listener
                    if (listeners.isEmpty()) {
                        listenersByEvent.remove(eventName)
                    }
                }
            }
        }
    }

    override fun post(
        eventName: String,
        eventData: JsonObject,
        crossProcess: Boolean,
        source: String
    ) {
        publish(
            SlockEnhancedNotifyEvent(
                eventName = eventName,
                eventData = eventData,
                crossProcess = crossProcess,
                source = source
            )
        )
    }

    private fun publish(event: SlockEnhancedNotifyEvent) {
        val shouldDrain =
            synchronized(listenersLock) {
                pendingEvents.addLast(event)
                if (deliveringEvents) {
                    false
                } else {
                    deliveringEvents = true
                    true
                }
            }
        if (!shouldDrain) return
        try {
            while (true) {
                val delivery = nextDelivery() ?: return
                delivery.snapshot.event.forEach { listener ->
                    listener(delivery.event.eventData)
                }
                delivery.snapshot.envelope.forEach { listener ->
                    listener(delivery.event.toEnvelopeJson())
                }
                delivery.snapshot.enhanced.forEach { listener ->
                    listener(delivery.event)
                }
            }
        } catch (throwable: Throwable) {
            synchronized(listenersLock) {
                pendingEvents.clear()
                deliveringEvents = false
            }
            throw throwable
        }
    }

    private fun nextDelivery(): EventDelivery? =
        synchronized(listenersLock) {
            if (pendingEvents.isEmpty()) {
                deliveringEvents = false
                null
            } else {
                val event = pendingEvents.removeFirst()
                EventDelivery(
                    event = event,
                    snapshot =
                        ListenerSnapshot(
                            event = listenersByEvent[event.eventName]?.toList().orEmpty(),
                            envelope =
                                if (event.eventName != SlockEnhancedNotifyContract.EventName) {
                                    listenersByEvent[SlockEnhancedNotifyContract.EventName]?.toList().orEmpty()
                                } else {
                                    emptyList()
                                },
                            enhanced = enhancedListeners.toList()
                        )
                )
            }
        }

    private data class EventDelivery(
        val event: SlockEnhancedNotifyEvent,
        val snapshot: ListenerSnapshot
    )

    private data class ListenerSnapshot(
        val event: List<(JsonObject?) -> Unit>,
        val envelope: List<(JsonObject?) -> Unit>,
        val enhanced: List<(SlockEnhancedNotifyEvent) -> Unit>
    )
}

internal fun SlockEnhancedNotifyEvent.toEnvelopeJson(): JsonObject =
    buildJsonObject {
        put(SlockEnhancedNotifyContract.KeyEventName, eventName)
        put(SlockEnhancedNotifyContract.KeyData, eventData)
        put(SlockEnhancedNotifyContract.KeyCrossProcess, crossProcess)
        put(SlockEnhancedNotifyContract.KeySource, source)
    }

internal class SlockPlatformDispatchingEventBus(
    private val dispatchToPlatform: (SlockEnhancedNotifyEvent) -> Unit
) : SlockEnhancedNotifyEventBus, SlockEventBus {
    override fun observeNotifyEvents(listener: (SlockEnhancedNotifyEvent) -> Unit): RealtimeSubscription =
        SlockEnhancedNotifyCenter.observeNotifyEvents(listener)

    override fun observe(
        eventName: String,
        crossProcess: Boolean,
        listener: (JsonObject?) -> Unit
    ): RealtimeSubscription =
        SlockEnhancedNotifyCenter.observe(eventName, crossProcess, listener)

    override fun post(
        eventName: String,
        eventData: JsonObject,
        crossProcess: Boolean,
        source: String
    ) {
        val event =
            SlockEnhancedNotifyEvent(
                eventName = eventName,
                eventData = eventData,
                crossProcess = crossProcess,
                source = source
            )
        SlockEnhancedNotifyCenter.post(eventName, eventData, crossProcess, source)
        dispatchToPlatform(event)
    }
}

internal class SlockEventBusRelay(
    initialEventBus: SlockEventBus
) : SlockEventBus {
    private var delegate: SlockEventBus = initialEventBus

    fun update(eventBus: SlockEventBus) {
        delegate = eventBus
    }

    override fun observe(
        eventName: String,
        crossProcess: Boolean,
        listener: (JsonObject?) -> Unit
    ): RealtimeSubscription =
        delegate.observe(eventName, crossProcess, listener)

    override fun post(
        eventName: String,
        eventData: JsonObject,
        crossProcess: Boolean,
        source: String
    ) {
        delegate.post(eventName, eventData, crossProcess, source)
    }
}

internal object SlockEventBuses {
    private val appScopeEventBus: SlockEventBus by lazy {
        createSlockPlatformEventBus()
    }

    fun appScope(): SlockEventBus = appScopeEventBus

    fun inProcess(): SlockEventBus = SlockEnhancedNotifyCenter
}

/** Native hosts use this boundary to publish already-serialized events into shared exactly once. */
internal fun postSlockInProcessJsonEvent(
    eventName: String,
    eventDataJson: String,
    source: String,
): Boolean =
    runCatching {
        require(eventName.isNotBlank())
        require(eventDataJson.isNotBlank())
        SlockEventBuses.inProcess().post(
            eventName = eventName,
            eventData = parseSlockJsonObject(eventDataJson),
            crossProcess = true,
            source = source,
        )
    }.isSuccess
