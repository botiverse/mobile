package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsHomeSearchFilterOrderTest {
    @Test
    fun fullSearchMatchesWebFromThenScopeOrder() {
        assertEquals(
            listOf(
                HomeSearchFilter.Sender,
                HomeSearchFilter.Scope,
                HomeSearchFilter.Channel,
                HomeSearchFilter.Time,
            ),
            homeSearchFilterOrder(showSenderFilter = true),
        )
    }

    @Test
    fun senderlessSearchKeepsScopeFirst() {
        assertEquals(
            listOf(HomeSearchFilter.Scope, HomeSearchFilter.Channel, HomeSearchFilter.Time),
            homeSearchFilterOrder(showSenderFilter = false),
        )
    }
}
