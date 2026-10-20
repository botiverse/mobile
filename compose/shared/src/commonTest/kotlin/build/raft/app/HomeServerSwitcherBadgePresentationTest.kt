package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeServerSwitcherBadgePresentationTest {
    @Test
    fun exactZeroSuppressesBroaderFallbackWhileAbsentUsesBroaderNumber() {
        val knownZero =
            RaftServerSwitcherItem(
                id = "server-1",
                broaderUnreadCount = 21,
                activityUnreadCount = 0,
            )

        assertEquals(
            HomeServerSwitcherBadgePresentation(kind = HomeServerSwitcherBadgeKind.None),
            homeServerSwitcherBadgePresentation(knownZero),
        )
        assertEquals(
            HomeServerSwitcherBadgePresentation(
                kind = HomeServerSwitcherBadgeKind.BroaderUnreadNumber,
                label = "21",
            ),
            homeServerSwitcherBadgePresentation(knownZero.copy(activityUnreadCount = null)),
        )
    }

    @Test
    fun exactAndBroaderFallbackNumbersUseTheSameCap() {
        val exact =
            homeServerSwitcherBadgePresentation(
                RaftServerSwitcherItem(
                    id = "server-exact",
                    broaderUnreadCount = 7,
                    activityUnreadCount = 128,
                )
            )
        val fallback =
            homeServerSwitcherBadgePresentation(
                RaftServerSwitcherItem(
                    id = "server-fallback",
                    broaderUnreadCount = 150,
                    activityUnreadCount = null,
                )
            )

        assertEquals(HomeServerSwitcherBadgeKind.ActivityNumber, exact.kind)
        assertEquals(HomeServerSwitcherBadgeKind.BroaderUnreadNumber, fallback.kind)
        assertEquals("99+", exact.label)
        assertEquals(exact.label, fallback.label)
    }
}
