package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Settings › Storage is listed only on a host that reports local storage; otherwise there is no row. */
class SettingsStorageRowTest {
    @Test
    fun storageRowFollowsTheHostsStorageInfo() {
        assertTrue(SettingsDestinationStorage in settingsHomeRowKeys(hasThemeServer = true, storage = true))
        assertFalse(SettingsDestinationStorage in settingsHomeRowKeys(hasThemeServer = true, storage = false))
        assertFalse(settingsStorageAvailable(hasStorageInfo = false))
    }
}
