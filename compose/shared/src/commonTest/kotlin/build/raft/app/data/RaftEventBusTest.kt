package build.raft.app.data

import build.raft.app.optBoolean
import build.raft.app.optInt
import build.raft.app.optJsonObject
import build.raft.app.optString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RaftEventBusTest {
    @Test
    fun appScopeEventBusDispatchesThroughPlatformActual() {
        val eventBus = RaftEventBuses.appScope()
        val eventName = "test.appScopeEventBusDispatchesThroughPlatformActual"
        val received = mutableListOf<String>()
        val subscription =
            eventBus.observe(eventName) { data ->
                received += data?.optString("value").orEmpty()
            }

        eventBus.post(eventName, buildJsonObject { put("value", "platform") })
        subscription.close()
        eventBus.post(eventName, buildJsonObject { put("value", "ignored") })

        assertEquals(listOf("platform"), received)
    }

    @Test
    fun inProcessEventBusDispatchesWithoutPager() {
        val eventBus = RaftEventBuses.inProcess()
        val eventName = "test.inProcessEventBusDispatchesWithoutPager"
        val received = mutableListOf<String>()
        val subscription =
            eventBus.observe(eventName) { data ->
                received += data?.optString("value").orEmpty()
            }

        eventBus.post(eventName, buildJsonObject { put("value", "first") })
        subscription.close()
        eventBus.post(eventName, buildJsonObject { put("value", "second") })

        assertEquals(listOf("first"), received)
    }

    @Test
    fun nativeJsonIngressDeliversValidPayloadExactlyOnceAndRejectsMalformedInput() {
        val eventName = "test.nativeJsonIngress"
        val received = mutableListOf<String>()
        val subscription =
            RaftEventBuses.inProcess().observe(eventName) { data ->
                received += data?.optString("value").orEmpty()
            }

        assertTrue(
            postRaftInProcessJsonEvent(
                eventName = eventName,
                eventDataJson = """{"value":"native"}""",
                source = "native-test",
            )
        )
        assertFalse(
            postRaftInProcessJsonEvent(
                eventName = eventName,
                eventDataJson = "not-json",
                source = "native-test",
            )
        )
        subscription.close()

        assertEquals(listOf("native"), received)
    }

    @Test
    fun listenerCanUnsubscribeDuringDeliveryWithoutSkippingSnapshotPeers() {
        val eventBus = RaftEventBuses.inProcess()
        val eventName = "test.listenerCanUnsubscribeDuringDeliveryWithoutSkippingSnapshotPeers"
        val received = mutableListOf<String>()
        lateinit var firstSubscription: RealtimeSubscription
        firstSubscription =
            eventBus.observe(eventName) {
                received += "first"
                firstSubscription.close()
            }
        val secondSubscription =
            eventBus.observe(eventName) {
                received += "second"
            }

        eventBus.post(eventName, buildJsonObject {})
        eventBus.post(eventName, buildJsonObject {})
        secondSubscription.close()

        assertEquals(listOf("first", "second", "second"), received)
    }

    @Test
    fun reentrantPostWaitsForCurrentEventSnapshotToFinish() {
        val eventBus = RaftEventBuses.inProcess()
        val eventName = "test.reentrantPostWaitsForCurrentEventSnapshotToFinish"
        val received = mutableListOf<String>()
        val firstSubscription =
            eventBus.observe(eventName) { data ->
                val value = data?.optInt("value") ?: return@observe
                received += "first:$value"
                if (value == 1) {
                    eventBus.post(eventName, buildJsonObject { put("value", 2) })
                }
            }
        val secondSubscription =
            eventBus.observe(eventName) { data ->
                received += "second:${data?.optInt("value") ?: return@observe}"
            }

        eventBus.post(eventName, buildJsonObject { put("value", 1) })
        firstSubscription.close()
        secondSubscription.close()

        assertEquals(listOf("first:1", "second:1", "first:2", "second:2"), received)
    }

    @Test
    fun inProcessEventBusPublishesEnhancedNotifyEnvelope() {
        val eventBus = RaftEventBuses.inProcess()
        val eventName = "test.inProcessEventBusPublishesEnhancedNotifyEnvelope"
        val received = mutableListOf<JsonObject>()
        val subscription =
            eventBus.observe(RaftEnhancedNotifyContract.EventName) { data ->
                data?.let(received::add)
            }

        eventBus.post(
            eventName,
            buildJsonObject { put("value", "payload") },
            crossProcess = true,
            source = "unit-test"
        )
        subscription.close()

        val envelope = received.single()
        assertEquals(eventName, envelope.optString(RaftEnhancedNotifyContract.KeyEventName))
        assertEquals("payload", envelope.optJsonObject(RaftEnhancedNotifyContract.KeyData)?.optString("value"))
        assertTrue(envelope.optBoolean(RaftEnhancedNotifyContract.KeyCrossProcess))
        assertEquals("unit-test", envelope.optString(RaftEnhancedNotifyContract.KeySource))
    }

    @Test
    fun platformDispatchingEventBusMirrorsLocalAndDispatchesPlatformEvent() {
        val dispatched = mutableListOf<RaftEnhancedNotifyEvent>()
        val eventBus = RaftPlatformDispatchingEventBus { event ->
            dispatched += event
        }
        val eventName = "test.platformDispatchingEventBusMirrorsLocalAndDispatchesPlatformEvent"
        val received = mutableListOf<String>()
        val subscription =
            eventBus.observe(eventName) { data ->
                received += data?.optString("value").orEmpty()
            }

        eventBus.post(
            eventName,
            buildJsonObject { put("value", "platform") },
            crossProcess = true,
            source = "platform-test"
        )
        subscription.close()

        assertEquals(listOf("platform"), received)
        val event = dispatched.single()
        assertEquals(eventName, event.eventName)
        assertEquals("platform", event.eventData.optString("value"))
        assertTrue(event.crossProcess)
        assertEquals("platform-test", event.source)
    }

    @Test
    fun authSessionEventsUseEventBusWithoutPager() {
        val eventBus = RaftEventBuses.inProcess()
        val received = mutableListOf<AuthSessionClearedSignal>()
        val events =
            RaftAuthSessionEvents(
                eventBus = eventBus,
                source = "unit-test-local"
            )
        val subscription = events.addSessionClearedListener(received::add)

        events.postSessionCleared(
            reason = "logout",
            previousSessionFingerprint = "session-1"
        )
        subscription.close()

        val signal = received.single()
        assertEquals("unit-test-local", signal.source)
        assertEquals("logout", signal.reason)
        assertEquals("session-1", signal.previousSessionFingerprint)
        assertTrue(events.isLocalSource(signal))
        assertFalse(events.isLocalSource(signal.copy(source = "other")))
    }

    @Test
    fun hostNetworkRecoveryRequiresExplicitConsistentOnlineSignal() {
        val eventBus = RaftPlatformDispatchingEventBus { }
        val events = RaftHostNetworkEvents(eventBus)
        var recoveries = 0
        val subscription = events.addAuthoritativeOnlineListener { recoveries += 1 }

        fun post(status: String?, online: Boolean?) {
            val data =
                buildJsonObject {
                    status?.let { put(RaftHostNetworkNotifyContract.KeyStatus, it) }
                    online?.let { put(RaftHostNetworkNotifyContract.KeyOnline, it) }
                }
            eventBus.post(RaftHostNetworkNotifyContract.EventName, data)
        }

        post(status = null, online = true)
        post(status = RaftHostNetworkNotifyContract.StatusOnline, online = null)
        post(status = RaftHostNetworkNotifyContract.StatusOnline, online = false)
        post(status = RaftHostNetworkNotifyContract.StatusOffline, online = true)
        post(status = RaftHostNetworkNotifyContract.StatusOffline, online = false)
        assertEquals(0, recoveries)

        post(status = RaftHostNetworkNotifyContract.StatusOnline, online = true)
        post(status = RaftHostNetworkNotifyContract.StatusOnline, online = true)
        assertEquals(2, recoveries)

        subscription.close()
        post(status = RaftHostNetworkNotifyContract.StatusOnline, online = true)
        assertEquals(2, recoveries)
    }
}
