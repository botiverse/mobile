package build.raft.app.base

import build.raft.app.flushRaftPlatformLogsForTest
import build.raft.app.installRaftPlatformLogSink
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * task #25 regression lock: BridgeModule.log must NEVER cross the Kuikly
 * bridge. Diagnostics lambdas fire from worker coroutines; a bridge call off
 * the context thread trips the fork's release-live KR_ASSERT_CONTEXT_HTREAD
 * and aborts the app (the TestFlight SIGABRTs). log() therefore routes through
 * the bridge-free RaftPlatformLog channel — these tests pin that contract.
 */
class BridgeModuleLogTest {

    private val captured = mutableListOf<Triple<String, String, String>>()

    private fun installCapture() {
        installRaftPlatformLogSink { level, tag, message ->
            captured += Triple(level, tag, message)
        }
    }

    @AfterTest
    fun uninstall() {
        installRaftPlatformLogSink(null)
    }

    @Test
    fun logNeverTouchesTheBridge() = runBlocking {
        installCapture()
        // A bare module: no pager injected, no native side, no context thread.
        // Any callNativeMethod would blow up (and on-device would be the exact
        // TestFlight crash) — log() must stay safe from ANY thread/state.
        val module = BridgeModule()

        module.log("plain info")
        module.log(BridgeLogLevel.Warn, "warned")
        module.logError("boom")
        module.logDebug("dbg")
        flushRaftPlatformLogsForTest()

        assertEquals(
            listOf(
                Triple("info", BridgeModule.BRIDGE_LOG_TAG, "plain info"),
                Triple("warn", BridgeModule.BRIDGE_LOG_TAG, "warned"),
                Triple("error", BridgeModule.BRIDGE_LOG_TAG, "boom"),
                Triple("debug", BridgeModule.BRIDGE_LOG_TAG, "dbg")
            ),
            captured
        )
    }

    @Test
    fun logLevelsMapOneToOne() = runBlocking {
        installCapture()
        val module = BridgeModule()

        BridgeLogLevel.entries.forEach { level ->
            module.log(level, "m-${level.wireValue}")
        }
        flushRaftPlatformLogsForTest()

        assertEquals(
            BridgeLogLevel.entries.map { it.wireValue },
            captured.map { it.first }
        )
        assertEquals(
            BridgeLogLevel.entries.map { "m-${it.wireValue}" },
            captured.map { it.third }
        )
    }
}
