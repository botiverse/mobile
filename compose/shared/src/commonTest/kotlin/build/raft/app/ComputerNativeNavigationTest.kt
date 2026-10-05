package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComputerNativeNavigationTest {
    private fun navigation() = ComputerNativeNavigation("https://raft.build", "raft")

    @Test fun restoresComputerBeforeOpeningAgentExactlyOnce() {
        val nav = navigation()
        assertNull(nav.accept("https://raft.build/s/raft/computer/machine?embed=1"))
        assertEquals(ComputerNavigationAction.RestoreComputer(1), nav.accept("https://raft.build/s/raft/agent/agent-1"))
        assertNull(nav.accept("https://raft.build/s/raft/agent/agent-1?embed=1"))
        assertEquals(ComputerNavigationAction.Open(ComputerNativeTarget("agent", "agent-1")), nav.accept("https://raft.build/s/raft/computer/machine?embed=1"))
        assertNull(nav.accept("https://raft.build/s/raft/computer/machine"))
    }

    @Test fun dmIdentityIsConversationAndNotMember() {
        val nav = navigation()
        nav.accept("https://raft.build/s/raft/computers")
        assertEquals(ComputerNavigationAction.RestoreComputer(1), nav.accept("https://raft.build/s/raft/dm/channel-1"))
        assertEquals(ComputerNavigationAction.Open(ComputerNativeTarget("dm", "channel-1")), nav.accept("https://raft.build/s/raft/computers"))
    }

    @Test fun rejectsUntrustedOriginServerAndMalformedPaths() {
        val nav = navigation()
        nav.accept("https://raft.build/s/raft/computers")
        listOf(
            "https://evil.example/s/raft/agent/a",
            "https://raft.build.evil.example/s/raft/agent/a",
            "https://user@raft.build/s/raft/agent/a",
            "https://raft.build/s/other/agent/a",
            "https://raft.build/s/raft/agent/a%2Fb",
            "https://raft.build/s/raft/agent/a/extra",
            "https://raft.build/s/raft/agent/",
        ).forEach { assertNull(nav.accept(it), it) }
    }

    @Test fun fallbackRestoresOriginalLocationIfBackLandsElsewhere() {
        val nav = navigation()
        assertNull(nav.accept("https://raft.build/s/raft/agent/a"))
        nav.accept("https://raft.build/s/raft/computer/a")
        nav.accept("https://raft.build/s/raft/agent/a")
        assertNull(nav.accept("https://raft.build/s/raft/computer/b"))
        assertEquals("https://raft.build/s/raft/computer/a", nav.fallbackUrl(1))
        assertEquals(ComputerNavigationAction.Open(ComputerNativeTarget("agent", "a")), nav.accept("https://raft.build/s/raft/computer/a"))
        assertNull(nav.fallbackUrl(1))
    }

    @Test fun replaceStateOrMissingHistoryHasBoundedFallbackAndOldTimerCannotRestoreNewNavigation() {
        val nav = navigation()
        nav.accept("https://raft.build/s/raft/computer/a")
        nav.accept("https://raft.build/s/raft/agent/a")
        assertEquals("https://raft.build/s/raft/computer/a", nav.fallbackUrl(1))
        nav.accept("https://raft.build/s/raft/computer/a")
        nav.accept("https://raft.build/s/raft/agent/b")
        assertNull(nav.fallbackUrl(1))
        assertEquals("https://raft.build/s/raft/computer/a", nav.fallbackUrl(2))
    }
}
