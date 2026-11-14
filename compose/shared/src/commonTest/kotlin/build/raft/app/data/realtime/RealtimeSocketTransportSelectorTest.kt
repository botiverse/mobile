package build.raft.app.data.realtime

import build.raft.app.data.RealtimeSubscription
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** realtime_kmp_client_v1 picks the socket implementation per connection; listeners follow the active one. */
class RealtimeSocketTransportSelectorTest {
    private class Recording(val name: String) : RealtimeSocketTransport {
        val calls = mutableListOf<String>()
        val handlers = mutableMapOf<String, MutableList<(RealtimeSocketConnectionContext, Any?) -> Unit>>()
        var context: RealtimeSocketConnectionContext? = null

        override fun <T> linearize(block: () -> T): T = block()

        override fun connect(auth: Map<String, String>, context: RealtimeSocketConnectionContext) {
            calls += "connect:${auth["serverId"]}"
            this.context = context
        }

        override fun disconnect() {
            calls += "disconnect"
            context = null
        }

        override fun emit(eventName: String, payload: Map<String, Any>) {
            calls += "emit:$eventName"
        }

        override fun emitWithAck(
            eventName: String,
            onAck: (context: RealtimeSocketConnectionContext, payload: Any?) -> Unit,
        ): Boolean {
            calls += "ack:$eventName"
            return true
        }

        override fun on(
            eventName: String,
            listener: (context: RealtimeSocketConnectionContext, payload: Any?) -> Unit,
        ): RealtimeSubscription {
            handlers.getOrPut(eventName) { mutableListOf() } += listener
            return RealtimeSubscription { handlers[eventName]?.remove(listener) }
        }

        fun receive(eventName: String, payload: Any? = null) {
            val current = context ?: return
            handlers[eventName]?.toList()?.forEach { it(current, payload) }
        }
    }

    private class Harness {
        var enabled = false
        val legacy = Recording("legacy")
        var kmpCreated = 0
        val kmp = Recording("kmp")
        val logs = mutableListOf<String>()
        val selector =
            RealtimeSocketTransportSelector(
                linearizer = ReentrantRealtimeTransportLinearizer(),
                legacy = legacy,
                kmpFactory = { kmpCreated += 1; kmp },
                decision = {
                    RaftRealtimeKmpClientFlag.Decision(enabled, RaftRealtimeKmpClientFlag.Source.Server)
                },
                logger = { logs += it },
            )

        fun context(serverId: String) =
            RealtimeSocketConnectionContext(serverId = ServerId(serverId), witness = UnboundRealtimeConnectionWitnessToken)
    }

    @Test
    fun offUsesTheLegacySocketAndNeverBuildsTheCommonClient() {
        val harness = Harness()
        val received = mutableListOf<String>()
        harness.selector.on("message:new") { _, payload -> received += "$payload" }
        harness.selector.connect(mapOf("serverId" to "server-1"), harness.context("server-1"))
        harness.selector.emit("join:channel", mapOf("channelId" to "c"))
        assertTrue(harness.selector.emitWithAck("sync:horizon") { _, _ -> })
        harness.legacy.receive("message:new", "m1")

        assertEquals(listOf("connect:server-1", "emit:join:channel", "ack:sync:horizon"), harness.legacy.calls)
        assertEquals(0, harness.kmpCreated)
        assertEquals(listOf("m1"), received)
        assertEquals("legacy", harness.selector.activeImplementation)
        assertTrue(harness.logs.single().startsWith("realtime_kmp_transport selected=legacy source=server serverId=server-1"))
    }

    @Test
    fun theFlagIsReadPerConnectionAndSwitchingDisconnectsTheOtherSocket() {
        val harness = Harness()
        val received = mutableListOf<String>()
        harness.selector.on("message:new") { _, payload -> received += "$payload" }
        harness.selector.connect(mapOf("serverId" to "server-1"), harness.context("server-1"))

        harness.enabled = true
        harness.selector.emit("presence:activity")
        assertEquals("a flag change never moves a live socket", "emit:presence:activity", harness.legacy.calls.last())

        harness.selector.connect(mapOf("serverId" to "server-2"), harness.context("server-2"))
        assertEquals("disconnect", harness.legacy.calls.last())
        assertEquals(listOf("connect:server-2"), harness.kmp.calls)
        assertEquals("kmp", harness.selector.activeImplementation)
        harness.kmp.receive("message:new", "m2")
        harness.legacy.receive("message:new", "stale")
        assertEquals("listeners registered before the common client existed reach it", listOf("m2"), received)
        assertTrue(harness.logs.last().contains("selected=kmp") && harness.logs.last().contains("switched=true"))

        harness.enabled = false
        harness.selector.connect(mapOf("serverId" to "server-1"), harness.context("server-1"))
        assertEquals("rollback on the next connection", "disconnect", harness.kmp.calls.last())
        assertEquals("connect:server-1", harness.legacy.calls.last())
        assertEquals(1, harness.kmpCreated)
    }

    @Test
    fun aClosedSubscriptionLeavesBothSockets() {
        val harness = Harness()
        harness.enabled = true
        val subscription = harness.selector.on("heartbeat") { _, _ -> }
        harness.selector.connect(mapOf("serverId" to "server-1"), harness.context("server-1"))
        assertTrue(harness.kmp.handlers["heartbeat"].orEmpty().isNotEmpty())
        subscription.close()
        assertTrue(harness.kmp.handlers["heartbeat"].orEmpty().isEmpty())
        assertTrue(harness.legacy.handlers["heartbeat"].orEmpty().isEmpty())
    }
}
