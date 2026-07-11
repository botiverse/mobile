package build.raft.app.base

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlockBridgeLogPumpTest {

    @AfterTest
    fun drainLeftovers() {
        SlockBridgeLogPump.drainForTest()
    }

    @Test
    fun enqueuePreservesEmissionOrderAndLevels() {
        SlockBridgeLogPump.drainForTest()
        SlockBridgeLogPump.enqueue(BridgeLogLevel.Info, "first")
        SlockBridgeLogPump.enqueue(BridgeLogLevel.Error, "second")
        SlockBridgeLogPump.enqueue(BridgeLogLevel.Debug, "third")

        val drained = SlockBridgeLogPump.drainForTest()

        assertEquals(
            listOf(
                SlockBridgeLogPump.Entry(BridgeLogLevel.Info, "first"),
                SlockBridgeLogPump.Entry(BridgeLogLevel.Error, "second"),
                SlockBridgeLogPump.Entry(BridgeLogLevel.Debug, "third")
            ),
            drained
        )
    }

    @Test
    fun drainEmptiesTheQueue() {
        SlockBridgeLogPump.drainForTest()
        SlockBridgeLogPump.enqueue(BridgeLogLevel.Info, "only")
        SlockBridgeLogPump.drainForTest()

        assertEquals(emptyList(), SlockBridgeLogPump.drainForTest())
    }

    @Test
    fun overflowDropsOldestAndKeepsNewest() {
        SlockBridgeLogPump.drainForTest()
        repeat(600) { index ->
            SlockBridgeLogPump.enqueue(BridgeLogLevel.Info, "m$index")
        }

        val drained = SlockBridgeLogPump.drainForTest()

        assertEquals(512, drained.size)
        assertEquals("m599", drained.last().content, "newest entry must survive")
        assertEquals("m88", drained.first().content, "oldest beyond cap must be dropped")
        assertTrue(drained.zipWithNext().all { (a, b) ->
            a.content.removePrefix("m").toInt() + 1 == b.content.removePrefix("m").toInt()
        }, "order must stay contiguous after the drop")
    }

    @Test
    fun bridgeModuleLogRoutesThroughThePump() {
        SlockBridgeLogPump.drainForTest()
        // BridgeModule.log must never touch the bridge directly (that is the
        // TestFlight crash); it only enqueues, so calling it without any pager
        // or native side must be safe from any thread.
        val module = BridgeModule()
        module.log("info-line")
        module.logWarn("warn-line")

        val drained = SlockBridgeLogPump.drainForTest()

        assertEquals(
            listOf(
                SlockBridgeLogPump.Entry(BridgeLogLevel.Info, "info-line"),
                SlockBridgeLogPump.Entry(BridgeLogLevel.Warn, "warn-line")
            ),
            drained
        )
    }
}
