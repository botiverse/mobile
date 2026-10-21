package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Nav spec 2026-10-21 §5: the wide two-pane Home list must not repeat the rail's Search and
 * Activity entries. Saved is on neither the bar nor the rail and always keeps its row.
 */
class AppShellHomeListShortcutRowsTest {
    @Test fun theWideRailCarriesSearchAndActivitySoTheHomeListDropsThem() {
        assertTrue(appShellNavCarriesSearchAndActivity(navRailActive = true))
        assertFalse(
            homeSidebarShowsSearchAndActivityRows(
                searchAndActivityOnShellNav = appShellNavCarriesSearchAndActivity(navRailActive = true)
            )
        )
    }

    @Test fun thePhoneBarCarriesThemToo() {
        assertTrue(appShellNavCarriesSearchAndActivity(navRailActive = false))
        assertFalse(
            homeSidebarShowsSearchAndActivityRows(
                searchAndActivityOnShellNav = appShellNavCarriesSearchAndActivity(navRailActive = false)
            )
        )
    }

    @Test fun savedIsOnNeitherNavigationSoItKeepsItsHomeRow() {
        assertFalse("saved" in AppShellRailTabKeys)
        assertFalse(AppShellRailSavedKey in AppShellRailOrder)
        assertFalse("saved" in AppShellPhoneBottomBarKeys)
    }

    @Test fun aListWithoutShellNavigationKeepsTheRows() {
        assertTrue(homeSidebarShowsSearchAndActivityRows(searchAndActivityOnShellNav = false))
    }
}
