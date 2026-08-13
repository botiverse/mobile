package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.ServerSetupProjection
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerSetupAdmissionTest {
    private val identity = ServerSetupAdmissionIdentity("env", "account", "server", "generation-1")

    @Test
    fun onlyOneSwitchCanClaimTheActiveGeneration() {
        val reducer = ServerSetupAdmissionReducer()
        reducer.begin(identity)

        assertTrue(reducer.claimSwitch(identity))
        assertFalse(reducer.claimSwitch(identity))
        assertFalse(reducer.acceptsWake(identity))
        assertFalse(reducer.claimSwitch(identity.copy(generation = "generation-2")))
        reducer.begin(identity.copy(generation = "generation-2"))
        assertTrue(reducer.claimSwitch(identity.copy(generation = "generation-2")))
    }

    @Test
    fun onlyBlocksChatControlsAdmission() {
        val reducer = ServerSetupAdmissionReducer()
        reducer.begin(identity)

        val phaseNullBlocked = reducer.resolve(identity, DataResult.Content(projection(blocksChat = true, phase = null)))
        assertIs<ServerSetupAdmissionState.Blocked>(phaseNullBlocked)

        val complete = reducer.resolve(identity, DataResult.Content(projection(blocksChat = false, phase = "complete")))
        assertIs<ServerSetupAdmissionState.Admitted>(complete)
    }

    @Test
    fun staleReadAndWakeCannotReleaseCurrentIdentity() {
        val reducer = ServerSetupAdmissionReducer()
        reducer.begin(identity)
        val stale = identity.copy(serverId = "old-server", generation = "")

        assertNull(reducer.resolve(stale, DataResult.Content(projection(blocksChat = false))))
        assertFalse(reducer.acceptsWake(stale))
        assertTrue(reducer.acceptsWake(identity))
    }

    @Test
    fun generationUsesTheFrozenAsciiAlphabetAndBounds() {
        assertTrue("A-z_0.:-".isValidServerSetupGeneration())
        assertFalse("代".isValidServerSetupGeneration())
        assertFalse("bad/value".isValidServerSetupGeneration())
        assertFalse("a".repeat(129).isValidServerSetupGeneration())
    }

    private fun projection(blocksChat: Boolean, phase: String? = "in_progress") =
        ServerSetupProjection(
            surface = if (blocksChat) "computer_runtime" else "complete",
            phase = phase,
            currentStep = null,
            blocksChat = blocksChat,
            allowedExits = emptySet(),
            gateReason = null,
        )
}
