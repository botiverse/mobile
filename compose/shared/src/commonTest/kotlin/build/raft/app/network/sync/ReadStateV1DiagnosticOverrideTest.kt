package build.raft.app.network.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ReadStateV1DiagnosticOverrideTest {
    private var now: Long = 1_000L
    private val controller = ReadStateV1DiagnosticOverrideController { now }
    private val owner = ReadStateV1ProductionOwner("https://api.example", "account-a", "server-a")

    @Test
    fun `release entitlement fails closed`() {
        controller.observe(owner, "channel-a")

        val result = controller.setMode(ReadStateV1DiagnosticOverrideMode.ForceOn)

        assertIs<ReadStateV1DiagnosticOverrideMutationResult.Rejected>(result)
        assertEquals("read_state_diagnostic_override_not_entitled", result.code)
        assertFalse(controller.decide(false, owner, "channel-a").finalEffective)
    }
}
