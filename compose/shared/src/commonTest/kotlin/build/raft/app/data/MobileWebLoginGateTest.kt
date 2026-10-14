package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MobileWebLoginGateTest {
    @Test fun defaultsOnUntilTheServerExplicitlyTurnsItOff() {
        val store = InMemorySlockKeyValueStore()
        val gate = MobileWebLoginGate(store)
        assertTrue(gate.isEnabled())
        gate.record(enabled = false, reason = "default")
        assertFalse(gate.isEnabled())
        assertFalse(MobileWebLoginGate(store).isEnabled(), "decision survives a new process")
        gate.record(enabled = true, reason = "rule")
        assertTrue(gate.isEnabled())
    }

    @Test fun missingFlagIsNoDecisionAndClearsAnOldOff() {
        val gate = MobileWebLoginGate(InMemorySlockKeyValueStore())
        gate.record(enabled = false, reason = "rule")
        gate.record(enabled = false, reason = "missing_flag")
        assertTrue(gate.isEnabled())
    }
}
