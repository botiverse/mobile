package build.raft.app.data.realtime

import build.raft.app.data.SlockEventBuses
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlockOhosRealtimeIngressTest {
    @Test
    fun nativeRealtimePayloadEntersSharedBusExactlyOnce() {
        val received = mutableListOf<Pair<String, String>>()
        val subscription =
            SlockEventBuses.inProcess().observe(SlockRealtimeNotifyContract.EventName) { data ->
                data?.let {
                    received +=
                        it.optString(SlockRealtimeNotifyContract.KeyEventName) to
                            it.optString(SlockRealtimeNotifyContract.KeyRawPayload)
                }
            }

        val published =
            slockRealtimeKnoiPublishEvent(
                """{"eventName":"message:new","rawPayload":"{\\"seq\\":42}"}"""
            )
        subscription.close()

        assertTrue(published)
        assertEquals(listOf("message:new" to "{\"seq\":42}"), received)
    }

    @Test
    fun malformedNativePayloadIsRejectedWithoutDelivery() {
        var deliveries = 0
        val subscription =
            SlockEventBuses.inProcess().observe(SlockRealtimeNotifyContract.EventName) {
                deliveries += 1
            }

        assertFalse(slockRealtimeKnoiPublishEvent(""))
        assertFalse(slockRealtimeKnoiPublishEvent("not-json"))
        subscription.close()

        assertEquals(0, deliveries)
    }
}
