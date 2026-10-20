package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class RaftThreadImePlatformPolicyAndroidTest {
    @Test
    fun androidRestoresOrdinaryCorrectionAfterTheRootFix() {
        assertEquals(
            ThreadImeProgrammaticViewportPolicy.AllowWhileImeOpen,
            raftPlatformThreadImeProgrammaticViewportPolicy(),
        )
    }
}
