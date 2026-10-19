package build.raft.app.data

import build.raft.app.data.realtime.GeneratedRealtimeContract
import build.raft.app.optString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.Foundation.NSNotificationCenter

class SlockPlatformEventBusIosTest {
    @Test
    fun nativeRealtimeNotificationFlowsIntoSharedEventBus() {
        val eventBus = createSlockPlatformEventBus()
        var received: JsonObject? = null
        val subscription =
            eventBus.observe(GeneratedRealtimeContract.Notify.EventName, crossProcess = true) {
                received = it
            }

        NSNotificationCenter.defaultCenter.postNotificationName(
            aName = GeneratedRealtimeContract.Notify.EventName,
            `object` = null,
            userInfo =
                mapOf(
                    GeneratedRealtimeContract.PayloadKey.ServerId to "server-1",
                    GeneratedRealtimeContract.PayloadKey.EventName to GeneratedRealtimeContract.SocketEvent.MessageNew,
                    GeneratedRealtimeContract.PayloadKey.RawPayload to "{\"id\":\"message-1\"}"
                )
        )

        assertEquals("server-1", received?.optString(GeneratedRealtimeContract.PayloadKey.ServerId))
        assertEquals(
            GeneratedRealtimeContract.SocketEvent.MessageNew,
            received?.optString(GeneratedRealtimeContract.PayloadKey.EventName)
        )
        assertEquals(
            "{\"id\":\"message-1\"}",
            received?.optString(GeneratedRealtimeContract.PayloadKey.RawPayload)
        )
        subscription.close()
    }

    @Test
    fun sharedRealtimePostIsDeliveredOnlyOnce() {
        val eventBus = createSlockPlatformEventBus()
        var deliveryCount = 0
        val subscription =
            eventBus.observe(GeneratedRealtimeContract.Notify.EventName, crossProcess = true) {
                deliveryCount += 1
            }

        eventBus.post(
            eventName = GeneratedRealtimeContract.Notify.EventName,
            eventData =
                buildJsonObject {
                    put(GeneratedRealtimeContract.PayloadKey.ServerId, "server-1")
                    put(GeneratedRealtimeContract.PayloadKey.EventName, GeneratedRealtimeContract.SocketEvent.Heartbeat)
                },
            crossProcess = true,
            source = "test"
        )

        assertEquals(1, deliveryCount)
        subscription.close()
    }

    @Test
    fun nativeSystemNotificationOpenFlowsIntoSharedEventBus() {
        val eventBus = createSlockPlatformEventBus()
        var received: JsonObject? = null
        val subscription =
            eventBus.observe(SlockSystemNotificationContract.OpenEventName) {
                received = it
            }

        NSNotificationCenter.defaultCenter.postNotificationName(
            aName = SlockSystemNotificationContract.OpenEventName,
            `object` = null,
            userInfo =
                mapOf(
                    "slockNotificationServerId" to "server-1",
                    "slockNotificationThreadId" to "thread-1",
                    "slockNotificationMessageId" to "message-1"
                )
        )

        assertEquals("server-1", received?.optString("slockNotificationServerId"))
        assertEquals("thread-1", received?.optString("slockNotificationThreadId"))
        assertEquals("message-1", received?.optString("slockNotificationMessageId"))
        subscription.close()
    }
}
