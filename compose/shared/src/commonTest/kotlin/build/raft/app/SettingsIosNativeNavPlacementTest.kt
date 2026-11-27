package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.nav.RaftIosNativeNavFlag
import build.raft.app.settings.settingsAppPreferenceVisibility
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * artin, task #98: the iOS native navigation switch moved from the hidden Developer page to
 * Settings > Mobile (Beta, off by default), on the same stored key so a choice made there stays.
 * Since 1.27.0 (no Mobile page) it is in Settings > Appearance > Home, still iPhone only.
 */
class SettingsIosNativeNavPlacementTest {
    @BeforeTest @AfterTest fun reset() = RaftIosNativeNavFlag.resetForTests()

    @Test
    fun appearanceShowsTheSwitchOnAnIPhoneOnly() {
        assertTrue(settingsAppPreferenceVisibility(finePointer = false, supportsIosNativeNav = true).iosNativeNav)
        assertFalse(settingsAppPreferenceVisibility(finePointer = false, supportsIosNativeNav = false).iosNativeNav)
        // The default follows the host: off until the iOS app says it is an iPhone.
        assertFalse(settingsAppPreferenceVisibility(finePointer = false).iosNativeNav)
        RaftIosNativeNavFlag.hostSupported = true
        assertTrue(settingsAppPreferenceVisibility(finePointer = false).iosNativeNav)
    }

    @Test
    fun theSwitchKeepsTheStoredKeySoEarlierChoicesStay() {
        // The key the Developer switch wrote in 1.22.
        assertEquals("mobile:ios_native_nav_v1:local_override", RaftIosNativeNavFlag.KEY_LOCAL_OVERRIDE)
        RaftIosNativeNavFlag.hostSupported = true
        val store = InMemoryRaftKeyValueStore()
        assertFalse(RaftIosNativeNavFlag.stored(store), "off by default")
        store.putString(RaftIosNativeNavFlag.KEY_LOCAL_OVERRIDE, "true")
        assertTrue(RaftIosNativeNavFlag.stored(store), "a 1.22 Developer choice reads on in Appearance")
        RaftIosNativeNavFlag.setLocalOverride(store, false)
        assertEquals("false", store.getString(RaftIosNativeNavFlag.KEY_LOCAL_OVERRIDE))
    }
}
