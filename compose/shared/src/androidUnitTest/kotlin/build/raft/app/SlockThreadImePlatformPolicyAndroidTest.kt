package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class SlockThreadImePlatformPolicyAndroidTest {
    @Test
    fun androidRestoresOrdinaryCorrectionAfterTheRootFix() {
        assertEquals(
            ThreadImeProgrammaticViewportPolicy.AllowWhileImeOpen,
            slockPlatformThreadImeProgrammaticViewportPolicy(),
        )
    }
}
