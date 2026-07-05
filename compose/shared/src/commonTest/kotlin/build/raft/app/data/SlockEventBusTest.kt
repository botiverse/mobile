package build.raft.app.data

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlockEventBusTest {
    @Test
    fun appScopeEventBusDispatchesThroughPlatformActual() {
        val eventBus = SlockEventBuses.appScope()
        val eventName = "test.appScopeEventBusDispatchesThroughPlatformActual"
        val received = mutableListOf<String>()
        val subscription =
            eventBus.observe(eventName) { data ->
                received += data?.optString("value").orEmpty()
            }

        eventBus.post(eventName, JSONObject().put("value", "platform"))
        subscription.close()
        eventBus.post(eventName, JSONObject().put("value", "ignored"))

        assertEquals(listOf("platform"), received)
    }

    @Test
    fun inProcessEventBusDispatchesWithoutPager() {
        val eventBus = SlockEventBuses.inProcess()
        val eventName = "test.inProcessEventBusDispatchesWithoutPager"
        val received = mutableListOf<String>()
        val subscription =
            eventBus.observe(eventName) { data ->
                received += data?.optString("value").orEmpty()
            }

        eventBus.post(eventName, JSONObject().put("value", "first"))
        subscription.close()
        eventBus.post(eventName, JSONObject().put("value", "second"))

        assertEquals(listOf("first"), received)
    }

    @Test
    fun inProcessEventBusPublishesEnhancedNotifyEnvelope() {
        val eventBus = SlockEventBuses.inProcess()
        val eventName = "test.inProcessEventBusPublishesEnhancedNotifyEnvelope"
        val received = mutableListOf<JSONObject>()
        val subscription =
            eventBus.observe(SlockEnhancedNotifyContract.EventName) { data ->
                data?.let(received::add)
            }

        eventBus.post(
            eventName,
            JSONObject().put("value", "payload"),
            crossProcess = true,
            source = "unit-test"
        )
        subscription.close()

        val envelope = received.single()
        assertEquals(eventName, envelope.optString(SlockEnhancedNotifyContract.KeyEventName))
        assertEquals("payload", envelope.optJSONObject(SlockEnhancedNotifyContract.KeyData)?.optString("value"))
        assertTrue(envelope.optBoolean(SlockEnhancedNotifyContract.KeyCrossProcess))
        assertEquals("unit-test", envelope.optString(SlockEnhancedNotifyContract.KeySource))
    }

    @Test
    fun platformDispatchingEventBusMirrorsLocalAndDispatchesPlatformEvent() {
        val dispatched = mutableListOf<SlockEnhancedNotifyEvent>()
        val eventBus = SlockPlatformDispatchingEventBus { event ->
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
            JSONObject().put("value", "platform"),
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
        val eventBus = SlockEventBuses.inProcess()
        val received = mutableListOf<AuthSessionClearedSignal>()
        val events =
            KuiklyAuthSessionEvents(
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
}
