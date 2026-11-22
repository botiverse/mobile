package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
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
}
