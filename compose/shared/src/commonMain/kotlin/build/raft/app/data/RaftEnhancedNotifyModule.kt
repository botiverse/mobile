package build.raft.app.data

import build.raft.app.parseRaftJsonObject
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class RaftEnhancedNotifyEvent(
    val eventName: String,
    val eventData: JsonObject,
    val crossProcess: Boolean = false,
    val source: String = "shared"
)

object RaftEnhancedNotifyContract {
    const val EventName = "slockEnhancedNotify"
    const val KeyEventName = "eventName"
    const val KeyData = "data"
    const val KeyCrossProcess = "crossProcess"
    const val KeySource = "source"
}

interface RaftEnhancedNotifyEventBus {
    fun observeNotifyEvents(listener: (RaftEnhancedNotifyEvent) -> Unit): RealtimeSubscription
}

interface RaftEventBus {
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

object RaftEnhancedNotifyCenter : RaftEnhancedNotifyEventBus, RaftEventBus {
    private val listenersLock = SynchronizedObject()
    private val enhancedListeners = mutableListOf<(RaftEnhancedNotifyEvent) -> Unit>()
    private val listenersByEvent = mutableMapOf<String, MutableList<(JsonObject?) -> Unit>>()
    private val pendingEvents = ArrayDeque<RaftEnhancedNotifyEvent>()
    private var deliveringEvents = false

    override fun observeNotifyEvents(listener: (RaftEnhancedNotifyEvent) -> Unit): RealtimeSubscription {
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
            RaftEnhancedNotifyEvent(
                eventName = eventName,
                eventData = eventData,
                crossProcess = crossProcess,
                source = source
            )
        )
    }

    private fun publish(event: RaftEnhancedNotifyEvent) {
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
                                if (event.eventName != RaftEnhancedNotifyContract.EventName) {
                                    listenersByEvent[RaftEnhancedNotifyContract.EventName]?.toList().orEmpty()
                                } else {
                                    emptyList()
                                },
                            enhanced = enhancedListeners.toList()
                        )
                )
            }
        }

    private data class EventDelivery(
        val event: RaftEnhancedNotifyEvent,
        val snapshot: ListenerSnapshot
    )

    private data class ListenerSnapshot(
        val event: List<(JsonObject?) -> Unit>,
        val envelope: List<(JsonObject?) -> Unit>,
        val enhanced: List<(RaftEnhancedNotifyEvent) -> Unit>
    )
}

internal fun RaftEnhancedNotifyEvent.toEnvelopeJson(): JsonObject =
    buildJsonObject {
        put(RaftEnhancedNotifyContract.KeyEventName, eventName)
        put(RaftEnhancedNotifyContract.KeyData, eventData)
        put(RaftEnhancedNotifyContract.KeyCrossProcess, crossProcess)
        put(RaftEnhancedNotifyContract.KeySource, source)
    }

internal class RaftPlatformDispatchingEventBus(
    private val dispatchToPlatform: (RaftEnhancedNotifyEvent) -> Unit
) : RaftEnhancedNotifyEventBus, RaftEventBus {
    override fun observeNotifyEvents(listener: (RaftEnhancedNotifyEvent) -> Unit): RealtimeSubscription =
        RaftEnhancedNotifyCenter.observeNotifyEvents(listener)

    override fun observe(
        eventName: String,
        crossProcess: Boolean,
        listener: (JsonObject?) -> Unit
    ): RealtimeSubscription =
        RaftEnhancedNotifyCenter.observe(eventName, crossProcess, listener)

    override fun post(
        eventName: String,
        eventData: JsonObject,
        crossProcess: Boolean,
        source: String
    ) {
        val event =
            RaftEnhancedNotifyEvent(
                eventName = eventName,
                eventData = eventData,
                crossProcess = crossProcess,
                source = source
            )
        RaftEnhancedNotifyCenter.post(eventName, eventData, crossProcess, source)
        dispatchToPlatform(event)
    }
}

internal class RaftEventBusRelay(
    initialEventBus: RaftEventBus
) : RaftEventBus {
    private var delegate: RaftEventBus = initialEventBus

    fun update(eventBus: RaftEventBus) {
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

internal object RaftEventBuses {
    private val appScopeEventBus: RaftEventBus by lazy {
        createRaftPlatformEventBus()
    }

    fun appScope(): RaftEventBus = appScopeEventBus

    fun inProcess(): RaftEventBus = RaftEnhancedNotifyCenter
}

/** Native hosts use this boundary to publish already-serialized events into shared exactly once. */
internal fun postRaftInProcessJsonEvent(
    eventName: String,
    eventDataJson: String,
    source: String,
): Boolean =
    runCatching {
        require(eventName.isNotBlank())
        require(eventDataJson.isNotBlank())
        RaftEventBuses.inProcess().post(
            eventName = eventName,
            eventData = parseRaftJsonObject(eventDataJson),
            crossProcess = true,
            source = source,
        )
    }.isSuccess
