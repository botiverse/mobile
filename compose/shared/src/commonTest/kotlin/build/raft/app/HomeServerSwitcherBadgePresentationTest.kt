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
}
