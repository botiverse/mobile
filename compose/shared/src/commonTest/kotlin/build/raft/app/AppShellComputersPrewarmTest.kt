package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppShellComputersPrewarmTest {
    @Test
    fun computersPrewarmWaitsForSessionServerAndHomeContent() {
        assertNull(
            appShellComputersPrewarmServerId(
                readyServerId = "server-1",
                sessionReady = false,
                homeContentReady = true
            )
        )
        assertNull(
            appShellComputersPrewarmServerId(
                readyServerId = "server-1",
                sessionReady = true,
                homeContentReady = false
            )
        )
        assertNull(
            appShellComputersPrewarmServerId(
                readyServerId = " ",
                sessionReady = true,
                homeContentReady = true
            )
        )

        assertEquals(
            "server-1",
            appShellComputersPrewarmServerId(
                readyServerId = " server-1 ",
                sessionReady = true,
                homeContentReady = true
            )
        )
    }
}
