package build.raft.app.data

import build.raft.app.optString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Android app-scope bus is in-process only: events reach shared observers synchronously
 * and nothing is serialized for a platform fan-out (the Kuikly event hub has no listener on CMP).
 */
class SlockAndroidAppScopeEventBusTest {
    @Test
    fun appScopeEventsAreDeliveredInProcessOnly() {
        val eventBus = createSlockPlatformEventBus()
        assertSame(SlockEnhancedNotifyCenter, eventBus)

        val eventName = "test.androidAppScopeEventsAreDeliveredInProcessOnly"
        val received = mutableListOf<String>()
        val subscription = eventBus.observe(eventName) { data -> received += data?.optString("value").orEmpty() }
        eventBus.post(eventName, buildJsonObject { put("value", "batch") }, crossProcess = false, source = "unit-test")
        subscription.close()

        assertEquals(listOf("batch"), received)
    }
}
