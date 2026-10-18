package build.raft.app.shared

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlockAndroidAvatarStartupGateTest {
    @Test
    fun secondStartReturnsImmediatelyWhileRecoveryIsScheduled() {
        val gate = SlockAndroidAvatarStartupGate()
        assertTrue(gate.trySchedule())
        assertFalse(gate.trySchedule())
        gate.reset()
        assertTrue(gate.trySchedule())
    }
}
