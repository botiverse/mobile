package build.raft.app.base

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BridgeLogLevelTest {
    @Test
    fun parsesSupportedWireLevels() {
        assertEquals(BridgeLogLevel.Debug, BridgeLogLevel.fromWire("debug"))
        assertEquals(BridgeLogLevel.Info, BridgeLogLevel.fromWire("info"))
        assertEquals(BridgeLogLevel.Warn, BridgeLogLevel.fromWire("warn"))
        assertEquals(BridgeLogLevel.Error, BridgeLogLevel.fromWire("error"))
        assertEquals(BridgeLogLevel.Error, BridgeLogLevel.fromWire("fatal"))
    }

    @Test
    fun defaultsUnknownWireLevelToInfoForLegacyCallers() {
        assertEquals(BridgeLogLevel.Info, BridgeLogLevel.fromWire(null))
        assertEquals(BridgeLogLevel.Info, BridgeLogLevel.fromWire(""))
        assertEquals(BridgeLogLevel.Info, BridgeLogLevel.fromWire("verbose"))
    }

    @Test
    fun mirrorPolicyDependsOnlyOnLevel() {
        assertFalse(BridgeLogLevel.Debug.mirrorsToConsole())
        assertTrue(BridgeLogLevel.Info.mirrorsToConsole())
        assertTrue(BridgeLogLevel.Warn.mirrorsToConsole())
        assertTrue(BridgeLogLevel.Error.mirrorsToConsole())
    }

    @Test
    fun messageKeywordsDoNotAffectMirrorPolicy() {
        val noisyInfoMessage = "presence_batch read_home write_home error failed"
        val quietCriticalMessage = "auth blocker"

        assertTrue(BridgeLogLevel.Info.mirrorsToConsole(), noisyInfoMessage)
        assertTrue(BridgeLogLevel.Error.mirrorsToConsole(), quietCriticalMessage)
        assertFalse(BridgeLogLevel.Debug.mirrorsToConsole(), noisyInfoMessage)
    }
}
