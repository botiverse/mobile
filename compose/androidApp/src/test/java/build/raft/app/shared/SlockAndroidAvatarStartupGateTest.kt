package build.raft.app.shared

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
