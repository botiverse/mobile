package build.raft.app

import build.raft.app.settings.HomeSearchFilter
import build.raft.app.settings.homeSearchFilterOrder
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
}
